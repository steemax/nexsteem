/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import com.fasterxml.jackson.databind.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.time.*;
import java.util.*;

/** Linearizable DCS snapshot and lease TTL must identify the very PostgreSQL primary used by SQL. */
final class AuthorityProbe {
  record Proof(String revision,Instant until,String pgNode,String dc,String syncStandby,boolean remoteSync,PgAuthorityTerm term,long checkedNanos) {}
  private final Settings settings;
  private final ControlStore store;
  private final ObjectMapper json=new ObjectMapper();
  private final HttpClient http;
  private final List<String> endpoints;
  private final String scope;
  AuthorityProbe(Settings settings,ControlStore store)throws Exception {
    this.settings=settings;this.store=store;
    String raw=settings.properties.getProperty("NX_CLUSTER_ETCD_ENDPOINTS","");
    endpoints=raw.startsWith("[")?json.readValue(raw,json.getTypeFactory().constructCollectionType(List.class,String.class)):Arrays.stream(raw.split(",")).filter(s->!s.isBlank()).toList();
    if(endpoints.isEmpty())throw new IllegalArgumentException("NX_CLUSTER_ETCD_ENDPOINTS is required");
    boolean test=Boolean.parseBoolean(settings.properties.getProperty("NX_CLUSTER_ALLOW_HTTP_TEST","false"));
    for(String endpoint:endpoints)if(!endpoint.startsWith("https://")&&!(test&&endpoint.startsWith("http://")))throw new IllegalArgumentException("DCS endpoints require TLS");
    scope=settings.properties.getProperty("NX_CLUSTER_ETCD_SCOPE");if(scope==null||!scope.endsWith("/"))throw new IllegalArgumentException("NX_CLUSTER_ETCD_SCOPE must end with /");
    HttpClient.Builder builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3));
    if(!test||settings.properties.containsKey("NX_CLUSTER_ETCD_CA_FILE"))builder.sslContext(tls(settings));
    http=builder.build();
  }
  static SSLContext tls(Settings s)throws Exception {
    String ca=s.properties.getProperty("NX_CLUSTER_ETCD_CA_FILE");
    TrustManager[] trusts=null;KeyManager[] keys=null;
    if(ca!=null){KeyStore trust=KeyStore.getInstance("PKCS12");trust.load(null,null);int index=0;
      try(InputStream in=Files.newInputStream(Path.of(ca))){for(var cert:CertificateFactory.getInstance("X.509").generateCertificates(in))trust.setCertificateEntry("ca"+(index++),cert);}
      TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);trusts=tm.getTrustManagers();}
    String file=s.properties.getProperty("NX_CLUSTER_ETCD_TLS_KEYSTORE_FILE");
    if(file!=null){char[] password=Settings.password(s.properties.getProperty("NX_CLUSTER_ETCD_TLS_PASSWORD_FILE")).toCharArray();KeyStore key=KeyStore.getInstance("PKCS12");try(InputStream in=Files.newInputStream(Path.of(file))){key.load(in,password);}KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(key,password);keys=km.getKeyManagers();Arrays.fill(password,'\0');}
    SSLContext context=SSLContext.getInstance("TLS");context.init(keys,trusts,null);return context;
  }
  Proof probe()throws Exception {
    long begin=System.nanoTime();ControlStore.PgState before=store.pgState();
    if(before.recovery())throw new IllegalStateException("PostgreSQL is a standby");
    Exception last=null;
    for(String endpoint:endpoints){try {
      String token=post(endpoint,"/v3/auth/authenticate",Map.of("name",settings.properties.getProperty("NX_CLUSTER_ETCD_USER","nx-proof"),"password",Settings.password(settings.properties.getProperty("NX_CLUSTER_ETCD_PASSWORD_FILE"))),null).path("token").asText();
      if(token.isBlank())throw new IOException("DCS authentication failed");
      JsonNode range=post(endpoint,"/v3/kv/range",Map.of("key",b64(scope),"range_end",b64(prefixEnd(scope)),"serializable",false),token);
      String revision=range.path("header").path("revision").asText();String leader=null,lease=null,sync="";Map<String,String> domains=new HashMap<>();
      for(JsonNode kv:range.path("kvs")){String key=decode(kv.path("key").asText()),value=decode(kv.path("value").asText());
        if(key.equals(scope+"leader")){leader=value;lease=kv.path("lease").asText();}
        else if(key.equals(scope+"sync")){JsonNode data=json.readTree(value);if(data.path("leader").asText().equals(before.node()))sync=data.path("sync_standby").asText();}
        else if(key.startsWith(scope+"members/")){JsonNode member=json.readTree(value);domains.put(key.substring((scope+"members/").length()),member.path("tags").path("dc").asText());}}
      if(revision.isBlank()||leader==null||!leader.equals(before.node())||lease==null||lease.equals("0"))throw new IOException("DCS authority does not identify the connected PostgreSQL primary");
      JsonNode leaseState=post(endpoint,"/v3/lease/timetolive",Map.of("ID",lease,"keys",true),token);
      long ttl=leaseState.path("TTL").asLong();boolean ownsLeaderKey=false;
      for(JsonNode key:leaseState.path("keys"))if(decode(key.asText()).equals(scope+"leader"))ownsLeaderKey=true;
      if(ttl<=3||!ownsLeaderKey)throw new IOException("DCS lease expired or no longer owns the exact leader key");
      // Anchor to the SQL clock sampled BEFORE the DCS calls. GC pauses cannot extend proof lifetime.
      Instant until=before.clock().plusSeconds(ttl-2);
      ControlStore.PgState after=store.pgState();
      if(after.recovery()||!after.node().equals(leader)||!until.isAfter(after.clock())||System.nanoTime()-begin>Duration.ofSeconds(ttl-2).toNanos())throw new IOException("Authority proof expired or PostgreSQL primary changed during proof");
      JsonNode pg=json.readTree(store.pgStatus());boolean remoteSync=false;
      Set<String> syncNodes=new HashSet<>(Arrays.asList(sync.split(",")));
      if(pg.path("syncCommit").asText().equals("remote_apply")&&!pg.path("syncNames").asText().isBlank())
        for(JsonNode peer:pg.path("peers")){String node=peer.path("node").asText(),dc=domains.get(node);
          if(syncNodes.contains(node)&&peer.path("state").asText().equals("streaming")&&Set.of("sync","quorum").contains(peer.path("sync").asText())&&dc!=null&&!dc.isBlank()&&!dc.equals(after.dc()))remoteSync=true;}
      if(System.nanoTime()-begin>Duration.ofSeconds(ttl-2).toNanos()||!pg.path("node").asText().equals(leader)||!pg.path("dc").asText().equals(after.dc()))throw new IOException("PostgreSQL changed during durability proof");
      return new Proof(revision+":"+leader+":"+lease,until,leader,after.dc(),sync,remoteSync,new PgAuthorityTerm(leader,lease),System.nanoTime());
    }catch(Exception failure){last=failure;}}
    throw new IOException("No authoritative DCS quorum/primary proof",last);
  }
  JsonNode post(String endpoint,String path,Object body,String token)throws Exception {
    HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(endpoint+path)).timeout(Duration.ofSeconds(4)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
    if(token!=null)builder.header("Authorization",token);
    HttpResponse<byte[]> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofByteArray());
    if(response.statusCode()!=200)throw new IOException("DCS request rejected: "+response.statusCode());
    return json.readTree(response.body());
  }
  static String b64(String value){return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
  static String decode(String value){return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}
  static String prefixEnd(String prefix){byte[] b=prefix.getBytes(StandardCharsets.UTF_8);for(int i=b.length-1;i>=0;i--)if((b[i]&255)<255){b[i]++;return new String(Arrays.copyOf(b,i+1),StandardCharsets.UTF_8);}throw new IllegalArgumentException("Invalid DCS prefix");}
}

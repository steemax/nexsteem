/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.sonatype.nexus.cluster.api.ClusterMonitorProof;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded, authenticated observations; never supplies authority to the cluster. */
@Component
@ConditionalOnProperty(name="nexus.cluster.enabled", havingValue="true")
public final class ClusterNodesMonitor {
  record Peer(String node,String dc,URI endpoint) {}
  private final String cluster,requester;
  private final byte[] key;
  private final List<Peer> peers;
  private final ObjectMapper json=new ObjectMapper();
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
      .followRedirects(HttpClient.Redirect.NEVER).build();
  private final ExecutorService requests=Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
  private long cachedAt;
  private Map<String,Object> cached;

  public ClusterNodesMonitor() throws IOException { this(System.getenv()); }

  ClusterNodesMonitor(Map<String,String> env) throws IOException {
    cluster=required(env,"NX_CLUSTER_ID");
    requester=required(env,"NX_CLUSTER_NODE_ID");
    key=Files.readAllBytes(Path.of(required(env,"NX_CLUSTER_SECRET_FILE")));
    if(key.length<32||key.length>4096)throw new IOException("Invalid cluster monitor authentication key");
    int port=Integer.parseInt(env.getOrDefault("NX_CLUSTER_FRONTEND_PORT","8081"));
    if(port<1||port>65535)throw new IOException("Invalid monitor port");
    Map<String,Map<String,String>> members=json.readValue(required(env,"NX_CLUSTER_PEERS_JSON"),new TypeReference<>(){});
    if(members.size()<2||members.size()>32||!members.containsKey(requester))throw new IOException("Invalid monitor membership");
    List<Peer> configured=new ArrayList<>();
    for(var entry:members.entrySet()) {
      String node=entry.getKey(),dc=entry.getValue().get("dc");
      URI peer=URI.create(entry.getValue().get("url"));
      boolean scheme="https".equals(peer.getScheme()) || ("true".equals(env.get("NX_CLUSTER_ALLOW_HTTP_TEST"))&&"http".equals(peer.getScheme()));
      if(!node.matches("[A-Za-z0-9_.-]{1,120}")||dc==null||!dc.matches("[A-Za-z0-9_.-]{1,80}")||!scheme||peer.getHost()==null
          ||peer.getUserInfo()!=null||peer.getQuery()!=null||peer.getFragment()!=null||!List.of("","/").contains(peer.getPath()))
        throw new IOException("Invalid configured monitor origin");
      try { configured.add(new Peer(node,dc,new URI("http",null,peer.getHost(),port,ClusterMonitorProof.PATH,null,null))); }
      catch(java.net.URISyntaxException invalid) { throw new IOException("Invalid monitor origin",invalid); }
    }
    configured.sort(Comparator.comparing(Peer::node));peers=List.copyOf(configured);
  }

  /** Coalesces simultaneous UI polls; failed members are represented explicitly. */
  public synchronized Map<String,Object> snapshot() {
    if(cached!=null&&System.nanoTime()-cachedAt<Duration.ofSeconds(5).toNanos())return cached;
    List<CompletableFuture<Map<String,Object>>> pending=peers.stream()
        .map(peer->CompletableFuture.supplyAsync(()->observe(peer),requests)).toList();
    List<Map<String,Object>> nodes=pending.stream().map(CompletableFuture::join).toList();
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("schemaVersion",1);result.put("cluster",cluster);result.put("sampledAt",Instant.now().toString());
    result.put("summary",summarize(nodes));result.put("nodes",nodes);
    cached=result;cachedAt=System.nanoTime();return result;
  }

  private Map<String,Object> observe(Peer peer) {
    Map<String,Object> node=new LinkedHashMap<>();
    node.put("node",peer.node);node.put("dc",peer.dc);node.put("endpoint",peer.endpoint.toString());
    node.put("reachable",false);node.put("status",Map.of());
    try {
      long time=System.currentTimeMillis();String nonce=ClusterMonitorProof.newNonce();
      HttpRequest.Builder request=HttpRequest.newBuilder(peer.endpoint).timeout(Duration.ofSeconds(3));
      ClusterMonitorProof.requestHeaders(key,cluster,requester,peer.node,time,nonce).forEach(request::header);
      HttpResponse<InputStream> response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofInputStream());
      try(InputStream body=response.body()) {
        var expiry=deadlines.schedule(()->{try{body.close();}catch(IOException ignored){}},3,TimeUnit.SECONDS);
        byte[] bytes;
        try { bytes=body.readNBytes(ClusterMonitorProof.MAX_BODY_BYTES+1); }
        finally { expiry.cancel(false); }
        if(response.statusCode()!=200||bytes.length>ClusterMonitorProof.MAX_BODY_BYTES)throw new IOException("Monitor unavailable");
        if(!ClusterMonitorProof.verifyReply(key,cluster,requester,peer.node,time,nonce,bytes,
            name->response.headers().firstValue(name).orElse(null),System.currentTimeMillis()))
          throw new SecurityException("Monitor authentication failed");
        Map<String,Object> status=json.readValue(bytes,new TypeReference<>(){});
        if(!peer.node.equals(status.get("node"))||!peer.dc.equals(status.get("dc")))throw new SecurityException("Monitor identity mismatch");
        node.put("status",status);node.put("reachable",true);node.put("error","");
      }
    } catch(InterruptedException interrupted) {
      Thread.currentThread().interrupt();node.put("error","Monitor request interrupted");
    } catch(SecurityException unauthenticated) {
      node.put("error","Monitor authentication or identity check failed");
    } catch(Exception unavailable) {
      node.put("error","Member monitor unavailable ("+unavailable.getClass().getSimpleName()+")");
    }
    node.put("observedAt",Instant.now().toString());return node;
  }

  static Map<String,Object> summarize(List<Map<String,Object>> nodes) {
    var reachable=nodes.stream().filter(n->Boolean.TRUE.equals(n.get("reachable"))).toList();
    var writers=reachable.stream().filter(n->yes(status(n),"writerReady")&&yes(status(n),"authority")).toList();
    Map<String,Object> writer=writers.size()==1?status(writers.getFirst()):Map.of();
    boolean caughtUp=reachable.size()==nodes.size()&&reachable.stream().allMatch(n->yes(status(n),"catchup"));
    boolean degraded=yes(writer,"degraded");int required=number(writer,"requiredCopies",2),available=number(writer,"availableCopies",0);
    String state,detail;
    if(writers.size()>1) { state="unknown";detail="Writer observations changed during sampling; refresh to confirm the current writer"; }
    else if(writers.isEmpty()) {
      state=reachable.size()==nodes.size()?"unavailable":"unknown";
      detail=state.equals("unknown")?"Some members cannot be observed; writer availability is unknown":"No ready writer observed";
    } else if(degraded||reachable.size()!=nodes.size()||!caughtUp||available<required||!yes(writer,"pgRemoteSync")) {
      state="degraded";detail=degraded?"Writer accepts degraded writes; remote durability is temporarily reduced":
          reachable.size()!=nodes.size()?"Writer is ready, but some member observations are unavailable":"Writer is ready; replication is recovering";
    } else { state="healthy";detail="One ready writer; all members caught up; remote PostgreSQL sync available"; }
    Map<String,Object> summary=new LinkedHashMap<>();
    summary.put("state",state);summary.put("detail",detail);summary.put("writerNode",writers.size()==1?writers.getFirst().get("node"):"");
    summary.put("writerReady",writers.size()==1);summary.put("degraded",degraded);summary.put("requiredCopies",required);
    summary.put("availableCopies",available);summary.put("caughtUp",caughtUp);
    summary.put("reachableNodes",reachable.size());summary.put("totalNodes",nodes.size());
    for(String field:List.of("pgNode","pgDC","pgSyncStandby","pgTerm"))summary.put(field,writer.getOrDefault(field,""));
    summary.put("pgRemoteSync",yes(writer,"pgRemoteSync"));return summary;
  }

  @SuppressWarnings("unchecked") private static Map<String,Object> status(Map<String,Object> node) { return (Map<String,Object>)node.getOrDefault("status",Map.of()); }
  private static boolean yes(Map<String,Object> status,String name) { return Boolean.TRUE.equals(status.get(name)); }
  private static int number(Map<String,Object> status,String name,int fallback) { return status.get(name) instanceof Number n?n.intValue():fallback; }
  private static String required(Map<String,String> env,String name) throws IOException {
    String value=env.get(name);if(value==null||value.isBlank())throw new IOException("Missing monitor setting: "+name);return value;
  }
  @PreDestroy public void close() { requests.shutdownNow();deadlines.shutdownNow();http.close(); }
}

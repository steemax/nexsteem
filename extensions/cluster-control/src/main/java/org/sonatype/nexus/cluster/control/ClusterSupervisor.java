/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import org.sonatype.nexus.cluster.api.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Always-running member coordinator. Only an acquired, fenced incarnation launches Core. */
public final class ClusterSupervisor implements AutoCloseable {
  final Settings settings;
  final ControlStore store;
  final AuthorityProbe authority;
  final ReplicaAgent replicas;
  final HttpAdmission admission;
  final HttpClient http;
  final ObjectMapper json=new ObjectMapper();
  final ClusterMonitorEndpoint monitor;
  final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
  final List<HttpServer> frontends=new ArrayList<>();
  volatile long epoch;
  volatile Process child;
  volatile PgAuthorityGuard writerAuthority;
  volatile PgAuthorityTerm corePgTerm;
  volatile boolean ready,closed,reachedReady;
  volatile long coreUnhealthySince;
  volatile AuthorityProbe.Proof lastProof;
  volatile String detail="Starting member";
  volatile ControlStore.Leader leader;
  volatile long childStartedNanos;
  ClusterSupervisor(Settings settings)throws Exception {
    System.setProperty("jdk.httpclient.allowRestrictedHeaders","host");
    this.settings=settings;admission=new HttpAdmission(settings.properties);store=new ControlStore(settings);authority=new AuthorityProbe(settings,store);
    Properties replicaConfig=new Properties();replicaConfig.putAll(settings.properties);
    replicaConfig.setProperty("NX_CLUSTER_SQL_USER",settings.controlUser);replicaConfig.setProperty("NX_CLUSTER_SQL_PASSWORD_FILE",settings.controlPasswordFile);
    List<ReplicaAgentFactory> factories=ServiceLoader.load(ReplicaAgentFactory.class).stream().map(ServiceLoader.Provider::get).toList();
    if(factories.size()!=1)throw new IllegalStateException("Exactly one integrated ReplicaAgentFactory is required");
    replicas=factories.getFirst().create(replicaConfig);
    monitor=ClusterMonitorEndpoint.optional(settings.properties,settings.cluster,settings.node,this::status,json);
    System.setProperty("jdk.httpclient.allowRestrictedHeaders","host");
    http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  }
  public static void main(String[] arguments)throws Exception {
    ClusterSupervisor supervisor=new ClusterSupervisor(new Settings(System.getenv()));
    Runtime.getRuntime().addShutdownHook(new Thread(()->{try{supervisor.close();}catch(Exception ignored){}}));
    supervisor.start();new CountDownLatch(1).await();
  }
  void start()throws Exception {
    replicas.start();
    String bind=settings.properties.getProperty("NX_CLUSTER_PUBLIC_BIND_ADDRESS","0.0.0.0");
    listen(bind,settings.frontendPort,settings.corePort,true);
    String mappings=settings.properties.getProperty("NX_CLUSTER_DOCKER_PORTS","");
    for(String entry:mappings.split(",")){if(entry.isBlank())continue;String[] pair=entry.split(":");int port=Integer.parseInt(pair[0]),backend=pair.length==1?port:Integer.parseInt(pair[1]);
      if(port==settings.frontendPort||backend==settings.corePort)throw new IllegalArgumentException("Docker port conflicts with Nexus main port");
      listen(bind,port,backend,false);}
    clock.scheduleWithFixedDelay(this::tick,0,settings.heartbeatSeconds,TimeUnit.SECONDS);
  }
  void listen(String bind,int port,int backend,boolean main)throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress(bind,port),128);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/",exchange->handle(exchange,port,backend,main));server.start();frontends.add(server);
  }
  void tick(){
    if(closed)return;
    try {
      AuthorityProbe.Proof proof=authority.probe();lastProof=proof;
      if(epoch>0){
        if(!store.releaseEligible())throw new IllegalStateException("Application release barrier superseded this Core");
        if(writerAuthority==null||!writerAuthority.renew(proof.term(),()->store.renew(epoch,proof)))throw new IllegalStateException("Writer lease expired or superseded");
        if(child!=null&&!child.isAlive())throw new IllegalStateException("Core child exited");
        ReplicaStatus status=replicas.status();
        boolean degradedAllowed=Boolean.parseBoolean(settings.properties.getProperty("NX_CLUSTER_ALLOW_DEGRADED","false"));
        if(!status.degraded()&&(!status.quorumReady()||!proof.remoteSync())){
          if(!degradedAllowed)throw new IllegalStateException("Strict remote SQL/blob durability unavailable");
          AuthorityProbe.Proof current=authority.probe();requireEpochAuthority(current);store.assertCurrent(epoch);store.policy(epoch,true,current);
          detail="Writer is degraded: remote SQL/blob durability unavailable";
        }else if(status.degraded()&&proof.remoteSync()&&status.availableCopies()>=2&&replicas.isCaughtUp()){
          store.policy(epoch,false,proof);
          if(!replicas.fencePeers(epoch,settings.owner)){store.policy(epoch,true,proof);}
        }
        if(child==null)prepareWriter(proof);
        else if(coreReady()){
          if(!ready){store.phase(epoch,"READY");ready=true;reachedReady=true;}
          coreUnhealthySince=0;
          detail=replicas.status().degraded()?"Writer is degraded: remote SQL/blob durability unavailable":"Writer ready";
        }else {
          ready=false;detail="Core blob stores are not writable";
          if(!reachedReady&&System.nanoTime()-childStartedNanos>Duration.ofMinutes(8).toNanos())throw new IllegalStateException("Core startup deadline exceeded");
          if(reachedReady){
            if(coreUnhealthySince==0)coreUnhealthySince=System.nanoTime();
            int grace=Integer.parseInt(settings.properties.getProperty("NX_CLUSTER_CORE_UNHEALTHY_SECONDS","60"));
            if(grace<15||grace>600)throw new IllegalArgumentException("Core unhealthy grace must be 15..600 seconds");
            if(System.nanoTime()-coreUnhealthySince>Duration.ofSeconds(grace).toNanos())throw new IllegalStateException("Core remained unhealthy beyond the failover grace");
          }
        }
      }else {
        store.fenceExpired();leader=store.leader();
        if(leader==null&&store.releaseEligible()&&replicas.isCaughtUp()){
          long acquired=store.acquire(proof);
          if(acquired>0){writerAuthority=new PgAuthorityGuard(proof.term());epoch=acquired;settings.properties.setProperty("NX_CLUSTER_EPOCH",Long.toString(epoch));prepareWriter(proof);}
        }
        detail=epoch>0?detail:"Passive member; Core stopped";
      }
      leader=store.leader();
    }catch(Exception failure){lastProof=null;detail=failure.getClass().getSimpleName()+": "+failure.getMessage();System.err.println("Cluster member "+settings.node+": "+detail);loseWriter();}
  }
  void requireEpochAuthority(AuthorityProbe.Proof proof){
    PgAuthorityGuard guard=writerAuthority;if(guard==null)throw new IllegalStateException("No bound writer authority");guard.require(proof.term());
  }
  void requireCoreAuthority(AuthorityProbe.Proof proof){
    requireEpochAuthority(proof);if(corePgTerm==null||!corePgTerm.equals(proof.term()))throw new IllegalStateException("Core is bound to a previous PostgreSQL primary term");
  }
  void prepareWriter(AuthorityProbe.Proof proof)throws Exception {
    requireEpochAuthority(proof);
    if(!replicas.isCaughtUp()){detail="Waiting for committed blob catchup";return;}
    if(!proof.remoteSync()||!replicas.fencePeers(epoch,settings.owner)){
      boolean allowed=Boolean.parseBoolean(settings.properties.getProperty("NX_CLUSTER_ALLOW_DEGRADED","false"));
      if(!allowed){detail="Waiting for remote data fence quorum";return;}
      // Data degradation follows established DCS/PG authority; it cannot create leadership.
      AuthorityProbe.Proof current=authority.probe();requireEpochAuthority(current);store.assertCurrent(epoch);store.policy(epoch,true,current);
      if(!replicas.fencePeers(epoch,settings.owner)){detail="Waiting for local durable fence";return;}
    }
    AuthorityProbe.Proof prepared=authority.probe();requireEpochAuthority(prepared);
    if(!writerAuthority.renew(prepared.term(),()->store.renew(epoch,prepared)))throw new IllegalStateException("Writer lost authority during peer fencing");
    store.assertCurrent(epoch);store.phase(epoch,"STARTING");startCore(prepared);detail="Starting fenced Core epoch "+epoch;
  }
  void startCore(AuthorityProbe.Proof proof)throws Exception {
    requireEpochAuthority(proof);corePgTerm=proof.term();
    ProcessBuilder builder=new ProcessBuilder(settings.properties.getProperty("NX_CLUSTER_CORE_COMMAND","/usr/local/bin/nexus-launch"));
    Map<String,String> env=builder.environment();
    env.keySet().removeIf(k->k.startsWith("NX_CLUSTER_ETCD_")||k.startsWith("NX_CLUSTER_CONTROL_")||k.equals("NX_CLUSTER_OWNER_ID")||k.equals("NX_CLUSTER_EPOCH"));
    env.put("NX_CLUSTER_OWNER_ID",settings.owner);env.put("NX_CLUSTER_EPOCH",Long.toString(epoch));env.put("NX_CLUSTER_PG_TERM",corePgTerm.toString());
    String fixedSql=JdbcEpoch.bind(settings.sqlUrl,settings.cluster,settings.owner,epoch);env.put("NX_CLUSTER_SQL_URL",fixedSql);
    String options=env.getOrDefault("NEXUS_JAVA_OPTS","-Xms256m -Xmx1g -XX:MaxDirectMemorySize=512m");
    Map<String,String> props=new LinkedHashMap<>();props.put("nexus.cluster.enabled","true");props.put("nexus.cluster.pg-term",corePgTerm.toString());props.put("nexus.cluster.sql-url",fixedSql);props.put("nexus.datastore.nexus.jdbcUrl",fixedSql);props.put("nexus.cluster.sql-user",settings.sqlUser);props.put("nexus.cluster.sql-password-file",settings.sqlPasswordFile);props.put("nexus.cluster.id",settings.cluster);props.put("nexus.cluster.owner",settings.owner);props.put("nexus.cluster.epoch",Long.toString(epoch));props.put("application-host","127.0.0.1");props.put("application-port",Integer.toString(settings.corePort));
    for(var prop:props.entrySet()){if(prop.getValue().chars().anyMatch(Character::isWhitespace))throw new IllegalArgumentException("Core launch property cannot contain whitespace: "+prop.getKey());options+=" -D"+prop.getKey()+"="+prop.getValue();}
    env.put("NEXUS_JAVA_OPTS",options);builder.inheritIO();childStartedNanos=System.nanoTime();child=builder.start();
  }
  boolean coreReady(){try{
    String time=Long.toString(Instant.now().getEpochSecond()),nonce=UUID.randomUUID().toString();
    String signature=CoreHealthProof.sign(Files.readAllBytes(Path.of(settings.properties.getProperty("NX_CLUSTER_SECRET_FILE"))),settings.cluster,settings.owner,epoch,corePgTerm.toString(),time,nonce);
    HttpRequest request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+settings.corePort+CoreHealthProof.PATH)).timeout(Duration.ofSeconds(7))
      .header("X-Nexus-Health-Time",time).header("X-Nexus-Health-Nonce",nonce).header("X-Nexus-Health-Signature",signature).GET().build();
    return http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200;
  }catch(Exception ignored){return false;}}
  synchronized void loseWriter(){
    ready=false;reachedReady=false;coreUnhealthySince=0;writerAuthority=null;corePgTerm=null;long old=epoch;epoch=0;Process process=child;child=null;
    if(process!=null){process.descendants().forEach(ProcessHandle::destroy);process.destroy();try{if(!process.waitFor(3,TimeUnit.SECONDS)){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}}catch(InterruptedException interrupted){Thread.currentThread().interrupt();process.destroyForcibly();}}
    if(old>0)try{store.release(old);}catch(Exception ignored){}
  }
  Map<String,Object> status(){
    ReplicaStatus replica=replicas.status();Map<String,Object> state=new LinkedHashMap<>();
    state.put("authority",lastProof!=null&&System.nanoTime()-lastProof.checkedNanos()<Duration.ofSeconds(settings.heartbeatSeconds*2L).toNanos());state.put("applicationSHA256",settings.applicationHash);state.put("applicationVersion",settings.version);try{state.put("releaseEligible",store.releaseEligible());}catch(Exception unavailable){state.put("releaseEligible",false);}
    state.put("node",settings.node);state.put("owner",settings.owner);state.put("epoch",epoch);state.put("writerReady",ready);state.put("coreRunning",child!=null&&child.isAlive());state.put("corePgTerm",corePgTerm==null?"":corePgTerm.toString());state.put("epochPgTerm",writerAuthority==null?"":writerAuthority.term().toString());state.put("detail",detail);
    state.put("replica",replica);state.put("degraded",replica.degraded());state.put("requiredCopies",replica.requiredCopies());state.put("availableCopies",replica.availableCopies());state.put("catchup",replica.caughtUp());
    ControlStore.Leader current=leader;if(current!=null){state.put("writer",current.owner());state.put("writerEndpoint",current.endpoint());state.put("writerEpoch",current.epoch());state.put("phase",current.phase());state.put("leaseUntil",current.until().toString());}
    AuthorityProbe.Proof proof=lastProof;if(proof!=null){state.put("authorityRevision",proof.revision());state.put("pgNode",proof.pgNode());state.put("pgTerm",proof.term().toString());state.put("pgDC",proof.dc());state.put("pgSyncStandby",proof.syncStandby());state.put("pgRemoteSync",proof.remoteSync());state.put("authorityProofUntil",proof.until().toString());}
    return state;
  }
  void handle(HttpExchange exchange,int port,int backend,boolean main)throws IOException {
    String path=exchange.getRequestURI().getRawPath();
    if(ClusterMonitorEndpoint.route(exchange,main,monitor))return;
    Semaphore slots=main&&path.startsWith("/_cluster/")?admission.control:admission.proxy;
    boolean admitted=slots.tryAcquire();
    try {
      if(!admitted){
        exchange.getResponseHeaders().set("Connection","close");exchange.getResponseHeaders().set("Retry-After","2");
        respond(exchange,503,Map.of("error","Cluster request capacity exhausted"));return;
      }
      boolean emptyRequest=completeEmptyRequest(exchange);
      if(!emptyRequest&&Set.of("GET","HEAD").contains(exchange.getRequestMethod())){
        exchange.getResponseHeaders().set("Connection","close");respond(exchange,400,Map.of("error","GET and HEAD request bodies are unsupported"));return;
      }
      if(main&&path.startsWith("/_cluster/")){
        if(!exchange.getRequestMethod().equals("GET")){if(!emptyRequest)exchange.getResponseHeaders().set("Connection","close");respond(exchange,405,Map.of("error","GET required"));return;}
        if(path.equals("/_cluster/status")){respond(exchange,200,status());return;}
        if(path.equals("/_cluster/health/member")){respond(exchange,replicas.status().memberReady()?200:503,status());return;}
        if(path.equals("/_cluster/health/writer")){
          if(!ready||epoch<1){respond(exchange,503,status());return;}
          if(!store.releaseEligible()){respond(exchange,503,status());return;}
          AuthorityProbe.Proof proof=authority.probe();requireCoreAuthority(proof);store.assertCurrent(epoch);ReplicaStatus replica=replicas.status();
          if(!replica.quorumReady()||!replica.caughtUp()||(!replica.degraded()&&!proof.remoteSync())){respond(exchange,503,status());return;}
          respond(exchange,200,status());return;
        }
        respond(exchange,404,Map.of("error","Unknown cluster endpoint"));return;
      }
      proxy(exchange,port,backend);
    }catch(Exception failure){if(exchange.getResponseCode()<0)respond(exchange,503,Map.of("error","Cluster writer unavailable","detail",Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));}
    finally{try{exchange.close();}finally{if(admitted)slots.release();}}
  }
  static boolean framedEmptyRequest(HttpExchange exchange){
    if(exchange.getRequestHeaders().getFirst("Transfer-Encoding")!=null)return false;
    String length=exchange.getRequestHeaders().getFirst("Content-Length");
    if(length==null)return true;
    try{return Long.parseLong(length.strip())==0;}catch(NumberFormatException invalid){return false;}
  }
  static boolean completeEmptyRequest(HttpExchange exchange)throws IOException {
    boolean empty=framedEmptyRequest(exchange);
    // drainAmount=0 keeps rejected uploads bounded. JDK HttpServer still needs
    // an explicit EOF read for admitted zero-length requests before keepalive reuse.
    if(empty&&exchange.getRequestBody().read()!=-1)throw new IOException("Unexpected body on an empty request");
    return empty;
  }
  void respond(HttpExchange exchange,int code,Object body)throws IOException {
    byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Cache-Control","no-store");if(exchange.getRequestMethod().equals("HEAD")){exchange.getResponseHeaders().set("Content-Length",Integer.toString(bytes.length));exchange.sendResponseHeaders(code,-1);}
    else{exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);}
  }
  static final Set<String> HOP=Set.of("connection","keep-alive","proxy-authenticate","proxy-authorization","te","trailer","transfer-encoding","upgrade","content-length","expect");
  void proxy(HttpExchange exchange,int port,int backend)throws Exception {
    AuthorityProbe.Proof proof=authority.probe();ControlStore.Leader current=store.leader();
    if(current==null||!current.phase().equals("READY"))throw new IOException("No ready writer");
    if(!current.degraded()&&!proof.remoteSync())throw new IOException("Strict remote PostgreSQL durability unavailable");
    boolean local=current.owner().equals(settings.owner)&&current.epoch()==epoch;
    if(local&&!ready)throw new IOException("Core blob stores are not ready");
    if(local)requireCoreAuthority(proof);
    if(local&&!store.releaseEligible())throw new IOException("Application release barrier changed");
    String hop=exchange.getRequestHeaders().getFirst("X-Nexus-Cluster-Hop");if(!local&&hop!=null)throw new IOException("Cluster forwarding loop");
    URI active=URI.create(current.endpoint());String destination=local?"http://127.0.0.1:"+backend:active.getScheme()+"://"+active.getHost()+":"+(port==settings.frontendPort?active.getPort():port);
    HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(destination+exchange.getRequestURI().toASCIIString())).timeout(Duration.ofMinutes(30));
    Set<String> excluded=new HashSet<>(HOP);String connection=exchange.getRequestHeaders().getFirst("Connection");if(connection!=null)for(String name:connection.split(","))excluded.add(name.strip().toLowerCase(Locale.ROOT));
    exchange.getRequestHeaders().forEach((name,values)->{if(!excluded.contains(name.toLowerCase(Locale.ROOT))&&!name.equalsIgnoreCase("X-Nexus-Cluster-Hop"))for(String value:values)request.header(name,value);});
    if(!local)request.header("X-Nexus-Cluster-Hop",settings.node);
    if(exchange.getRequestHeaders().getFirst("X-Forwarded-Host")==null)request.header("X-Forwarded-Host",Objects.toString(exchange.getRequestHeaders().getFirst("Host"),settings.endpoint));
    if(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto")==null)request.header("X-Forwarded-Proto","http");
    String method=exchange.getRequestMethod();boolean mutating=!Set.of("GET","HEAD","OPTIONS").contains(method);
    HttpRequest.BodyPublisher body=framedEmptyRequest(exchange)?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofInputStream(exchange::getRequestBody);
    HttpResponse<InputStream> response=http.send(request.method(method,body).build(),HttpResponse.BodyHandlers.ofInputStream());
    Path buffered=null;boolean acknowledgedDegraded=current.degraded();
    try(InputStream in=response.body()){
      boolean positive=response.statusCode()>=200&&response.statusCode()<400;
      if(mutating&&positive){
        buffered=Files.createTempFile(settings.root,"response-",".pending");Files.copy(in,buffered,StandardCopyOption.REPLACE_EXISTING);
      }
      if(positive){
        // GET can mutate proxy caches or complete OAuth login. Check every positive response
        // before headers; keep large GET bodies streaming instead of buffering downloads.
        AuthorityProbe.Proof acknowledgedProof=authority.probe();ControlStore.Leader after=store.leader();
        if(after==null||after.epoch()!=current.epoch()||!after.owner().equals(current.owner())||!after.phase().equals("READY"))throw new IOException("Writer changed before request acknowledgement");
        if(!after.degraded()&&!acknowledgedProof.remoteSync())throw new IOException("Remote PostgreSQL durability disappeared before acknowledgement");
        if(!proof.term().equals(acknowledgedProof.term()))throw new IOException("PostgreSQL primary term changed during the request");
        if(local)requireCoreAuthority(acknowledgedProof);
        else if(!acknowledgedProof.term().toString().equals(response.headers().firstValue("X-Nexus-Cluster-PG-Term").orElse("")))throw new IOException("Writer response has no matching primary term fence");
        acknowledgedDegraded=after.degraded();
        if(local){if(!store.releaseEligible())throw new IOException("Application release barrier changed before acknowledgement");store.assertCurrent(current.epoch());}
      }
      response.headers().map().forEach((name,values)->{if(!HOP.contains(name.toLowerCase(Locale.ROOT)))exchange.getResponseHeaders().put(name,new ArrayList<>(values));});
      if(positive){exchange.getResponseHeaders().set("X-Nexus-Cluster-Epoch",Long.toString(current.epoch()));exchange.getResponseHeaders().set("X-Nexus-Cluster-PG-Term",proof.term().toString());}
      if(acknowledgedDegraded)exchange.getResponseHeaders().set("X-Nexus-Cluster-Degraded","true");
      boolean noBody=method.equals("HEAD")||response.statusCode()==204||response.statusCode()==304;
      long representationLength=-1;
      String originalLength=response.headers().firstValue("Content-Length").orElse(null);
      if(originalLength!=null)try{representationLength=Long.parseLong(originalLength);if(representationLength<0)representationLength=-1;}catch(NumberFormatException ignored){}
      // JDK HttpServer requires HEAD/304 representation length to be set manually.
      // Registry and package clients use this header without receiving the body.
      if((method.equals("HEAD")||response.statusCode()==304)&&representationLength>=0&&response.statusCode()!=204)
        exchange.getResponseHeaders().set("Content-Length",Long.toString(representationLength));
      long framing=noBody?-1:representationLength<0?0:representationLength==0?-1:representationLength;
      exchange.sendResponseHeaders(response.statusCode(),framing);
      if(!noBody&&representationLength!=0){if(buffered!=null)Files.copy(buffered,exchange.getResponseBody());else in.transferTo(exchange.getResponseBody());}
    }finally{if(buffered!=null)Files.deleteIfExists(buffered);}
  }
  @Override public void close()throws Exception {closed=true;clock.shutdownNow();loseWriter();for(HttpServer server:frontends)server.stop(1);replicas.close();}
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.sonatype.nexus.cluster.api.ClusterMonitorProof;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ClusterNodesMonitorTest {
  private static final byte[] KEY="monitor-fixture-key-at-least-thirty-two-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private static final ObjectMapper JSON=new ObjectMapper();

  private Map<String,Object> live(String name,boolean writer) {
    return Map.of("node",name,"dc",name.equals("a")?"dc1":"dc2","writerReady",writer,"authority",true,
        "coreRunning",writer,"catchup",true,"degraded",false,"requiredCopies",2,"availableCopies",2,"pgRemoteSync",true);
  }

  private Map<String,Object> observed(String name,boolean reachable,boolean writer) {
    return Map.of("node",name,"reachable",reachable,"status",reachable?live(name,writer):Map.of());
  }

  @Test void passiveStoppedCoreIsHealthyAndUnavailableMemberIsExplicit() {
    var healthy=ClusterNodesMonitor.summarize(List.of(observed("a",true,true),observed("b",true,false)));
    assertEquals("healthy",healthy.get("state"));assertEquals("a",healthy.get("writerNode"));
    var unavailable=ClusterNodesMonitor.summarize(List.of(observed("a",true,false),observed("b",true,false)));
    assertEquals("unavailable",unavailable.get("state"));
    var unknown=ClusterNodesMonitor.summarize(List.of(observed("a",true,false),observed("b",false,false)));
    assertEquals("unknown",unknown.get("state"));assertEquals(false,unknown.get("writerReady"));
    var partial=ClusterNodesMonitor.summarize(List.of(observed("a",true,true),observed("b",false,false)));
    assertEquals("degraded",partial.get("state"));assertEquals(false,partial.get("degraded"));
    var mixed=ClusterNodesMonitor.summarize(List.of(observed("a",true,true),observed("b",true,true)));
    assertEquals("unknown",mixed.get("state"));assertEquals(false,mixed.get("writerReady"));
  }

  @Test void authenticatesRepliesAndCoalescesPollsWithoutUsingForgedWriter() throws Exception {
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    AtomicInteger reads=new AtomicInteger();
    server.createContext(ClusterMonitorProof.PATH,x->{
      try {
        reads.incrementAndGet();var h=x.getRequestHeaders();
        String requester=h.getFirst("X-Nexus-Monitor-Requester"),target=h.getFirst("X-Nexus-Monitor-Target"),nonce=h.getFirst("X-Nexus-Monitor-Nonce");
        long time=Long.parseLong(h.getFirst("X-Nexus-Monitor-Time"));
        assertTrue(ClusterMonitorProof.verifyRequest(KEY,"fixture",requester,target,time,nonce,
            h.getFirst("X-Nexus-Monitor-Signature"),System.currentTimeMillis()));
        byte[] body=JSON.writeValueAsBytes(live(target,target.equals("a")));
        // A response with a valid signature for the wrong target must be rejected.
        ClusterMonitorProof.replyHeaders(KEY,"fixture",requester,"b".equals(target)?"a":target,time,nonce,body)
            .forEach((name,value)->x.getResponseHeaders().set(name,value));
        x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);
      } finally { x.close(); }
    });server.start();
    var keyFile=Files.createTempFile("monitor-proof-",".key");Files.write(keyFile,KEY);
    Map<String,String> env=Map.of("NX_CLUSTER_ID","fixture","NX_CLUSTER_NODE_ID","a","NX_CLUSTER_SECRET_FILE",keyFile.toString(),
        "NX_CLUSTER_FRONTEND_PORT",Integer.toString(server.getAddress().getPort()),"NX_CLUSTER_PEERS_JSON",
        "{\"a\":{\"url\":\"https://127.0.0.1:8095\",\"dc\":\"dc1\"},\"b\":{\"url\":\"https://127.0.0.1:8095\",\"dc\":\"dc2\"}}");
    try {
      var monitor=new ClusterNodesMonitor(env);
      try {
        var first=monitor.snapshot();assertSame(first,monitor.snapshot());assertEquals(2,reads.get());
        @SuppressWarnings("unchecked") var summary=(Map<String,Object>)first.get("summary");
        assertEquals("degraded",summary.get("state"));assertEquals("a",summary.get("writerNode"));
        @SuppressWarnings("unchecked") var nodes=(List<Map<String,Object>>)first.get("nodes");
        assertEquals(true,nodes.get(0).get("reachable"));assertEquals(false,nodes.get(1).get("reachable"));
        assertTrue(nodes.get(1).get("error").toString().contains("authentication"));
      } finally { monitor.close(); }
    } finally { server.stop(0);Files.deleteIfExists(keyFile); }
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.sonatype.nexus.cluster.api.ClusterMonitorProof;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.Assert.*;

public class ClusterMonitorEndpointTest {
  private static final byte[] KEY = "a".repeat(64).getBytes(StandardCharsets.UTF_8);
  private static final long TIME = 1_700_000_000_000L;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicInteger snapshots = new AtomicInteger();
  private volatile Supplier<Map<String, Object>> snapshot;
  private volatile ClusterMonitorEndpoint endpoint;
  private HttpServer server;
  private ExecutorService executor;
  private HttpClient client;
  private HttpAdmission admission;
  private URI uri;

  @Before public void start() throws Exception {
    admission = new HttpAdmission(new Properties());
    snapshot = () -> Map.of("writerReady", true, "epoch", 9,
        "replica", Map.of("caughtUp", true, "detail", "pendingHeads=0"));
    endpoint = new ClusterMonitorEndpoint(KEY, "cluster", "node2", "dc2", Set.of("node1", "node2"),
        () -> { snapshots.incrementAndGet(); return snapshot.get(); }, json,
        Clock.fixed(Instant.ofEpochMilli(TIME), ZoneOffset.UTC));
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
    executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
    server.createContext("/", exchange -> {
      if (ClusterMonitorEndpoint.route(exchange, true, endpoint)) return;
      boolean admitted = admission.control.tryAcquire();
      try {
        byte[] bytes = "health".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(admitted ? 200 : 503, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally { if (admitted) admission.control.release(); exchange.close(); }
    });
    server.start(); uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  @After public void stop() {
    if (server != null) server.stop(0);
    if (executor != null) executor.close();
    if (client != null) client.close();
  }

  @Test public void authenticatedReplyContainsFullStatusAndBoundNodeIdentity() throws Exception {
    String nonce = ClusterMonitorProof.newNonce();
    var response = send("GET", "node1", "node2", TIME, nonce);
    assertEquals(200, response.statusCode());
    assertTrue(ClusterMonitorProof.verifyReply(KEY, "cluster", "node1", "node2", TIME, nonce,
        response.body(), name -> response.headers().firstValue(name).orElse(null), TIME));
    var value = json.readTree(response.body());
    assertTrue(value.path("writerReady").asBoolean()); assertEquals(9, value.path("epoch").asInt());
    assertEquals("pendingHeads=0", value.path("replica").path("detail").asText());
    assertEquals("node2", value.path("node").asText()); assertEquals("dc2", value.path("dc").asText());
    assertEquals(Instant.ofEpochMilli(TIME).toString(), value.path("sampledAt").asText());
    assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null));
    assertEquals(1, snapshots.get());
  }

  @Test public void unauthenticatedUnknownRequesterWrongTargetAndStaleRequestsAreDeniedBeforeSnapshot() throws Exception {
    assertEquals(403, client.send(HttpRequest.newBuilder(uri.resolve(ClusterMonitorProof.PATH)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    assertEquals(403, send("GET", "unknown", "node2", TIME, ClusterMonitorProof.newNonce()).statusCode());
    assertEquals(403, send("GET", "node1", "other", TIME, ClusterMonitorProof.newNonce()).statusCode());
    assertEquals(403, send("GET", "node1", "node2", TIME - 30_001, ClusterMonitorProof.newNonce()).statusCode());
    var partial = HttpRequest.newBuilder(uri.resolve(ClusterMonitorProof.PATH));
    ClusterMonitorProof.requestHeaders(KEY, "cluster", "node1", "node2", TIME, ClusterMonitorProof.newNonce())
        .forEach((name, value) -> { if (!name.equals(ClusterMonitorProof.REQUESTER)) partial.header(name, value); });
    assertEquals(403, client.send(partial.GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    assertEquals(0, snapshots.get());
  }

  @Test public void rejectsMethodsQueriesAndBodiesWithoutTakingSnapshot() throws Exception {
    assertEquals(405, send("POST", "node1", "node2", TIME, ClusterMonitorProof.newNonce()).statusCode());
    var headers = ClusterMonitorProof.requestHeaders(KEY, "cluster", "node1", "node2", TIME, ClusterMonitorProof.newNonce());
    var query = HttpRequest.newBuilder(uri.resolve(ClusterMonitorProof.PATH + "?target=other"));
    headers.forEach(query::header);
    assertEquals(403, client.send(query.GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    var body = HttpRequest.newBuilder(uri.resolve(ClusterMonitorProof.PATH)); headers.forEach(body::header);
    assertEquals(400, client.send(body.method("GET", HttpRequest.BodyPublishers.ofString("unused")).build(),
        HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    assertEquals(0, snapshots.get());
  }

  @Test public void oversizedOrFailedSnapshotsDoNotLeakInternalDetailsOrReturnSignedHealthyStatus() throws Exception {
    snapshot = () -> Map.of("detail", "x".repeat(ClusterMonitorProof.MAX_BODY_BYTES + 1));
    var huge = send("GET", "node1", "node2", TIME, ClusterMonitorProof.newNonce());
    assertEquals(503, huge.statusCode()); assertTrue(huge.body().length < 100);
    assertTrue(huge.headers().firstValue(ClusterMonitorProof.SIGNATURE).isEmpty());
    snapshot = () -> { throw new IllegalStateException("PRIVATE_MONITOR_SECRET"); };
    var failed = send("GET", "node1", "node2", TIME, ClusterMonitorProof.newNonce());
    assertEquals(503, failed.statusCode());
    assertFalse(new String(failed.body(), StandardCharsets.UTF_8).contains("PRIVATE_MONITOR_SECRET"));
  }

  @Test public void busyMonitorDoesNotConsumeHealthAdmission() throws Exception {
    CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
    snapshot = () -> {
      entered.countDown();
      try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
      catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
      return Map.of("writerReady", true);
    };
    var first = client.sendAsync(request("GET", "node1", "node2", TIME, ClusterMonitorProof.newNonce()), HttpResponse.BodyHandlers.ofByteArray());
    var second = client.sendAsync(request("GET", "node1", "node2", TIME, ClusterMonitorProof.newNonce()), HttpResponse.BodyHandlers.ofByteArray());
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertEquals(503, send("GET", "node1", "node2", TIME, ClusterMonitorProof.newNonce()).statusCode());
      assertEquals(4, admission.control.availablePermits());
      assertEquals(200, client.send(HttpRequest.newBuilder(uri.resolve("/_cluster/health/member")).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    } finally { release.countDown(); }
    assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
    assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
  }

  @Test public void invalidOptionalConfigurationDisablesOnlyMonitorRoute() throws Exception {
    var file = Files.createTempFile("nexus-monitor-test-key", ".key");
    try {
      Files.write(file, KEY); Properties config = new Properties();
      config.setProperty("NX_CLUSTER_SECRET_FILE", file.toString());
      config.setProperty("NX_CLUSTER_PEERS_JSON", "{\"node1\":{\"dc\":\"dc1\"},\"node2\":{}}");
      endpoint = ClusterMonitorEndpoint.optional(config, "cluster", "node2", snapshot, json);
      assertNull(endpoint);
      assertEquals(403, send("GET", "node1", "node2", TIME, ClusterMonitorProof.newNonce()).statusCode());
      assertEquals(200, client.send(HttpRequest.newBuilder(uri.resolve("/_cluster/health/member")).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray()).statusCode());
      assertEquals(4, admission.control.availablePermits()); assertEquals(0, snapshots.get());
    } finally { Files.deleteIfExists(file); }
  }

  private HttpRequest request(String method, String requester, String target, long time, String nonce) {
    var builder = HttpRequest.newBuilder(uri.resolve(ClusterMonitorProof.PATH)).timeout(Duration.ofSeconds(5));
    ClusterMonitorProof.requestHeaders(KEY, "cluster", requester, target, time, nonce).forEach(builder::header);
    return builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
  }
  private HttpResponse<byte[]> send(String method, String requester, String target, long time, String nonce) throws Exception {
    return client.send(request(method, requester, target, time, nonce), HttpResponse.BodyHandlers.ofByteArray());
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import org.sonatype.nexus.cluster.api.ClusterMonitorProof;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** Diagnostic snapshots have their own admission limit and never change cluster ownership. */
final class ClusterMonitorEndpoint {
  private final byte[] key;
  private final String cluster, node, dc;
  private final Set<String> members;
  private final Supplier<Map<String, Object>> status;
  private final ObjectMapper json;
  private final Clock clock;
  private final Semaphore capacity = new Semaphore(2);

  static ClusterMonitorEndpoint optional(Properties properties, String cluster, String node,
                                         Supplier<Map<String, Object>> status, ObjectMapper json) {
    try { return new ClusterMonitorEndpoint(properties, cluster, node, status, json); }
    catch (Exception unavailable) { System.err.println("Cluster monitor unavailable"); return null; }
  }

  /** Called before the supervisor's health/proxy semaphores are acquired. */
  static boolean route(HttpExchange exchange, boolean main, ClusterMonitorEndpoint endpoint) throws IOException {
    if (!main || !ClusterMonitorProof.PATH.equals(exchange.getRequestURI().getRawPath())) return false;
    if (endpoint != null) endpoint.handle(exchange);
    else { try { error(exchange, 403, "Forbidden"); } finally { exchange.close(); } }
    return true;
  }

  ClusterMonitorEndpoint(Properties properties, String cluster, String node,
                         Supplier<Map<String, Object>> status, ObjectMapper json) throws IOException {
    this(readKey(properties.getProperty("NX_CLUSTER_SECRET_FILE")), cluster, node,
        configuredDc(properties, node, json), configuredMembers(properties, json), status, json, Clock.systemUTC());
  }

  ClusterMonitorEndpoint(byte[] key, String cluster, String node, String dc, Set<String> members,
                         Supplier<Map<String, Object>> status, ObjectMapper json, Clock clock) {
    // Validate context here rather than discovering a malformed secret on each request.
    ClusterMonitorProof.signRequest(key, cluster, node, node, clock.millis(), ClusterMonitorProof.newNonce());
    if (dc == null || dc.isBlank() || dc.length() > 253 || !members.contains(node)) {
      throw new IllegalArgumentException("Monitor membership unavailable");
    }
    this.key = key.clone(); this.cluster = cluster; this.node = node; this.dc = dc;
    this.members = Set.copyOf(members); this.status = status; this.json = json; this.clock = clock;
  }

  void handle(HttpExchange exchange) throws IOException {
    boolean admitted = false;
    try {
      if (!exchange.getRequestMethod().equals("GET")) { error(exchange, 405, "GET required"); return; }
      if (!ClusterMonitorProof.PATH.equals(exchange.getRequestURI().getRawPath())
          || exchange.getRequestURI().getRawQuery() != null) { error(exchange, 403, "Forbidden"); return; }
      String requester = header(exchange, ClusterMonitorProof.REQUESTER);
      String target = header(exchange, ClusterMonitorProof.TARGET);
      String timeHeader = header(exchange, ClusterMonitorProof.TIME);
      String nonce = header(exchange, ClusterMonitorProof.NONCE);
      long time;
      try {
        time = Long.parseLong(timeHeader);
        if (!Long.toString(time).equals(timeHeader)) { error(exchange, 403, "Forbidden"); return; }
      }
      catch (NumberFormatException invalid) { error(exchange, 403, "Forbidden"); return; }
      if (requester == null || target == null || nonce == null || !node.equals(target) || !members.contains(requester)
          || !ClusterMonitorProof.verifyRequest(key, cluster, requester, target, time, nonce,
              header(exchange, ClusterMonitorProof.SIGNATURE), clock.millis())) {
        error(exchange, 403, "Forbidden"); return;
      }
      // Reject declared bodies without reading them; diagnostic GET never needs one.
      if (!ClusterSupervisor.framedEmptyRequest(exchange)) { error(exchange, 400, "GET body unsupported"); return; }
      if (!ClusterSupervisor.completeEmptyRequest(exchange)) { error(exchange, 400, "GET body unsupported"); return; }
      admitted = capacity.tryAcquire();
      if (!admitted) { error(exchange, 503, "Monitor busy"); return; }
      Map<String, Object> snapshot = new LinkedHashMap<>(status.get());
      snapshot.put("node", node); snapshot.put("dc", dc); snapshot.put("sampledAt", clock.instant().toString());
      BoundedOutput output = new BoundedOutput();
      json.writeValue(output, snapshot);
      byte[] bytes = output.bytes();
      ClusterMonitorProof.replyHeaders(key, cluster, requester, target, time, nonce, bytes)
          .forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
    }
    catch (RuntimeException | IOException unavailable) { if (exchange.getResponseCode() < 0) error(exchange, 503, "Monitor unavailable"); }
    finally { if (admitted) capacity.release(); exchange.close(); }
  }

  private static String header(HttpExchange exchange, String name) {
    var values = exchange.getRequestHeaders().get(name);
    return values == null || values.size() != 1 ? null : values.getFirst();
  }

  private static void error(HttpExchange exchange, int code, String message) throws IOException {
    byte[] body = ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("Connection", "close");
    exchange.sendResponseHeaders(code, body.length); exchange.getResponseBody().write(body);
  }

  private static byte[] readKey(String filename) throws IOException {
    if (filename == null) throw new IOException("Monitor key unavailable");
    Path path = Path.of(filename);
    if (Files.size(path) > 4096) throw new IOException("Monitor key unavailable");
    return Files.readAllBytes(path);
  }

  private static Set<String> configuredMembers(Properties properties, ObjectMapper json) throws IOException {
    var peers = json.readTree(properties.getProperty("NX_CLUSTER_PEERS_JSON", "{}"));
    if (!peers.isObject() || peers.size() > 64) throw new IOException("Monitor membership unavailable");
    Set<String> members = new HashSet<>(); peers.fieldNames().forEachRemaining(members::add); return members;
  }

  private static String configuredDc(Properties properties, String node, ObjectMapper json) throws IOException {
    return json.readTree(properties.getProperty("NX_CLUSTER_PEERS_JSON", "{}")).path(node).path("dc").asText();
  }

  private static final class BoundedOutput extends OutputStream {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int count;
    @Override public void write(int value) throws IOException {
      if (count >= ClusterMonitorProof.MAX_BODY_BYTES) throw new IOException("Monitor reply too large");
      output.write(value); count++;
    }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > ClusterMonitorProof.MAX_BODY_BYTES - count) throw new IOException("Monitor reply too large");
      output.write(bytes, offset, length); count += length;
    }
    byte[] bytes() { return output.toByteArray(); }
  }
}

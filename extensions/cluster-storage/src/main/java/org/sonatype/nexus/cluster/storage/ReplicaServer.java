/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/** Replica service keeps running when the supervisor stops the Core process on passive Pods. */
public final class ReplicaServer implements AutoCloseable
{
  private final ReplicationEngine engine;
  private final PeerAuthentication auth;
  private final ObjectMapper json = new ObjectMapper();
  private final HttpServer server;
  private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  public ReplicaServer(final ReplicationEngine engine, final SSLContext tls) throws IOException {
    this.engine = engine;
    auth = new PeerAuthentication(engine.settings.key, engine.settings.cluster);
    InetSocketAddress address = new InetSocketAddress(engine.settings.port);
    System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(engine.settings.transferTimeoutSeconds));
    if (engine.settings.testHttp) { server = HttpServer.create(address, 64); }
    else {
      if (tls == null) { throw new IOException("Replica server requires TLS"); }
      HttpsServer https = HttpsServer.create(address, 64);
      https.setHttpsConfigurator(new HttpsConfigurator(tls)); server = https;
    }
    server.setExecutor(executor);
    server.createContext("/", this::handle);
  }

  public void start() { server.start(); }

  private void handle(final HttpExchange x) throws IOException {
    try {
      String path = x.getRequestURI().getRawPath(), method = x.getRequestMethod();
      java.util.List<String> length = x.getRequestHeaders().get("Content-Length");
      boolean empty = !x.getRequestHeaders().containsKey("Transfer-Encoding")
          && (length == null || (length.size() == 1 && "0".equals(length.get(0))));
      if ("GET".equals(method) && !empty) {
        x.getResponseHeaders().set("Connection", "close"); respond(x, 400, ""); return;
      }
      // The framing proves this read cannot block, including health/status/object GETs.
      // JDK HTTPS otherwise closes the reusable TLS socket when drainAmount is zero.
      if (empty && x.getRequestBody().readNBytes(1).length != 0) { respond(x, 400, ""); return; }
      if (x.getRequestURI().getRawQuery() != null) { respond(x, 400, ""); return; }
      if ("/health/member".equals(path) && "GET".equals(method)) {
        engine.journal.missing(engine.settings.cluster, engine.settings.node, engine.generation, 1);
        if (!engine.objects.available()) { respond(x, 503, "{\"ready\":false}"); return; }
        respond(x, 200, "{\"ready\":true}"); return;
      }
      if ("/health/writer".equals(path) && "GET".equals(method)) {
        boolean ready = engine.journal.validWriter(engine.settings.cluster, engine.settings.owner, engine.settings.epoch)
            && engine.caughtUp() && engine.quorumAvailable();
        respond(x, ready ? 200 : 503, "{\"ready\":" + ready + "}"); return;
      }
      authenticate(x);
      if ("/v1/status".equals(path) && "GET".equals(method)) {
        if (!engine.objects.available()) { respond(x, 503, "{\"ready\":false}"); return; }
        String body = json.writeValueAsString(Map.of("node", engine.settings.node, "cluster", engine.settings.cluster,
            "generation", engine.generation, "dc", engine.settings.domains.get(engine.settings.node),
            "caughtUp", engine.caughtUp(), "fence", readFence()));
        String digest = HexFormat.of().formatHex(DurableObjects.digest("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        x.getResponseHeaders().set("X-Cluster-Reply", auth.sign(engine.settings.node, "REPLY", path,
            header(x, "X-Cluster-Time"), header(x, "X-Cluster-Nonce"), digest));
        respond(x, 200, body); return;
      }
      if ("/v1/fence".equals(path) && "POST".equals(method)) {
        byte[] body = x.getRequestBody().readNBytes(4097);
        if (body.length > 4096 || !HexFormat.of().formatHex(DurableObjects.digest("SHA-256").digest(body))
            .equals(header(x, "X-Cluster-Digest"))) { respond(x, 400, ""); return; }
        JsonNode v = json.readTree(body);
        long epoch = v.path("epoch").asLong(0); String owner = v.path("owner").asText("");
        if (!engine.journal.validWriter(engine.settings.cluster, owner, epoch)) { respond(x, 409, ""); return; }
        persistFence(epoch, owner);
        respond(x, 204, ""); return;
      }
      if (path.matches("/v1/(stage|retain|objects)/[1-9][0-9]{0,18}")) {
        boolean retain = path.startsWith("/v1/retain/");
        boolean stage = !path.startsWith("/v1/objects/");
        if (!(stage ? "POST" : "GET").equals(method)) { respond(x, 405, ""); return; }
        // With drainAmount=0 even a zero-length request needs an explicit EOF read.
        // Otherwise JDK HTTPS closes the reused connection after the retain response.
        if (retain && x.getRequestBody().readNBytes(1).length != 0) { respond(x, 400, ""); return; }
        long sequence = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
        BlobVersion v = engine.journal.event(sequence, !stage);
        if (v == null || !engine.settings.cluster.equals(v.cluster)
            || !v.sha256.equals(header(x, "X-Cluster-Digest"))) { respond(x, 404, ""); return; }
        if (stage) {
          if (!retain && "HARD_DELETED".equals(v.state)
              && x.getRequestBody().readNBytes(1).length != 0) { respond(x, 400, ""); return; }
          engine.journal.assertCurrent(v.cluster, v.owner, v.epoch);
          persistFence(v.epoch, v.owner);
          if (retain && !"HARD_DELETED".equals(v.state)) {
            if (!engine.objects.has(v)) { respond(x, 404, ""); return; }
            engine.objects.record(v);
          }
          else if ("HARD_DELETED".equals(v.state)) { engine.objects.record(v); }
          else { engine.objects.receive(v, x.getRequestBody()); }
          // Recheck after large bodies: an expired leader's staged copy never becomes a publishable acknowledgement.
          engine.journal.assertCurrent(v.cluster, v.owner, v.epoch);
          engine.journal.acknowledge(v.sequence, engine.settings.node, engine.generation);
          x.getResponseHeaders().set("X-Cluster-Generation", engine.generation);
          x.getResponseHeaders().set("X-Cluster-Ack", auth.sign(engine.settings.node, "ACK", path,
              Long.toString(v.epoch), Long.toString(v.sequence), v.sha256 + ":" + engine.generation));
          respond(x, 204, ""); return;
        }
        if (!engine.objects.has(v)) { respond(x, 404, ""); return; }
        x.getResponseHeaders().set("Content-Type", "application/octet-stream");
        x.sendResponseHeaders(200, v.size);
        try (java.io.InputStream bytes = engine.objects.open(v.sha256)) { bytes.transferTo(x.getResponseBody()); }
        return;
      }
      respond(x, 404, "");
    }
    catch (SecurityException e) { respond(x, 401, ""); }
    catch (Exception e) { respond(x, 503, "{\"ready\":false}"); }
    finally { x.close(); }
  }

  private void authenticate(final HttpExchange x) {
    String node = header(x, "X-Cluster-Node");
    if (!engine.settings.cluster.equals(header(x, "X-Cluster-Id")) || !engine.settings.members.containsKey(node)) {
      throw new SecurityException("Unconfigured replica identity");
    }
    auth.validate(node, x.getRequestMethod(), x.getRequestURI().getRawPath(), header(x, "X-Cluster-Time"),
        header(x, "X-Cluster-Nonce"), header(x, "X-Cluster-Digest"), header(x, "X-Cluster-Signature"));
  }

  private static String header(final HttpExchange x, final String name) {
    java.util.List<String> values = x.getRequestHeaders().get(name);
    if (values == null || values.size() != 1) { throw new SecurityException("Missing or repeated replica header"); }
    return values.get(0);
  }

  public synchronized Map<String, Object> readFence() throws IOException {
    Path path = engine.objects.root().resolve("fence.json");
    if (!Files.exists(path)) { return Map.of("epoch", 0L, "owner", ""); }
    JsonNode v = json.readTree(Files.readAllBytes(path));
    return Map.of("epoch", v.path("epoch").asLong(), "owner", v.path("owner").asText());
  }

  public synchronized void persistFence(final long epoch, final String owner) throws IOException {
    Map<String, Object> old = readFence();
    long previous = ((Number) old.get("epoch")).longValue();
    if (epoch < previous || (epoch == previous && !owner.equals(old.get("owner")))) {
      throw new IOException("Replica refuses a stale fencing epoch");
    }
    if (epoch == previous) { return; }
    Path path = engine.objects.root().resolve("fence.json"), stage = engine.objects.root().resolve("fence.next");
    byte[] bytes = json.writeValueAsBytes(Map.of("epoch", epoch, "owner", owner));
    try (FileChannel c = FileChannel.open(stage, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE)) {
      ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) { c.write(b); } c.force(true);
    }
    Files.move(stage, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    DurableObjects.forceDirectory(engine.objects.root());
  }

  private static void respond(final HttpExchange x, final int code, final String body) throws IOException {
    java.util.List<String> length = x.getRequestHeaders().get("Content-Length");
    if (code >= 400 && (x.getRequestHeaders().containsKey("Transfer-Encoding")
        || (length != null && (length.size() != 1 || !"0".equals(length.get(0)))))) {
      x.getResponseHeaders().set("Connection", "close");
    }
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().set("Content-Type", "application/json");
    x.sendResponseHeaders(code, code == 204 ? -1 : bytes.length);
    if (code != 204) { x.getResponseBody().write(bytes); }
  }

  @Override public void close() { server.stop(0); executor.shutdownNow(); }
}

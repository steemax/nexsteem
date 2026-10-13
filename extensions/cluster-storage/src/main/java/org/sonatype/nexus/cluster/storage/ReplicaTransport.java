/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import javax.net.ssl.SSLContext;

public final class ReplicaTransport
{
  private final ClusterSettings settings;
  private final PeerAuthentication auth;
  private final HttpClient http;
  private static final java.util.concurrent.ScheduledExecutorService deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "replica-transfer-deadline"); t.setDaemon(true); return t;
  });

  public ReplicaTransport(final ClusterSettings settings, final SSLContext tls) {
    this.settings = settings;
    auth = new PeerAuthentication(settings.key, settings.cluster);
    HttpClient.Builder b = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(3));
    if (tls != null) { b.sslContext(tls); }
    http = b.build();
  }

  public String stage(final String node, final BlobVersion version, final Path object) throws IOException {
    String retained = attemptStage(node, version, object, true);
    return retained != null ? retained : attemptStage(node, version, object, false);
  }

  private String attemptStage(final String node, final BlobVersion version, final Path object, final boolean retain) throws IOException {
    String path = (retain ? "/v1/retain/" : "/v1/stage/") + version.sequence;
    HttpRequest.Builder b = request(node, "POST", path, version.sha256)
        .timeout(Duration.ofSeconds(retain ? 3 : settings.transferTimeoutSeconds));
    HttpResponse<String> response = send(b.POST(retain || "HARD_DELETED".equals(version.state) ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofFile(object)).build(),
        HttpResponse.BodyHandlers.ofString());
    if (retain && response.statusCode() == 404) { return null; }
    if (response.statusCode() != 204) { throw new StageHttpException(response.statusCode()); }
    String stamp = response.headers().firstValue("X-Cluster-Ack").orElse("");
    String generation = response.headers().firstValue("X-Cluster-Generation").orElse("");
    if (!generation.matches("[a-f0-9-]{36}")) { throw new IOException("Invalid replica PVC generation"); }
    String expected = auth.sign(node, "ACK", path, Long.toString(version.epoch), Long.toString(version.sequence), version.sha256 + ":" + generation);
    if (!java.security.MessageDigest.isEqual(stamp.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
      throw new IOException("Replica acknowledgement signature mismatch");
    }
    return generation;
  }

  public InputStream download(final String node, final BlobVersion version) throws IOException {
    String path = "/v1/objects/" + version.sequence;
    HttpResponse<InputStream> response = send(request(node, "GET", path, version.sha256)
        .timeout(Duration.ofSeconds(settings.transferTimeoutSeconds)).GET().build(),
        HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      response.body().close(); throw new IOException("Replica object unavailable: HTTP " + response.statusCode());
    }
    // ofInputStream completes at headers, so its body needs a separate bounded transfer deadline.
    InputStream bytes = response.body();
    java.util.concurrent.ScheduledFuture<?> deadline = deadlines.schedule(() -> {
      try { bytes.close(); } catch (IOException ignored) {}
    }, settings.transferTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    return new java.io.FilterInputStream(bytes) {
      @Override public void close() throws IOException { deadline.cancel(false); super.close(); }
    }; // receiving DurableObjects validates the authoritative digest and length
  }

  public boolean probe(final String node) {
    try { return status(node) != null; }
    catch (IOException e) { return false; }
  }

  public PeerStatus status(final String node) throws IOException {
    try {
      String path = "/v1/status";
      HttpRequest request = request(node, "GET", path, emptyDigest()).GET().build();
      HttpResponse<String> r = send(request, HttpResponse.BodyHandlers.ofString());
      String digest = java.util.HexFormat.of().formatHex(DurableObjects.digest("SHA-256").digest(r.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      String expected = auth.sign(node, "REPLY", path, request.headers().firstValue("X-Cluster-Time").orElse(""),
          request.headers().firstValue("X-Cluster-Nonce").orElse(""), digest);
      if (r.statusCode() != 200 || !java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          r.headers().firstValue("X-Cluster-Reply").orElse("").getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
        throw new IOException("Invalid replica status authentication");
      }
      com.fasterxml.jackson.databind.JsonNode body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(r.body());
      String generation = body.path("generation").asText();
      if (!node.equals(body.path("node").asText()) || !settings.cluster.equals(body.path("cluster").asText())
          || !generation.matches("[a-f0-9-]{36}")) { throw new IOException("Invalid replica status identity"); }
      return new PeerStatus(body.path("caughtUp").asBoolean(false), generation,
          body.path("fence").path("epoch").asLong(), body.path("fence").path("owner").asText());
    }
    catch (RuntimeException e) { throw new IOException("Invalid replica status", e); }
  }

  public record PeerStatus(boolean caughtUp, String generation, long epoch, String owner) {}

  public static final class StageHttpException extends IOException {
    public final int status;
    StageHttpException(final int status) { super("Replica rejected staged blob"); this.status = status; }
  }

  public void fence(final String node, final long epoch, final String owner) throws IOException {
    byte[] bytes = ("{\"epoch\":" + epoch + ",\"owner\":\"" + owner + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String digest = java.util.HexFormat.of().formatHex(DurableObjects.digest("SHA-256").digest(bytes));
    HttpResponse<String> r = send(request(node, "POST", "/v1/fence", digest)
        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() != 204) { throw new IOException("Replica fence rejected: HTTP " + r.statusCode()); }
  }

  private HttpRequest.Builder request(final String node, final String method, final String path, final String digest) throws IOException {
    URI base = settings.members.get(node);
    if (base == null) { throw new IOException("Unconfigured replica member"); }
    HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3));
    for (Map.Entry<String, String> h : auth.headers(settings.node, method, path, digest).entrySet()) {
      b.header(h.getKey(), h.getValue());
    }
    return b;
  }

  private <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) throws IOException {
    try { return http.send(request, handler); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Replica transport interrupted", e); }
  }

  static String emptyDigest() {
    return java.util.HexFormat.of().formatHex(DurableObjects.digest("SHA-256").digest(new byte[0]));
  }
}

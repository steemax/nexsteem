/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.sonatype.nexus.ssl.TrustStore;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Trusted configured endpoints only; no redirects, bounded bodies and complete response timeout. */
@Component
public class OidcHttpClient {
  private final TrustStore trust;
  private final ObjectMapper json = new ObjectMapper();
  public OidcHttpClient(@Lazy TrustStore trust) { this.trust = trust; }
  public Map<String, Object> get(OidcConfiguration config, String url) throws Exception {
    return exchange(config, HttpRequest.newBuilder(URI.create(url)).GET());
  }
  public Map<String, Object> post(OidcConfiguration config, String url, Map<String, String> form) throws Exception {
    return exchange(config, HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(encode(form))));
  }
  private Map<String, Object> exchange(OidcConfiguration config, HttpRequest.Builder request) throws Exception {
    HttpClient.Builder client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
    if (config.useTrustStore) { client.sslContext(trust.getSSLContext()); }
    try (HttpClient http = client.build()) {
      var pending = http.sendAsync(request.timeout(Duration.ofSeconds(10)).header("Accept", "application/json").build(),
          info -> new LimitedBody());
      try {
        HttpResponse<byte[]> response = pending.get(10, TimeUnit.SECONDS);
        if (response.statusCode() != 200) { throw new SecurityException("OIDC provider rejected the request"); }
        return json.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
      }
      finally { pending.cancel(true); }
    }
  }
  public static String encode(Map<String, String> values) {
    return values.entrySet().stream().map(e -> escape(e.getKey()) + "=" + escape(e.getValue())).collect(Collectors.joining("&"));
  }
  private static String escape(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
  private static class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> complete = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;
    public CompletionStage<byte[]> getBody() { return complete; }
    public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > 1024 * 1024 - bytes.size()) {
          subscription.cancel(); complete.completeExceptionally(new SecurityException("OIDC response exceeds the size limit")); return;
        }
        byte[] part = new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part);
      }
      subscription.request(1);
    }
    public void onError(Throwable failure) { complete.completeExceptionally(failure); }
    public void onComplete() { complete.complete(bytes.toByteArray()); }
  }
}

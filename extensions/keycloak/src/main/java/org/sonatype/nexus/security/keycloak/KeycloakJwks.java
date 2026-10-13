/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import com.nimbusds.jose.jwk.JWKSet;

/** Bounded JWKS cache; limited refresh rate, no redirects or stale keys after expiry. */
public final class KeycloakJwks implements KeycloakTokenVerifier.Keys
{
  private final URI endpoint;
  private final HttpClient http;
  private final Clock clock;
  private JWKSet cached;
  private Instant expiresAt = Instant.MIN;
  private Instant retryAt = Instant.MIN;

  public KeycloakJwks(URI issuer, boolean allowHttp, Clock clock) {
    if (issuer.getHost() == null || issuer.getUserInfo() != null || issuer.getQuery() != null
        || issuer.getFragment() != null
        || !("https".equals(issuer.getScheme()) || (allowHttp && "http".equals(issuer.getScheme())))) {
      throw new IllegalArgumentException("HTTPS issuer is required; HTTP is for local tests only");
    }
    this.endpoint = URI.create(issuer.toString().replaceAll("/$", "") + "/protocol/openid-connect/certs");
    this.clock = clock;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override
  public synchronized JWKSet get(String keyId) throws Exception {
    Instant now = clock.instant();
    boolean known = cached != null && cached.getKeyByKeyId(keyId) != null;
    if (!now.isBefore(expiresAt) || !known) {
      if (!now.isBefore(retryAt)) {
        retryAt = now.plusSeconds(10);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).GET().build();
        var pending = http.sendAsync(request, info -> new LimitedBody());
        try {
          HttpResponse<byte[]> response = pending.get(5, TimeUnit.SECONDS);
          if (response.statusCode() != 200) {
            throw new SecurityException("JWKS endpoint unavailable");
          }
          cached = JWKSet.parse(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8));
          expiresAt = now.plusSeconds(300);
        }
        finally {
          pending.cancel(true);
        }
      }
    }
    if (cached == null || !now.isBefore(expiresAt) || cached.getKeyByKeyId(keyId) == null) {
      throw new SecurityException("No current signing key");
    }
    return cached;
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() { return completed; }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      subscription.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.remaining() > 1024 * 1024 - bytes.size()) {
          subscription.cancel();
          completed.completeExceptionally(new SecurityException("JWKS response too large"));
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
      subscription.request(1);
    }

    @Override
    public void onError(Throwable error) { completed.completeExceptionally(error); }

    @Override
    public void onComplete() { completed.complete(bytes.toByteArray()); }
  }
}

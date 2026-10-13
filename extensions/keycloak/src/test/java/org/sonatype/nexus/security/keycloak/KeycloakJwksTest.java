/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

public class KeycloakJwksTest {
  private HttpServer server;
  private KeycloakJwks jwks;
  private final AtomicInteger requests = new AtomicInteger();
  private String body;
  private int status;
  private MutableClock clock;

  @Before
  public void setup() throws Exception {
    body = new JWKSet(new RSAKeyGenerator(2048).keyID("one").generate().toPublicJWK()).toString();
    status = 200;
    clock = new MutableClock();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/realms/test/protocol/openid-connect/certs", exchange -> {
      requests.incrementAndGet();
      byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, bytes.length);
      try (var output = exchange.getResponseBody()) { output.write(bytes); }
    });
    server.start();
    URI issuer = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/realms/test");
    jwks = new KeycloakJwks(issuer, true, clock);
  }

  @After
  public void stop() { server.stop(0); }

  @Test
  public void cachesValidKeys() throws Exception {
    assertNotNull(jwks.get("one").getKeyByKeyId("one"));
    assertNotNull(jwks.get("one").getKeyByKeyId("one"));
    assertEquals(1, requests.get());
  }

  @Test
  public void rateLimitsUnknownKeyRequests() throws Exception {
    jwks.get("one");
    for (int i = 0; i < 20; i++) {
      assertThrows(SecurityException.class, () -> jwks.get("unknown"));
    }
    assertEquals(1, requests.get());
  }

  @Test
  public void refreshesRotatedKey() throws Exception {
    jwks.get("one");
    body = new JWKSet(new RSAKeyGenerator(2048).keyID("two").generate().toPublicJWK()).toString();
    clock.now = clock.now.plusSeconds(11);
    assertNotNull(jwks.get("two").getKeyByKeyId("two"));
    assertEquals(2, requests.get());
  }

  @Test
  public void rejectsExpiredCacheOnEndpointFailure() throws Exception {
    jwks.get("one");
    status = 503;
    clock.now = clock.now.plusSeconds(301);
    assertThrows(SecurityException.class, () -> jwks.get("one"));
    assertThrows(SecurityException.class, () -> jwks.get("one"));
    assertEquals(2, requests.get());
  }

  @Test
  public void rejectsOversizedResponse() {
    body = "x".repeat(1024 * 1024 + 1);
    assertThrows(Exception.class, () -> jwks.get("one"));
  }

  @Test
  public void requiresHttpsOutsideLocalTesting() {
    assertThrows(IllegalArgumentException.class, () -> new KeycloakJwks(URI.create("http://example/realms/test"), false, clock));
    assertThrows(IllegalArgumentException.class, () -> new KeycloakJwks(URI.create("https://u:p@example/realms/test"), false, clock));
  }

  private static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-11T00:00:00Z");
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}

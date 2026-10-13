/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class KeycloakTokenVerifierTest
{
  private static final String ISSUER = "https://keycloak.example/realms/nexus";
  private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");
  private RSAKey key;
  private KeycloakTokenVerifier verifier;

  @Before
  public void setup() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test").generate();
    verifier = verifier(key);
  }

  private KeycloakTokenVerifier verifier(RSAKey signingKey) {
    return new KeycloakTokenVerifier(ISSUER, "nexus", "nexus",
        Map.of("reader", "nx-anonymous"), kid -> new JWKSet(signingKey.toPublicJWK()),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder().issuer(ISSUER).subject("immutable-subject").audience("nexus")
        .issueTime(Date.from(NOW.minusSeconds(30))).expirationTime(Date.from(NOW.plusSeconds(60)))
        .claim("typ", "Bearer").claim("preferred_username", "admin")
        .claim("resource_access", Map.of("nexus", Map.of("roles", List.of("reader", "nx-admin"))));
  }

  private String token(JWTClaimsSet.Builder claims) throws Exception {
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID("test").type(JOSEObjectType.JWT).build(), claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  @Test
  public void acceptsValidAccessTokenAndOnlyMappedClientRoles() throws Exception {
    KeycloakIdentity identity = verifier.verify(token(claims()));
    assertEquals("keycloak:immutable-subject", identity.subject());
    assertEquals(Set.of("nx-anonymous"), identity.nexusRoles());
  }

  @Test
  public void rejectsWrongSignature() throws Exception {
    KeycloakTokenVerifier other = verifier(new RSAKeyGenerator(2048).keyID("test").generate());
    assertThrows(SecurityException.class, () -> other.verify(token(claims())));
  }

  @Test
  public void rejectsWrongIssuer() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims().issuer("https://evil.example"))));
  }

  @Test
  public void rejectsWrongAudience() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims().audience("account"))));
  }

  @Test
  public void rejectsExpiredToken() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims().expirationTime(Date.from(NOW)))));
  }

  @Test
  public void rejectsMissingExpiry() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims().expirationTime(null))));
  }

  @Test
  public void rejectsNotYetValidToken() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims().notBeforeTime(Date.from(NOW.plusSeconds(1))))));
  }

  @Test
  public void rejectsIdToken() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims().claim("typ", "ID"))));
  }

  @Test
  public void rejectsUnmappedRoles() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims()
        .claim("resource_access", Map.of("nexus", Map.of("roles", List.of("nx-admin")))))));
  }

  @Test
  public void ignoresOtherClientsRoles() {
    assertThrows(SecurityException.class, () -> verifier.verify(token(claims()
        .claim("resource_access", Map.of("other-client", Map.of("roles", List.of("reader")))))));
  }

  @Test
  public void rejectsUnknownKey() throws Exception {
    KeycloakTokenVerifier unknown = new KeycloakTokenVerifier(ISSUER, "nexus", "nexus",
        Map.of("reader", "nx-anonymous"), kid -> new JWKSet(), Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(SecurityException.class, () -> unknown.verify(token(claims())));
  }

  @Test
  public void rejectsDuplicateKeyIds() {
    KeycloakTokenVerifier duplicate = new KeycloakTokenVerifier(ISSUER, "nexus", "nexus",
        Map.of("reader", "nx-anonymous"), kid -> new JWKSet(List.of(key.toPublicJWK(), key.toPublicJWK())),
        Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(SecurityException.class, () -> duplicate.verify(token(claims())));
  }

  @Test
  public void failsWhenKeycloakKeysAreUnavailable() {
    KeycloakTokenVerifier offline = new KeycloakTokenVerifier(ISSUER, "nexus", "nexus",
        Map.of("reader", "nx-anonymous"), kid -> { throw new SecurityException("offline"); },
        Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(SecurityException.class, () -> offline.verify(token(claims())));
  }
}

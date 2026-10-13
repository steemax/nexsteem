/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Keycloak access tokens only; configured issuer/JWKS and explicit role allowlist. */
public final class KeycloakTokenVerifier
{
  @FunctionalInterface
  public interface Keys {
    JWKSet get(String keyId) throws Exception;
  }

  private final String issuer;
  private final String audience;
  private final String client;
  private final Map<String, String> roleMapping;
  private final Keys keys;
  private final Clock clock;

  public KeycloakTokenVerifier(String issuer, String audience, String client,
      Map<String, String> roleMapping, Keys keys, Clock clock)
  {
    if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
        || client == null || client.isBlank() || roleMapping.isEmpty()) {
      throw new IllegalArgumentException("Issuer, audience, client and role mapping are required");
    }
    this.issuer = issuer;
    this.audience = audience;
    this.client = client;
    this.roleMapping = Map.copyOf(roleMapping);
    this.keys = keys;
    this.clock = clock;
  }

  public KeycloakIdentity verify(String token) throws Exception {
    if (token == null || token.isBlank() || token.length() > 16384) {
      throw new SecurityException("Invalid access token");
    }
    SignedJWT jwt = SignedJWT.parse(token);
    if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
        || jwt.getHeader().getKeyID() == null
        || jwt.getHeader().getCriticalParams() != null) {
      throw new SecurityException("Unsupported token header");
    }
    // Never read a URL, JWK or x5c key from an untrusted token header.
    List<JWK> matching = keys.get(jwt.getHeader().getKeyID()).getKeys().stream()
        .filter(key -> jwt.getHeader().getKeyID().equals(key.getKeyID())).toList();
    if (matching.size() != 1 || !(matching.getFirst() instanceof RSAKey key)
        || key.isPrivate() || (key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse()))
        || (key.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(key.getAlgorithm()))
        || key.toRSAPublicKey().getModulus().bitLength() < 2048
        || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
      throw new SecurityException("Access token signature rejected");
    }
    JWTClaimsSet claims = jwt.getJWTClaimsSet();
    Instant now = clock.instant();
    if (!issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(audience)
        || claims.getSubject() == null || claims.getSubject().isBlank()
        || !"Bearer".equals(claims.getStringClaim("typ"))
        || claims.getExpirationTime() == null || !now.isBefore(claims.getExpirationTime().toInstant())
        || claims.getIssueTime() == null || claims.getIssueTime().toInstant().isAfter(now.plusSeconds(30))
        || (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now))) {
      throw new SecurityException("Access token claims rejected");
    }
    Set<String> granted = new HashSet<>();
    Map<String, Object> access = claims.getJSONObjectClaim("resource_access");
    if (access != null && access.get(client) instanceof Map<?, ?> clientAccess
        && clientAccess.get("roles") instanceof List<?> roles) {
      for (Object role : roles) {
        if (role instanceof String name && roleMapping.containsKey(name)) {
          granted.add(roleMapping.get(name));
        }
      }
    }
    if (granted.isEmpty()) {
      throw new SecurityException("No allowed Nexus roles");
    }
    // Stable subject avoids collisions caused by mutable usernames or emails.
    return new KeycloakIdentity("keycloak:" + claims.getSubject(),
        claims.getExpirationTime().toInstant(), granted);
  }
}

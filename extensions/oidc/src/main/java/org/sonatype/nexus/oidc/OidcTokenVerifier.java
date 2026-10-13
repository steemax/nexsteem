/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** OIDC ID-token validation is separate from access-token validation, including nonce and at_hash. */
public class OidcTokenVerifier {
  @FunctionalInterface public interface Keys { JWKSet get(String keyId) throws Exception; }
  public record Verified(String userId, String subject, String firstName, String lastName, String email,
      Set<String> groups, Set<String> roles, Instant expiresAt) implements java.io.Serializable { }
  private final OidcConfiguration config;
  private final Keys keys;
  private final Clock clock;
  public OidcTokenVerifier(OidcConfiguration config, Keys keys, Clock clock) { this.config = config; this.keys = keys; this.clock = clock; }
  public Verified login(String idToken, String accessToken, String nonce) throws Exception {
    JWTClaimsSet id = signed(idToken, config.clientId);
    JWTClaimsSet access = signed(accessToken, config.audience);
    if (!id.getSubject().equals(access.getSubject()) || nonce == null || !constantEqual(nonce, id.getStringClaim("nonce"))) {
      throw new SecurityException("OIDC identity or nonce rejected");
    }
    if (id.getAudience().size() > 1 && !config.clientId.equals(id.getStringClaim("azp")) ||
        id.getClaim("azp") != null && !config.clientId.equals(id.getStringClaim("azp"))) {
      throw new SecurityException("OIDC authorized party rejected");
    }
    String hash = id.getStringClaim("at_hash");
    if (hash != null) {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
      String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, digest.length / 2));
      if (!constantEqual(hash, expected)) { throw new SecurityException("OIDC access token hash rejected"); }
    }
    for (var expected : config.exactMatchClaims.entrySet()) {
      Object actual = claim(id.getClaims(), expected.getKey());
      if (!java.util.Objects.equals(expected.getValue(), actual)) { throw new SecurityException("Required OIDC claim rejected"); }
    }
    return profile(id, access);
  }
  public Verified refresh(String accessToken, String subject, Verified previous) throws Exception {
    JWTClaimsSet access = signed(accessToken, config.audience);
    if (!subject.equals(access.getSubject())) { throw new SecurityException("OIDC subject changed during refresh"); }
    Set<String> groups = groupNames(access.getClaims());
    return new Verified(previous.userId(), subject, previous.firstName(), previous.lastName(), previous.email(),
        groups, mappedRoles(groups), access.getExpirationTime().toInstant());
  }
  public void refreshId(String idToken, String accessToken, String subject, String nonce) throws Exception {
    JWTClaimsSet id = signed(idToken, config.clientId);
    if (!subject.equals(id.getSubject()) || id.getClaim("nonce") != null && (nonce == null || !constantEqual(nonce, id.getStringClaim("nonce")))) {
      throw new SecurityException("OIDC refresh identity or nonce rejected");
    }
    if (id.getAudience().size() > 1 && !config.clientId.equals(id.getStringClaim("azp"))) { throw new SecurityException("OIDC refresh authorized party rejected"); }
    String hash = id.getStringClaim("at_hash");
    if (hash != null) {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.US_ASCII));
      String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, digest.length / 2));
      if (!constantEqual(hash, expected)) { throw new SecurityException("OIDC refresh access token hash rejected"); }
    }
    for (var expected : config.exactMatchClaims.entrySet()) {
      if (!java.util.Objects.equals(expected.getValue(), claim(id.getClaims(), expected.getKey()))) { throw new SecurityException("Required OIDC refresh claim rejected"); }
    }
  }
  public Verified bearer(String accessToken) throws Exception {
    JWTClaimsSet access = signed(accessToken, config.audience);
    return profile(access, access);
  }
  private Verified profile(JWTClaimsSet id, JWTClaimsSet access) {
    String user = text(id.getClaims(), config.usernameClaim);
    if (user == null || user.isBlank() || user.length() > 255) { throw new SecurityException("OIDC username claim missing"); }
    Set<String> groups = groupNames(access.getClaims());
    if (groups.isEmpty()) { groups = groupNames(id.getClaims()); }
    return new Verified(user, id.getSubject(), text(id.getClaims(), config.firstNameClaim), text(id.getClaims(), config.lastNameClaim),
        text(id.getClaims(), config.emailClaim), groups, mappedRoles(groups), access.getExpirationTime().toInstant());
  }
  private JWTClaimsSet signed(String text, String audience) throws Exception {
    if (text == null || text.isBlank() || text.length() > 32768) { throw new SecurityException("Invalid OIDC token size"); }
    SignedJWT token = SignedJWT.parse(text);
    String id = token.getHeader().getKeyID();
    if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm()) || id == null || token.getHeader().getCriticalParams() != null) {
      throw new SecurityException("OIDC token header rejected");
    }
    List<JWK> matching = keys.get(id).getKeys().stream().filter(key -> id.equals(key.getKeyID())).toList();
    if (matching.size() != 1 || !(matching.getFirst() instanceof RSAKey key) || key.isPrivate() ||
        key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse()) ||
        key.getAlgorithm() != null && !JWSAlgorithm.RS256.equals(key.getAlgorithm()) ||
        key.toRSAPublicKey().getModulus().bitLength() < 2048 || !token.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
      throw new SecurityException("OIDC token signature rejected");
    }
    JWTClaimsSet claims = token.getJWTClaimsSet(); Instant now = clock.instant();
    if (!config.issuer.equals(claims.getIssuer()) || !claims.getAudience().contains(audience) ||
        claims.getSubject() == null || claims.getSubject().isBlank() || claims.getExpirationTime() == null ||
        !now.isBefore(claims.getExpirationTime().toInstant()) || claims.getIssueTime() == null ||
        claims.getIssueTime().toInstant().isAfter(now.plusSeconds(30)) ||
        claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now) ||
        claims.getClaim("azp") != null && !config.clientId.equals(claims.getStringClaim("azp"))) {
      throw new SecurityException("OIDC token claims rejected");
    }
    return claims;
  }
  private Set<String> groupNames(Map<String, Object> values) {
    Object claim = claim(values, config.groupsClaim); Set<String> result = new LinkedHashSet<>();
    if (claim instanceof Collection<?> groups) {
      for (Object group : groups) { if (group instanceof String name && name.length() <= 255) { result.add(name); } }
    }
    else if (claim instanceof String value && !value.isBlank() && value.length() <= 255) { result.add(value); }
    return Set.copyOf(result);
  }
  private Set<String> mappedRoles(Set<String> groups) {
    Set<String> roles = new LinkedHashSet<>();
    for (String group : groups) { if (config.roleMapping.containsKey(group)) { roles.add(config.roleMapping.get(group)); } }
    return Set.copyOf(roles);
  }
  private static Object claim(Map<String, Object> values, String path) {
    if (values.containsKey(path)) { return values.get(path); }
    Object value = values;
    for (String key : path.split("\\.")) { if (!(value instanceof Map<?, ?> nested)) { return null; } value = nested.get(key); }
    return value;
  }
  private static String text(Map<String, Object> values, String path) { Object value = claim(values, path); return value instanceof String text ? text : null; }
  private static boolean constantEqual(String expected, String actual) {
    return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }
}

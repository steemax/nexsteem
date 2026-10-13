/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.security.KeyPairGenerator;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class OidcTokenVerifierTest {
  private RSAKey key;
  private OidcConfiguration config;
  private OidcTokenVerifier verifier;
  private final Instant now = Instant.parse("2026-10-11T00:00:00Z");
  @Before public void setup() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
    key = new RSAKey.Builder((java.security.interfaces.RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate())
        .keyID("test").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
    config = new OidcConfiguration(); config.clientId = "nexus"; config.audience = "nexus";
    config.issuer = "https://idp.example.org/realms/nexus"; config.groupsClaim = "resource_access.nexus.roles";
    config.roleMapping = Map.of("reader", "nx-anonymous");
    verifier = new OidcTokenVerifier(config, ignored -> new JWKSet(key.toPublicJWK()), Clock.fixed(now, ZoneOffset.UTC));
  }
  private JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder().issuer(config.issuer).audience("nexus").subject("subject-1")
        .issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(60)))
        .claim("azp", "nexus").claim("nonce", "expected-nonce").claim("given_name", "Test").claim("family_name", "Reader")
        .claim("email", "reader@example.org").claim("resource_access", Map.of("nexus", Map.of("roles", List.of("reader", "unmapped"))));
  }
  private String token(JWTClaimsSet.Builder claims) throws Exception { return token(claims, key); }
  private String token(JWTClaimsSet.Builder claims, RSAKey signing) throws Exception {
    SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims.build());
    jwt.sign(new RSASSASigner(signing)); return jwt.serialize();
  }
  @Test public void validIdAndAccessTokensMapOnlyExplicitRoles() throws Exception {
    var result = verifier.login(token(claims()), token(claims()), "expected-nonce");
    assertEquals("subject-1", result.userId()); assertEquals("reader@example.org", result.email());
    assertEquals(Set.of("nx-anonymous"), result.roles()); assertEquals(Set.of("reader", "unmapped"), result.groups());
  }
  @Test(expected = SecurityException.class) public void wrongNonce() throws Exception { verifier.login(token(claims()), token(claims()), "wrong"); }
  @Test(expected = SecurityException.class) public void missingNonce() throws Exception { verifier.login(token(claims().claim("nonce", null)), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void wrongIssuer() throws Exception { verifier.login(token(claims().issuer("https://evil.example.org")), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void wrongIdAudience() throws Exception { verifier.login(token(claims().audience("other")), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void wrongAccessAudience() throws Exception { verifier.login(token(claims()), token(claims().audience("other")), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void differentSubjects() throws Exception { verifier.login(token(claims()), token(claims().subject("other")), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void wrongAuthorizedParty() throws Exception { verifier.login(token(claims().claim("azp", "other")), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void multipleIdAudiencesRequireAzp() throws Exception { verifier.login(token(claims().audience(List.of("nexus", "other")).claim("azp", null)), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void expiredIdToken() throws Exception { verifier.login(token(claims().expirationTime(Date.from(now))), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void expiredAccessToken() throws Exception { verifier.login(token(claims()), token(claims().expirationTime(Date.from(now))), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void futureIssuedAt() throws Exception { verifier.login(token(claims().issueTime(Date.from(now.plusSeconds(31)))), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void futureNotBefore() throws Exception { verifier.login(token(claims().notBeforeTime(Date.from(now.plusSeconds(1)))), token(claims()), "expected-nonce"); }
  @Test(expected = SecurityException.class) public void invalidAtHash() throws Exception { verifier.login(token(claims().claim("at_hash", "wrong")), token(claims()), "expected-nonce"); }
  @Test public void refreshedIdTokenMayOmitNonce() throws Exception { verifier.refreshId(token(claims().claim("nonce", null)), token(claims()), "subject-1", "expected-nonce"); }
  @Test(expected = SecurityException.class) public void refreshedIdRejectsChangedNonce() throws Exception { verifier.refreshId(token(claims().claim("nonce", "other")), token(claims()), "subject-1", "expected-nonce"); }
  @Test(expected = SecurityException.class) public void refreshedIdRejectsChangedSubject() throws Exception { verifier.refreshId(token(claims().subject("other")), token(claims()), "subject-1", "expected-nonce"); }
  @Test(expected = SecurityException.class) public void refreshedIdRejectsWrongAudience() throws Exception { verifier.refreshId(token(claims().audience("other")), token(claims()), "subject-1", "expected-nonce"); }
  @Test(expected = SecurityException.class) public void refreshedIdRejectsWrongAtHash() throws Exception { verifier.refreshId(token(claims().claim("at_hash", "wrong")), token(claims()), "subject-1", "expected-nonce"); }
  @Test public void validAtHash() throws Exception {
    String access = token(claims()); byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(access.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    String half = Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, hash.length / 2));
    assertEquals("subject-1", verifier.login(token(claims().claim("at_hash", half)), access, "expected-nonce").subject());
  }
  @Test(expected = SecurityException.class) public void rejectsOtherSigningKey() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair = generator.generateKeyPair();
    var other = new RSAKey.Builder((java.security.interfaces.RSAPublicKey)pair.getPublic()).privateKey(pair.getPrivate()).build();
    verifier.login(token(claims(), other), token(claims()), "expected-nonce");
  }
  @Test(expected = SecurityException.class) public void duplicateKeyIdRejected() throws Exception {
    verifier = new OidcTokenVerifier(config, ignored -> new JWKSet(List.of(key.toPublicJWK(), key.toPublicJWK())), Clock.fixed(now, ZoneOffset.UTC));
    verifier.login(token(claims()), token(claims()), "expected-nonce");
  }
  @Test(expected = SecurityException.class) public void exactClaimRequirement() throws Exception {
    config.exactMatchClaims = Map.of("email_verified", true); verifier.login(token(claims()), token(claims()), "expected-nonce");
  }
  @Test public void unmappedGroupsDoNotGrantRoles() throws Exception {
    config.roleMapping = Map.of(); assertTrue(verifier.login(token(claims()), token(claims()), "expected-nonce").roles().isEmpty());
  }
  @Test public void refreshUpdatesRolesWithoutRequiringNonce() throws Exception {
    var previous = verifier.login(token(claims()), token(claims()), "expected-nonce");
    var result = verifier.refresh(token(claims().claim("nonce", null).claim("resource_access", Map.of())), "subject-1", previous);
    assertTrue(result.roles().isEmpty()); assertEquals(previous.userId(), result.userId());
  }
  @Test(expected = SecurityException.class) public void refreshCannotChangeSubject() throws Exception {
    var previous = verifier.login(token(claims()), token(claims()), "expected-nonce");
    verifier.refresh(token(claims().subject("other")), "subject-1", previous);
  }
  @Test public void bearerValidatesAndMapsRoles() throws Exception { assertEquals(Set.of("nx-anonymous"), verifier.bearer(token(claims())).roles()); }
}

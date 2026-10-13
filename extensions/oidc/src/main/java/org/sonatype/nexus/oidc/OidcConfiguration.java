/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/** Compatible with the public Nexus OAuth form, with explicit issuer and role mappings. */
public class OidcConfiguration {
  public String clientId = "", clientSecret = "";
  public String idpAuthorizationUrl = "", idpLogoutUrl = "", idpTokenUrl = "", idpJwksUrl = "";
  public String issuer = "", audience = "", publicUrl = "";
  public String idpJwsAlgorithm = "RS256", idpJwks = "";
  public String usernameClaim = "sub", firstNameClaim = "given_name", lastNameClaim = "family_name";
  public String emailClaim = "email", groupsClaim = "groups";
  public boolean useTrustStore;
  public Map<String, Object> exactMatchClaims = Map.of();
  public Map<String, String> authorizationCustomParams = Map.of(), tokenRequestCustomParams = Map.of();
  public Map<String, String> roleMapping = Map.of();

  public void validate(boolean allowHttp, String defaultPublicUrl) {
    if (clientId == null || clientId.isBlank() || clientId.length() > 255) { throw new IllegalArgumentException("Client ID is required"); }
    url(idpAuthorizationUrl, allowHttp); url(idpLogoutUrl, allowHttp); url(idpTokenUrl, allowHttp); url(idpJwksUrl, allowHttp);
    if (issuer == null || issuer.isBlank()) {
      String suffix = "/protocol/openid-connect/auth";
      if (idpAuthorizationUrl.endsWith(suffix)) { issuer = idpAuthorizationUrl.substring(0, idpAuthorizationUrl.length() - suffix.length()); }
      else if (exactMatchClaims != null && exactMatchClaims.get("iss") instanceof String value) { issuer = value; }
      else { throw new IllegalArgumentException("Issuer URL is required"); }
    }
    url(issuer, allowHttp);
    if (audience == null || audience.isBlank()) { audience = clientId; }
    if (publicUrl == null || publicUrl.isBlank()) { publicUrl = defaultPublicUrl; }
    url(publicUrl, allowHttp);
    while (publicUrl.endsWith("/")) { publicUrl = publicUrl.substring(0, publicUrl.length() - 1); }
    if (!"RS256".equals(idpJwsAlgorithm)) { throw new IllegalArgumentException("The open OIDC provider requires RS256 signatures"); }
    for (String claim : new String[]{usernameClaim, firstNameClaim, lastNameClaim, emailClaim, groupsClaim}) {
      if (claim == null || !claim.matches("[A-Za-z0-9_.-]{1,128}")) { throw new IllegalArgumentException("Invalid OIDC claim mapping"); }
    }
    if (roleMapping == null || roleMapping.size() > 100 || roleMapping.entrySet().stream().anyMatch(e ->
        !e.getKey().matches("[A-Za-z0-9_./:-]{1,255}") || !e.getValue().matches("[A-Za-z0-9_.:-]{1,255}"))) {
      throw new IllegalArgumentException("Invalid group to Nexus role mappings");
    }
    checkParams(authorizationCustomParams, Set.of("client_id", "response_type", "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method"));
    checkParams(tokenRequestCustomParams, Set.of("client_id", "client_secret", "grant_type", "code", "redirect_uri", "code_verifier", "refresh_token"));
    if (exactMatchClaims == null || exactMatchClaims.size() > 100) { throw new IllegalArgumentException("Invalid required claims"); }
    if (idpJwks != null && !idpJwks.isBlank()) {
      try {
        if (idpJwks.length() > 1024 * 1024) { throw new IllegalArgumentException(); }
        var keys = com.nimbusds.jose.jwk.JWKSet.parse(idpJwks).getKeys();
        if (keys.isEmpty() || keys.size() > 100) { throw new IllegalArgumentException(); }
        for (var key : keys) {
          if (!(key instanceof com.nimbusds.jose.jwk.RSAKey rsa) || key.isPrivate() || key.getKeyID() == null ||
              rsa.toRSAPublicKey().getModulus().bitLength() < 2048) { throw new IllegalArgumentException(); }
        }
      }
      catch (Exception invalid) { throw new IllegalArgumentException("Inline JWKS must contain public RSA signing keys of at least 2048 bits with key IDs"); }
    }
  }
  public String callbackUrl() { return publicUrl + "/oidc/callback"; }
  public boolean configured() { return clientId != null && !clientId.isBlank() && idpAuthorizationUrl != null && !idpAuthorizationUrl.isBlank(); }
  private static void checkParams(Map<String, String> values, Set<String> reserved) {
    if (values == null || values.size() > 30 || values.entrySet().stream().anyMatch(e -> reserved.contains(e.getKey()) ||
        !e.getKey().matches("[A-Za-z0-9_.-]{1,100}") || e.getValue() == null || e.getValue().length() > 2048)) {
      throw new IllegalArgumentException("Custom parameters cannot override OIDC security parameters");
    }
  }
  private static void url(String value, boolean allowHttp) {
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
          !("https".equals(uri.getScheme()) || allowHttp && "http".equals(uri.getScheme()))) {
        throw new IllegalArgumentException();
      }
    }
    catch (Exception failure) { throw new IllegalArgumentException("OIDC URLs must use HTTPS and have no credentials, query or fragment"); }
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import org.springframework.stereotype.Component;

@Component
public class OidcClient {
  private final OidcConfigurationStore config;
  private final OidcPersistentStore store;
  private final OidcHttpClient http;
  private String keysRevision;
  private JWKSet keys;
  private Instant keysExpires = Instant.MIN, keysRetry = Instant.MIN;
  public OidcClient(OidcConfigurationStore config, OidcPersistentStore store, OidcHttpClient http) {
    this.config = config; this.store = store; this.http = http;
  }
  private synchronized JWKSet keys(OidcConfiguration settings, String keyId) throws Exception {
    Instant now = Instant.now(); String revision = config.revision();
    if (!revision.equals(keysRevision)) { keys = null; keysRevision = revision; keysExpires = Instant.MIN; keysRetry = Instant.MIN; }
    if (keys == null || !now.isBefore(keysExpires) || keys.getKeyByKeyId(keyId) == null) {
      if (!now.isBefore(keysRetry)) {
        keysRetry = now.plusSeconds(10);
        keys = settings.idpJwks != null && !settings.idpJwks.isBlank() ? JWKSet.parse(settings.idpJwks) :
            JWKSet.parse(new ObjectMapper().writeValueAsString(http.get(settings, settings.idpJwksUrl)));
        keysExpires = now.plusSeconds(300);
      }
    }
    if (keys == null || !now.isBefore(keysExpires) || keys.getKeyByKeyId(keyId) == null) { throw new SecurityException("No current OIDC signing key"); }
    return keys;
  }
  private OidcTokenVerifier verifier(OidcConfiguration settings) { return new OidcTokenVerifier(settings, id -> keys(settings, id), Clock.systemUTC()); }
  public String complete(OidcPersistentStore.PendingLogin login, String code) throws Exception {
    OidcConfiguration settings = config.get();
    if (!login.revision.equals(config.revision())) { throw new SecurityException("OIDC configuration changed during login"); }
    Map<String, String> parameters = new LinkedHashMap<>(settings.tokenRequestCustomParams);
    parameters.put("grant_type", "authorization_code"); parameters.put("code", code);
    parameters.put("redirect_uri", settings.callbackUrl()); parameters.put("code_verifier", store.decrypt(login.verifierSecret));
    parameters.put("client_id", settings.clientId); parameters.put("client_secret", config.secret());
    Map<String, Object> response = http.post(settings, settings.idpTokenUrl, parameters);
    String access = token(response, "access_token", true), id = token(response, "id_token", true), refresh = token(response, "refresh_token", false);
    requireBearer(response);
    OidcTokenVerifier.Verified identity = verifier(settings).login(id, access, login.nonce);
    if (!login.revision.equals(config.revision())) { throw new SecurityException("OIDC configuration changed during login"); }
    store.bindProfile(settings.issuer, identity);
    OidcPersistentStore.BrowserSession session = new OidcPersistentStore.BrowserSession();
    session.identity = identity; session.revision = login.revision; session.nonce = login.nonce; session.expiresAt = Instant.now().plusSeconds(28800).getEpochSecond();
    try {
      session.accessSecret = store.encrypt(access); session.refreshSecret = store.encrypt(refresh); session.idSecret = store.encrypt(id);
      return store.createSession(session);
    }
    catch (RuntimeException failed) { store.destroySecrets(session); throw failed; }
  }
  public synchronized OidcTokenVerifier.Verified session(String id) throws Exception {
    OidcPersistentStore.BrowserSession current = store.session(id); OidcConfiguration settings = config.get();
    if (!current.revision.equals(config.revision())) { store.revoke(id); throw new SecurityException("OIDC configuration changed"); }
    if (!Instant.now().plusSeconds(15).isBefore(current.identity.expiresAt())) {
      if (current.refreshSecret == null) { store.revoke(id); throw new SecurityException("OIDC session cannot be refreshed"); }
      OidcPersistentStore.BrowserSession updated = null;
      try {
        Map<String, String> parameters = new LinkedHashMap<>(settings.tokenRequestCustomParams);
        parameters.put("grant_type", "refresh_token"); parameters.put("refresh_token", store.decrypt(current.refreshSecret));
        parameters.put("client_id", settings.clientId); parameters.put("client_secret", config.secret());
        Map<String, Object> response = http.post(settings, settings.idpTokenUrl, parameters); requireBearer(response);
        String access = token(response, "access_token", true);
        OidcTokenVerifier.Verified identity = verifier(settings).refresh(access, current.identity.subject(), current.identity);
        store.bindProfile(settings.issuer, identity);
        updated = new OidcPersistentStore.BrowserSession();
        updated.revision = current.revision; updated.nonce = current.nonce; updated.expiresAt = current.expiresAt; updated.identity = identity;
        updated.accessSecret = store.encrypt(access);
        String refresh = token(response, "refresh_token", false), idToken = token(response, "id_token", false);
        if (idToken != null) { verifier(settings).refreshId(idToken, access, current.identity.subject(), current.nonce); }
        updated.refreshSecret = store.encrypt(refresh == null ? store.decrypt(current.refreshSecret) : refresh);
        updated.idSecret = store.encrypt(idToken == null ? store.decrypt(current.idSecret) : idToken);
        if (!current.revision.equals(config.revision())) { throw new SecurityException("OIDC configuration changed during refresh"); }
        store.updateSession(id, updated, current); current = updated;
      }
      catch (Exception rejected) {
        if (updated != null) { store.destroySecrets(updated); }
        store.revoke(id); throw new SecurityException("OIDC token refresh rejected");
      }
    }
    if (!Instant.now().isBefore(current.identity.expiresAt())) { store.revoke(id); throw new SecurityException("OIDC access token expired"); }
    return current.identity;
  }
  public OidcTokenVerifier.Verified bearer(String token) throws Exception {
    OidcConfiguration settings = config.get(); var verified = verifier(settings).bearer(token); store.bindProfile(settings.issuer, verified); return verified;
  }
  public String logout(String sessionId) {
    OidcConfiguration settings = config.get();
    OidcPersistentStore.BrowserSession session = store.session(sessionId);
    String idToken = store.decrypt(session.idSecret);
    store.revoke(sessionId);
    return settings.idpLogoutUrl + "?" + OidcHttpClient.encode(Map.of("id_token_hint", idToken,
        "client_id", settings.clientId, "post_logout_redirect_uri", settings.publicUrl + "/"));
  }
  private static String token(Map<String, Object> response, String name, boolean required) {
    Object value = response.get(name);
    if (value instanceof String token && !token.isBlank() && token.length() <= 32768) { return token; }
    if (required) { throw new SecurityException("OIDC token response incomplete"); } return null;
  }
  private static void requireBearer(Map<String, Object> response) {
    if (!(response.get("token_type") instanceof String type) || !"Bearer".equalsIgnoreCase(type)) { throw new SecurityException("Unsupported OIDC token type"); }
  }
}

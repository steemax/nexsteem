/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.kv.KeyValueStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Shared SQL state and encrypted token secrets, suitable for one active Nexus and failover. */
@Component
public class OidcPersistentStore {
  public static class PendingLogin {
    public String nonce, bindingHash, verifierSecret, returnHash, revision;
    public long expiresAt;
    public PendingLogin() { }
  }
  public static class BrowserSession {
    public String accessSecret, refreshSecret, idSecret, revision, nonce;
    public OidcTokenVerifier.Verified identity;
    public long expiresAt;
    public BrowserSession() { }
  }
  public static class Profile {
    public String issuer, subject, userId, firstName, lastName, email;
    public Set<String> groups = Set.of();
    public Profile() { }
  }
  public static class ExpiringKey {
    public String key;
    public long expiresAt;
    public ExpiringKey() { }
    ExpiringKey(String key, long expiresAt) { this.key = key; this.expiresAt = expiresAt; }
  }
  private static final String LOGINS = "nexus.open.oidc.login-index.v1", SESSIONS = "nexus.open.oidc.session-index.v1", PROFILES = "nexus.open.oidc.profile-index.v1";
  private final KeyValueStore values;
  private final SecretsService secrets;
  private final SecureRandom random = new SecureRandom();
  public OidcPersistentStore(@Qualifier("mybatis") KeyValueStore values, SecretsService secrets) { this.values = values; this.secrets = secrets; }
  public String random() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
  public static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception failure) { throw new IllegalStateException(failure); }
  }
  public String encrypt(String token) {
    if (token == null || token.isEmpty()) { return null; }
    char[] value = token.toCharArray();
    try { return secrets.encrypt("oidc-token", value, null).getId(); }
    finally { Arrays.fill(value, '\0'); }
  }
  public String decrypt(String id) {
    if (id == null) { return null; }
    char[] value = secrets.from(id).decrypt(); try { return new String(value); } finally { Arrays.fill(value, '\0'); }
  }
  private void removeSecret(String id) { if (id != null) { secrets.remove(secrets.from(id)); } }
  public synchronized String createLogin(PendingLogin login) {
    List<ExpiringKey> index = clean(LOGINS, true);
    if (index.size() >= 1000) { removeSecret(login.verifierSecret); throw new IllegalStateException("Too many pending OIDC logins"); }
    String state = random(), key = "nexus.open.oidc.login." + hash(state);
    try { values.setString(key, login); index.add(new ExpiringKey(key, login.expiresAt)); values.setString(LOGINS, index); }
    catch (RuntimeException failure) { values.removeKey(key); removeSecret(login.verifierSecret); throw failure; }
    return state;
  }
  public synchronized PendingLogin consumeLogin(String state, String binding) {
    if (state == null || !state.matches("[A-Za-z0-9_-]{43}") || binding == null) { throw new SecurityException("OIDC state or browser binding missing"); }
    String key = "nexus.open.oidc.login." + hash(state);
    PendingLogin login = values.get(key, PendingLogin.class).orElseThrow(() -> new SecurityException("OIDC state was not found or already used"));
    if (!MessageDigest.isEqual(login.bindingHash.getBytes(StandardCharsets.US_ASCII), hash(binding).getBytes(StandardCharsets.US_ASCII))) {
      throw new SecurityException("OIDC browser binding rejected");
    }
    if (!values.removeKey(key)) { throw new SecurityException("OIDC state already used"); }
    List<ExpiringKey> index = keys(LOGINS); index.removeIf(value -> value.key.equals(key)); values.setString(LOGINS, index);
    if (login.expiresAt <= Instant.now().getEpochSecond()) { removeSecret(login.verifierSecret); throw new SecurityException("OIDC state expired"); }
    return login;
  }
  public void finishLogin(PendingLogin login) { removeSecret(login.verifierSecret); }
  public synchronized String createSession(BrowserSession session) {
    List<ExpiringKey> index = clean(SESSIONS, false);
    if (index.size() >= 10000) { destroySecrets(session); throw new IllegalStateException("Too many active OIDC sessions"); }
    String id = random(), key = "nexus.open.oidc.session." + hash(id);
    try { values.setString(key, session); index.add(new ExpiringKey(key, session.expiresAt)); values.setString(SESSIONS, index); }
    catch (RuntimeException failure) { values.removeKey(key); destroySecrets(session); throw failure; }
    return id;
  }
  public BrowserSession session(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) { throw new SecurityException("Invalid OIDC session identifier"); }
    BrowserSession value = values.get("nexus.open.oidc.session." + hash(id), BrowserSession.class)
        .orElseThrow(() -> new SecurityException("OIDC session was not found"));
    if (value.expiresAt <= Instant.now().getEpochSecond()) { revoke(id); throw new SecurityException("OIDC session expired"); }
    return value;
  }
  public synchronized void updateSession(String id, BrowserSession updated, BrowserSession previous) {
    try { session(id); values.setString("nexus.open.oidc.session." + hash(id), updated); }
    catch (RuntimeException failure) { destroySecrets(updated); throw failure; }
    destroySecrets(previous);
  }
  public synchronized void revoke(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{43}")) { return; }
    String key = "nexus.open.oidc.session." + hash(id);
    BrowserSession session = values.get(key, BrowserSession.class).orElse(null);
    values.removeKey(key);
    if (session != null) { destroySecrets(session); }
    List<ExpiringKey> index = keys(SESSIONS); index.removeIf(value -> value.key.equals(key)); values.setString(SESSIONS, index);
  }
  public void destroySecrets(BrowserSession value) {
    removeSecret(value.accessSecret); value.accessSecret = null;
    removeSecret(value.refreshSecret); value.refreshSecret = null;
    removeSecret(value.idSecret); value.idSecret = null;
  }
  public synchronized void bindProfile(String issuer, OidcTokenVerifier.Verified verified) {
    String key = "nexus.open.oidc.profile." + hash(verified.userId());
    Profile existing = values.get(key, Profile.class).orElse(null);
    if (existing != null && (!issuer.equals(existing.issuer) || !verified.subject().equals(existing.subject))) {
      throw new SecurityException("OIDC username belongs to another subject; administrator review is required");
    }
    Profile profile = new Profile(); profile.issuer = issuer; profile.subject = verified.subject(); profile.userId = verified.userId();
    profile.firstName = verified.firstName(); profile.lastName = verified.lastName(); profile.email = verified.email(); profile.groups = verified.groups();
    values.setString(key, profile);
    List<String> index = new ArrayList<>(Arrays.asList(values.get(PROFILES, String[].class).orElseGet(() -> new String[0])));
    if (!index.contains(key)) { index.add(key); values.setString(PROFILES, index); }
  }
  public Profile profile(String id) { return values.get("nexus.open.oidc.profile." + hash(id), Profile.class).orElse(null); }
  public List<Profile> profiles() {
    return Arrays.stream(values.get(PROFILES, String[].class).orElseGet(() -> new String[0]))
        .map(key -> values.get(key, Profile.class).orElse(null)).filter(Objects::nonNull).toList();
  }
  private List<ExpiringKey> keys(String key) { return new ArrayList<>(Arrays.asList(values.get(key, ExpiringKey[].class).orElseGet(() -> new ExpiringKey[0]))); }
  private List<ExpiringKey> clean(String key, boolean login) {
    List<ExpiringKey> index = keys(key); long now = Instant.now().getEpochSecond();
    Iterator<ExpiringKey> iterator = index.iterator();
    while (iterator.hasNext()) {
      ExpiringKey item = iterator.next();
      if (item.expiresAt <= now) {
        if (login) { values.get(item.key, PendingLogin.class).ifPresent(value -> removeSecret(value.verifierSecret)); }
        else { values.get(item.key, BrowserSession.class).ifPresent(this::destroySecrets); }
        values.removeKey(item.key); iterator.remove();
      }
    }
    values.setString(key, index); return index;
  }
}

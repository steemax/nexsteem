/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.nexus.kv.KeyValueStore;
import org.sonatype.nexus.crypto.secrets.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class OidcPersistentStoreTest {
  private final Map<String,byte[]> values = new ConcurrentHashMap<>();
  private final Map<String,Secret> encrypted = new ConcurrentHashMap<>();
  private final Set<String> removed = ConcurrentHashMap.newKeySet();
  private KeyValueStore kv; private SecretsService secrets; private OidcPersistentStore store;
  @Before public void setup() throws Exception {
    kv = mock(KeyValueStore.class); secrets = mock(SecretsService.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    doAnswer(call -> { values.put(call.getArgument(0), mapper.writeValueAsBytes(call.getArgument(1))); return null; })
        .when(kv).setString(anyString(), any(Object.class));
    when(kv.get(anyString(), any())).thenAnswer(call -> {
      byte[] value = values.get(call.getArgument(0));
      return value == null ? Optional.empty() : Optional.of(mapper.readValue(value, (Class<?>)call.getArgument(1)));
    });
    when(kv.removeKey(anyString())).thenAnswer(call -> values.remove(call.getArgument(0)) != null);
    when(secrets.encrypt(anyString(), any(char[].class), isNull())).thenAnswer(call -> {
      char[] clear = ((char[])call.getArgument(1)).clone(); String id = UUID.randomUUID().toString();
      Secret secret = mock(Secret.class); when(secret.getId()).thenReturn(id); when(secret.decrypt()).thenAnswer(ignored -> clear.clone());
      encrypted.put(id, secret); return secret;
    });
    when(secrets.from(anyString())).thenAnswer(call -> encrypted.get(call.getArgument(0)));
    doAnswer(call -> { Secret secret = call.getArgument(0); assertTrue("Secret must be removed once", removed.add(secret.getId())); return null; })
        .when(secrets).remove(any(Secret.class));
    store = new OidcPersistentStore(kv, secrets);
  }
  private OidcPersistentStore.PendingLogin pending(long expiry) {
    var login = new OidcPersistentStore.PendingLogin(); login.bindingHash = OidcPersistentStore.hash("browser-cookie");
    login.verifierSecret = store.encrypt("pkce-secret"); login.nonce = "nonce"; login.revision = "revision"; login.expiresAt = expiry;
    return login;
  }
  private OidcTokenVerifier.Verified identity(String subject) {
    return new OidcTokenVerifier.Verified("reader", subject, "Test", "Reader", "reader@example.org", Set.of("reader"), Set.of("nx-anonymous"), Instant.now().plusSeconds(600));
  }
  private OidcPersistentStore.BrowserSession browser(long expiry) {
    var value = new OidcPersistentStore.BrowserSession(); value.identity = identity("subject-1"); value.revision = "revision";
    value.nonce = "nonce"; value.expiresAt = expiry; value.accessSecret = store.encrypt("access");
    value.refreshSecret = store.encrypt("refresh"); value.idSecret = store.encrypt("id"); return value;
  }
  @Test public void stateIsOpaqueAndStoredOnlyAsHash() {
    String state = store.createLogin(pending(Instant.now().plusSeconds(600).getEpochSecond()));
    assertTrue(state.matches("[A-Za-z0-9_-]{43}"));
    assertTrue(values.containsKey("nexus.open.oidc.login." + OidcPersistentStore.hash(state)));
    assertFalse(values.keySet().stream().anyMatch(key -> key.contains(state)));
  }
  @Test public void wrongBrowserCannotConsumeStateAndValidCallbackIsSingleUse() {
    String state = store.createLogin(pending(Instant.now().plusSeconds(600).getEpochSecond()));
    assertThrows(SecurityException.class, () -> store.consumeLogin(state, "other-browser"));
    var login = store.consumeLogin(state, "browser-cookie"); assertEquals("pkce-secret", store.decrypt(login.verifierSecret));
    assertThrows(SecurityException.class, () -> store.consumeLogin(state, "browser-cookie"));
    store.finishLogin(login); assertTrue(removed.contains(login.verifierSecret));
  }
  @Test public void expiredCallbackDestroysPkceSecret() {
    var login = pending(Instant.now().minusSeconds(1).getEpochSecond()); String state = store.createLogin(login);
    assertThrows(SecurityException.class, () -> store.consumeLogin(state, "browser-cookie"));
    assertTrue(removed.contains(login.verifierSecret));
  }
  @Test public void parallelReplayHasOnlyOneWinner() throws Exception {
    String state = store.createLogin(pending(Instant.now().plusSeconds(600).getEpochSecond()));
    ExecutorService workers = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> callback = () -> { try { store.consumeLogin(state, "browser-cookie"); return true; } catch (SecurityException rejected) { return false; } };
      var results = workers.invokeAll(List.of(callback, callback));
      assertEquals(1, (results.get(0).get() ? 1 : 0) + (results.get(1).get() ? 1 : 0));
    } finally { workers.shutdownNow(); }
  }
  @Test public void revokeDestroysAllEncryptedTokensAndRejectsSession() {
    var value = browser(Instant.now().plusSeconds(600).getEpochSecond()); String id = store.createSession(value);
    assertEquals("subject-1", store.session(id).identity.subject()); store.revoke(id);
    assertEquals(3, removed.size()); assertThrows(SecurityException.class, () -> store.session(id));
    store.revoke(id); assertEquals(3, removed.size());
  }
  @Test public void expiredBrowserSessionIsRevoked() {
    String id = store.createSession(browser(Instant.now().minusSeconds(1).getEpochSecond()));
    assertThrows(SecurityException.class, () -> store.session(id)); assertEquals(3, removed.size());
  }
  @Test public void usernameCannotBeReboundToAnotherSubjectOrIssuer() {
    store.bindProfile("issuer", identity("subject-1"));
    assertThrows(SecurityException.class, () -> store.bindProfile("issuer", identity("subject-2")));
    assertThrows(SecurityException.class, () -> store.bindProfile("other-issuer", identity("subject-1")));
    assertEquals("subject-1", store.profile("reader").subject);
  }
  @Test public void failedSessionIndexWriteRollsBackSessionAndSecrets() {
    var session = browser(Instant.now().plusSeconds(600).getEpochSecond());
    doAnswer(call -> { if (values.keySet().stream().anyMatch(key -> key.startsWith("nexus.open.oidc.session."))) { throw new IllegalStateException("database failure"); } return null; })
        .when(kv).setString(eq("nexus.open.oidc.session-index.v1"), any(Object.class));
    assertThrows(IllegalStateException.class, () -> store.createSession(session)); store.destroySecrets(session);
    assertEquals(3, removed.size()); assertFalse(values.keySet().stream().anyMatch(key -> key.startsWith("nexus.open.oidc.session.")));
  }
  @Test public void refreshCannotReviveRevokedSession() {
    String id = store.createSession(browser(Instant.now().plusSeconds(600).getEpochSecond()));
    var previous = store.session(id); store.revoke(id);
    var updated = browser(Instant.now().plusSeconds(600).getEpochSecond());
    assertThrows(SecurityException.class, () -> store.updateSession(id, updated, previous));
    assertEquals(6, removed.size()); assertThrows(SecurityException.class, () -> store.session(id));
  }
}

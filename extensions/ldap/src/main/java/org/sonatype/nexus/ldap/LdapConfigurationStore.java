/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.kv.KeyValueStore;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Persist in the Nexus SQL database. Only secret identifiers are kept in the configuration. */
@Component
public class LdapConfigurationStore {
  private static final String KEY = "nexus.open.ldap.servers.v1";
  public static class StoredServer {
    public LdapServer server;
    public String passwordSecret;
    public StoredServer() { }
    StoredServer(LdapServer server, String passwordSecret) { this.server = server; this.passwordSecret = passwordSecret; }
  }
  private final KeyValueStore store;
  private final SecretsService secrets;
  public LdapConfigurationStore(@Qualifier("mybatis") KeyValueStore store, SecretsService secrets) {
    this.store = store; this.secrets = secrets;
  }
  public List<StoredServer> stored() {
    return new ArrayList<>(Arrays.asList(store.get(KEY, StoredServer[].class).orElseGet(() -> new StoredServer[0])));
  }
  public List<LdapServer> list() {
    List<LdapServer> result = new ArrayList<>();
    int order = 1;
    for (StoredServer value : stored()) {
      value.server.authPassword = PasswordPlaceholder.get(value.passwordSecret);
      value.server.order = order++;
      result.add(value.server);
    }
    return result;
  }
  public LdapServer get(String idOrName) {
    return list().stream().filter(s -> idOrName.equals(s.id) || idOrName.equals(s.name)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("LDAP server was not found"));
  }
  public String password(LdapServer settings) {
    if (!PasswordPlaceholder.is(settings.authPassword)) { return settings.authPassword; }
    StoredServer saved = stored().stream().filter(s -> s.server.id.equals(settings.id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Saved LDAP password was not found"));
    if (saved.passwordSecret == null) { return null; }
    char[] value = secrets.from(saved.passwordSecret).decrypt();
    try { return new String(value); }
    finally { Arrays.fill(value, '\0'); }
  }
  public synchronized LdapServer save(LdapServer settings, boolean create) {
    settings.validate(true);
    List<StoredServer> values = stored();
    StoredServer old = values.stream().filter(s -> s.server.id.equals(settings.id)).findFirst().orElse(null);
    if (!create && old == null) { throw new IllegalArgumentException("LDAP server was not found"); }
    if (values.stream().anyMatch(s -> s.server.name.equals(settings.name) && s != old)) {
      throw new IllegalArgumentException("LDAP server name must be unique");
    }
    if (create) { settings.id = UUID.randomUUID().toString(); }
    String previous = old == null ? null : old.passwordSecret;
    if (!"none".equals(settings.authScheme) && (PasswordPlaceholder.is(settings.authPassword) ? previous == null :
        settings.authPassword == null || settings.authPassword.isEmpty())) {
      throw new IllegalArgumentException("LDAP bind password is required");
    }
    String secret = previous;
    if (!PasswordPlaceholder.is(settings.authPassword)) {
      secret = settings.authPassword == null || settings.authPassword.isEmpty() ? null :
          secrets.encrypt("ldap", settings.authPassword.toCharArray(), null).getId();
    }
    if ("none".equals(settings.authScheme)) { secret = null; }
    settings.authPassword = null;
    StoredServer value = new StoredServer(settings, secret);
    if (old == null) { values.add(value); } else { values.set(values.indexOf(old), value); }
    try { store.setString(KEY, values); }
    catch (RuntimeException failure) {
      if (secret != null && !secret.equals(previous)) { secrets.remove(secrets.from(secret)); }
      throw failure;
    }
    if (previous != null && !previous.equals(secret)) { secrets.remove(secrets.from(previous)); }
    return get(settings.id);
  }
  public synchronized void remove(String id) {
    List<StoredServer> values = stored();
    StoredServer old = values.stream().filter(s -> s.server.id.equals(id) || s.server.name.equals(id)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("LDAP server was not found"));
    values.remove(old); store.setString(KEY, values);
    if (old.passwordSecret != null) { secrets.remove(secrets.from(old.passwordSecret)); }
  }
  public synchronized void reorder(List<String> ids) {
    List<StoredServer> values = stored();
    if (ids.size() != values.size() || ids.stream().distinct().count() != ids.size()) {
      throw new IllegalArgumentException("Include every LDAP server exactly once");
    }
    List<StoredServer> ordered = ids.stream().map(id -> values.stream().filter(s -> s.server.id.equals(id))
        .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown LDAP server"))).toList();
    store.setString(KEY, ordered);
  }
}

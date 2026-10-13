/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.util.Arrays;
import java.util.UUID;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.kv.KeyValueStore;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OidcConfigurationStore {
  public static class StoredConfiguration {
    public OidcConfiguration configuration;
    public String clientSecretId, revision;
    public StoredConfiguration() { }
  }
  private static final String KEY = "nexus.open.oidc.configuration.v1";
  private final KeyValueStore values;
  private final SecretsService secrets;
  private final boolean allowHttp;
  private final String defaultPublicUrl;
  public OidcConfigurationStore(@Qualifier("mybatis") KeyValueStore values, SecretsService secrets,
      @Value("${nexus.oidc.allow-http:false}") boolean allowHttp,
      @Value("${nexus.oidc.public-url:}") String defaultPublicUrl) {
    this.values = values; this.secrets = secrets; this.allowHttp = allowHttp; this.defaultPublicUrl = defaultPublicUrl;
  }
  public StoredConfiguration stored() { return values.get(KEY, StoredConfiguration.class).orElse(null); }
  public OidcConfiguration get() {
    StoredConfiguration saved = stored();
    if (saved == null) { OidcConfiguration defaults = new OidcConfiguration(); defaults.publicUrl = defaultPublicUrl; return defaults; }
    saved.configuration.clientSecret = PasswordPlaceholder.get(saved.clientSecretId);
    return saved.configuration;
  }
  public String revision() { StoredConfiguration saved = stored(); return saved == null ? "" : saved.revision; }
  public String secret() {
    StoredConfiguration saved = stored();
    if (saved == null || saved.clientSecretId == null) { throw new IllegalStateException("OIDC client is not configured"); }
    char[] value = secrets.from(saved.clientSecretId).decrypt();
    try { return new String(value); } finally { Arrays.fill(value, '\0'); }
  }
  public synchronized OidcConfiguration save(OidcConfiguration config) {
    config.validate(allowHttp, defaultPublicUrl);
    StoredConfiguration old = stored();
    String previous = old == null ? null : old.clientSecretId;
    if (config.clientSecret == null || config.clientSecret.isEmpty() || PasswordPlaceholder.is(config.clientSecret) && previous == null) {
      throw new IllegalArgumentException("Client Secret is required");
    }
    String id = PasswordPlaceholder.is(config.clientSecret) ? previous : secrets.encrypt("oidc-client", config.clientSecret.toCharArray(), null).getId();
    config.clientSecret = "";
    StoredConfiguration saved = new StoredConfiguration(); saved.configuration = config; saved.clientSecretId = id; saved.revision = UUID.randomUUID().toString();
    try { values.setString(KEY, saved); }
    catch (RuntimeException failure) { if (!id.equals(previous)) { secrets.remove(secrets.from(id)); } throw failure; }
    if (previous != null && !previous.equals(id)) { secrets.remove(secrets.from(previous)); }
    return get();
  }
}

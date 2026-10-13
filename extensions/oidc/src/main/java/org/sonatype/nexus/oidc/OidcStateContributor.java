/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import java.util.Map;
import org.sonatype.nexus.rapture.StateContributor;
import org.sonatype.nexus.security.authc.SsoDetector;
import org.sonatype.nexus.security.realm.RealmManager;
import org.springframework.stereotype.Component;
@Component
public class OidcStateContributor implements StateContributor, SsoDetector {
  private final OidcConfigurationStore configuration;
  private final RealmManager realms;
  public OidcStateContributor(OidcConfigurationStore configuration, RealmManager realms) { this.configuration = configuration; this.realms = realms; }
  @Override public boolean isSsoEnabled() { return configuration.get().configured() && realms.isRealmEnabled("OAuth2Realm"); }
  @Override public Map<String, Object> getState() { return Map.of("oauth2Available", true, "oauth2Enabled", isSsoEnabled()); }
}

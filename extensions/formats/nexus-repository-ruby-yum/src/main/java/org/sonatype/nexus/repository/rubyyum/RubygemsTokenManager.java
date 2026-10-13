/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.authc.apikey.ApiKeyService;
import org.sonatype.nexus.security.token.BearerTokenManager;
import org.springframework.stereotype.Component;
@Component
public class RubygemsTokenManager extends BearerTokenManager {
  private final SecurityHelper security;
  public RubygemsTokenManager(ApiKeyService keys,SecurityHelper security){super(keys,security,RubygemsTokenExtractor.NAME);this.security=security;}
  public boolean authenticated(){return security.subject().isAuthenticated();}
  public String issue(String presented) {
    // Already authenticated JWTs retain their original expiry for CLI usage.
    if(presented!=null&&presented.startsWith("Bearer ")&&presented.split("\\.").length==3)return presented;
    var principals=security.subject().getPrincipals();
    for(String realm:principals.getRealmNames())if(realm.toLowerCase(java.util.Locale.ROOT).contains("oauth")||realm.toLowerCase(java.util.Locale.ROOT).contains("keycloak"))throw new IllegalArgumentException("Use an expiring OIDC access token for RubyGems CLI");
    return createToken(principals);
  }
}

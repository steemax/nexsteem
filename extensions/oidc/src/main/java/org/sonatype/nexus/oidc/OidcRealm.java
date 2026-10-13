/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.io.Serializable;
import java.time.Instant;
import org.apache.shiro.authc.*;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.subject.*;
import org.sonatype.nexus.common.Description;
import org.sonatype.nexus.security.authz.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component @Qualifier(OidcRealm.NAME) @Description("Open OAuth 2.0 / Keycloak Realm")
public class OidcRealm extends PermissionCachingAuthorizingRealm {
  public static final String NAME = "OAuth2Realm";
  public record Identity(OidcTokenVerifier.Verified profile, String revision) implements Serializable { }
  private final OidcClient client;
  private final OidcConfigurationStore configuration;
  public OidcRealm(PrincipalPermissionsCacheFactory caches, OidcClient client, OidcConfigurationStore configuration) {
    super(NAME, caches); this.client = client; this.configuration = configuration;
    setAuthenticationCachingEnabled(false); setAuthorizationCachingEnabled(false); setCredentialsMatcher(new AllowAllCredentialsMatcher());
  }
  public boolean supports(AuthenticationToken token) { return token instanceof OidcAuthenticationToken; }
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
    try {
      OidcAuthenticationToken supplied = (OidcAuthenticationToken) token;
      String revision = configuration.revision();
      OidcTokenVerifier.Verified verified = supplied.browser() ? client.session((String) supplied.getCredentials()) : client.bearer((String) supplied.getCredentials());
      if (!revision.equals(configuration.revision())) { throw new SecurityException("OIDC configuration changed"); }
      SimplePrincipalCollection principals = new SimplePrincipalCollection(verified.userId(), NAME);
      principals.add(new Identity(verified, revision), NAME);
      return new SimpleAuthenticationInfo(principals, token.getCredentials());
    }
    catch (Exception rejected) { throw new AuthenticationException("OIDC authentication rejected", rejected); }
  }
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    for (Object principal : principals.fromRealm(NAME)) {
      if (principal instanceof Identity identity && identity.revision().equals(configuration.revision()) &&
          Instant.now().isBefore(identity.profile().expiresAt())) { return new SimpleAuthorizationInfo(identity.profile().roles()); }
    }
    return null;
  }
}

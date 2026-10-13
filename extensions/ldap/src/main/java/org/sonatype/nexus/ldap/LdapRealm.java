/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.LinkedHashSet;
import java.util.Set;
import javax.naming.NamingException;
import org.apache.shiro.authc.*;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.subject.PrincipalCollection;
import org.sonatype.nexus.common.Description;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.PermissionCachingAuthorizingRealm;
import org.sonatype.nexus.security.authz.PrincipalPermissionsCacheFactory;
import org.sonatype.nexus.security.role.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Qualifier(LdapRealm.NAME)
@Description("LDAP Realm")
public class LdapRealm extends PermissionCachingAuthorizingRealm {
  public static final String NAME = "LdapRealm";
  private final LdapConfigurationStore store;
  private final LdapDirectory directory;
  private final AuthorizationManager roles;
  public LdapRealm(PrincipalPermissionsCacheFactory caches, LdapConfigurationStore store,
      LdapDirectory directory, @Lazy @Qualifier("default") AuthorizationManager roles) {
    super(NAME, caches); this.store = store; this.directory = directory; this.roles = roles;
    setCredentialsMatcher(new SimpleCredentialsMatcher());
    setAuthenticationCachingEnabled(false); setAuthorizationCachingEnabled(false);
  }
  @Override public boolean supports(AuthenticationToken token) { return token instanceof UsernamePasswordToken; }
  @Override protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
    UsernamePasswordToken credentials = (UsernamePasswordToken) token;
    if (credentials.getPassword() == null || credentials.getPassword().length == 0) {
      throw new AuthenticationException("LDAP credentials rejected");
    }
    for (LdapServer server : store.list()) {
      try {
        LdapDirectory.DirectoryUser user = directory.authenticate(server, store.password(server),
            credentials.getUsername(), new String(credentials.getPassword()));
        return new SimpleAuthenticationInfo(user.username(), credentials.getPassword(), NAME);
      }
      catch (javax.naming.AuthenticationException rejected) {
        throw new AuthenticationException("LDAP credentials rejected");
      }
      catch (NamingException unavailable) { /* Try the next configured directory. */ }
    }
    throw new UnknownAccountException("LDAP credentials rejected");
  }
  @Override protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    if (!principals.getRealmNames().contains(NAME)) { return null; }
    String username = principals.getPrimaryPrincipal().toString();
    for (LdapServer server : store.list()) {
      try {
        var users = directory.users(server, store.password(server), username, false, 2);
        if (users.size() != 1) { continue; }
        Set<String> mapped = new LinkedHashSet<>();
        for (String group : users.get(0).membership()) {
          // Only administrator-created mappings grant permissions; a directory group named
          // nx-admin must never inherit the built-in administrator role by coincidence.
          try {
            Role role = roles.getRole(group);
            if (role != null && !role.isReadOnly()) { mapped.add(group); }
          }
          catch (org.sonatype.nexus.security.role.NoSuchRoleException unmapped) { /* No explicit mapping. */ }
        }
        return new SimpleAuthorizationInfo(mapped);
      }
      catch (NamingException unavailable) { /* A failed directory contributes no permissions. */ }
    }
    return new SimpleAuthorizationInfo(Set.of());
  }
}

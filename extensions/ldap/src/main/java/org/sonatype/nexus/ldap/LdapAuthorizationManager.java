/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import javax.naming.NamingException;
import org.sonatype.nexus.security.authz.AbstractReadOnlyAuthorizationManager;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component @Qualifier("LDAP")
public class LdapAuthorizationManager extends AbstractReadOnlyAuthorizationManager {
  private final LdapConfigurationStore store;
  private final LdapDirectory directory;
  public LdapAuthorizationManager(LdapConfigurationStore store, LdapDirectory directory) { this.store = store; this.directory = directory; }
  @Override public String getSource() { return "LDAP"; }
  @Override public String getRealmName() { return LdapRealm.NAME; }
  @Override public Set<Role> listRoles() { return searchRoles(""); }
  @Override public Set<Role> searchRoles(String query) {
    Set<Role> result = new LinkedHashSet<>();
    for (LdapServer server : store.list()) {
      try {
        directory.groups(server, store.password(server), query).forEach(id -> result.add(new Role(
            id, id, "LDAP group", "LDAP", true, Set.of(), Set.of())));
      }
      catch (NamingException unavailable) { /* An unavailable directory contributes no groups. */ }
    }
    return result;
  }
  @Override public Role getRole(String id) { return searchRoles(id).stream().filter(r -> r.getRoleId().equals(id)).findFirst().orElse(null); }
  @Override public Set<Privilege> listPrivileges() { return Set.of(); }
  @Override public Privilege getPrivilege(String id) { return null; }
  @Override public Privilege getPrivilegeByName(String name) { return null; }
  @Override public List<Privilege> getPrivileges(Set<String> ids) { return List.of(); }
}

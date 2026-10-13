/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import java.util.*;
import org.sonatype.nexus.security.authz.AbstractReadOnlyAuthorizationManager;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.Role;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
@Component @Qualifier("OAuth2")
public class OidcAuthorizationManager extends AbstractReadOnlyAuthorizationManager {
  private final OidcPersistentStore profiles;
  public OidcAuthorizationManager(OidcPersistentStore profiles) { this.profiles = profiles; }
  public String getSource() { return "OAuth2"; }
  public String getRealmName() { return OidcRealm.NAME; }
  public Set<Role> listRoles() {
    Set<String> names = new TreeSet<>(); profiles.profiles().forEach(profile -> names.addAll(profile.groups));
    Set<Role> result = new TreeSet<>(); names.forEach(name -> result.add(new Role(name, name, "OIDC group", "OAuth2", true, Set.of(), Set.of()))); return result;
  }
  public Role getRole(String id) { return listRoles().stream().filter(role -> id.equals(role.getRoleId())).findFirst().orElse(null); }
  public Set<Privilege> listPrivileges() { return Set.of(); }
  public Privilege getPrivilege(String id) { return null; }
  public Privilege getPrivilegeByName(String name) { return null; }
  public List<Privilege> getPrivileges(Set<String> ids) { return List.of(); }
}

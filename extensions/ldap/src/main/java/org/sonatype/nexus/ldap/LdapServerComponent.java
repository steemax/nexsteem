/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.stereotype.Component;

/** Implements the existing upstream LDAP screen's Direct API. */
@Component
@DirectAction(action = "ldap_LdapServer")
public class LdapServerComponent extends DirectComponentSupport {
  private final LdapConfigurationStore store;
  private final LdapDirectory directory;
  public LdapServerComponent(LdapConfigurationStore store, LdapDirectory directory) {
    this.store = store; this.directory = directory;
  }
  @DirectMethod @RequiresPermissions("nexus:ldap:read")
  public List<LdapServer> read() { return store.list(); }
  @DirectMethod @RequiresPermissions("nexus:ldap:read")
  public List<Map<String, String>> readReferences() { return store.list().stream().map(s -> Map.of("id", s.id, "name", s.name)).toList(); }
  @DirectMethod @RequiresPermissions("nexus:ldap:read")
  public List<LdapServer> readTemplates() {
    LdapServer generic = new LdapServer(); generic.name = "Generic LDAP Server";
    generic.ldapGroupsAsRoles = true; generic.groupType = "dynamic"; generic.userMemberOfAttribute = "memberOf";
    LdapServer ad = new LdapServer(); ad.name = "Active Directory"; ad.userIdAttribute = "sAMAccountName";
    ad.userObjectClass = "user"; ad.userBaseDn = "cn=users"; ad.ldapGroupsAsRoles = true;
    ad.groupType = "dynamic"; ad.userMemberOfAttribute = "memberOf"; ad.groupObjectClass = "group";
    LdapServer posix = new LdapServer(); posix.name = "Posix with Static Groups"; posix.userObjectClass = "posixAccount";
    posix.userBaseDn = "ou=people"; posix.ldapGroupsAsRoles = true; posix.groupBaseDn = "ou=groups";
    posix.groupObjectClass = "posixGroup"; posix.groupMemberAttribute = "memberUid"; posix.groupMemberFormat = "${username}";
    LdapServer dynamic = new LdapServer(); dynamic.name = "Posix with Dynamic Groups"; dynamic.userObjectClass = "posixAccount";
    dynamic.userBaseDn = "ou=people"; dynamic.ldapGroupsAsRoles = true; dynamic.groupType = "dynamic";
    dynamic.userMemberOfAttribute = "memberOf";
    return List.of(ad, generic, posix, dynamic);
  }
  @DirectMethod @RequiresPermissions("nexus:ldap:create")
  public LdapServer create(LdapServer server) { return store.save(server, true); }
  @DirectMethod @RequiresPermissions("nexus:ldap:update")
  public LdapServer update(LdapServer server) { return store.save(server, false); }
  @DirectMethod @RequiresPermissions("nexus:ldap:delete")
  public void remove(String id) { store.remove(id); }
  @DirectMethod @RequiresPermissions("nexus:ldap:update")
  public void changeOrder(List<String> ids) { store.reorder(ids); }
  @DirectMethod @RequiresPermissions("nexus:ldap:delete")
  public void clearCache() { /* No LDAP credential or membership caches are used. */ }
  @DirectMethod @RequiresPermissions("nexus:ldap:update")
  public void verifyConnection(LdapServer server) throws javax.naming.NamingException {
    directory.verifyConnection(server, store.password(server));
  }
  @DirectMethod @RequiresPermissions("nexus:ldap:update")
  public List<LdapDirectory.DirectoryUser> verifyUserMapping(LdapServer server) throws javax.naming.NamingException {
    return directory.users(server, store.password(server), null, false, 20);
  }
  @DirectMethod @RequiresPermissions("nexus:ldap:update")
  public void verifyLogin(LdapServer server, String username, String password) throws javax.naming.NamingException {
    directory.authenticate(server, store.password(server), decode(username), decode(password));
  }
  private static String decode(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
}

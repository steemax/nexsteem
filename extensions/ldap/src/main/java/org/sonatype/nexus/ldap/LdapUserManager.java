/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.LinkedHashSet;
import java.util.Set;
import javax.naming.NamingException;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.user.*;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component @Qualifier("LDAP")
public class LdapUserManager extends AbstractReadOnlyUserManager {
  private final LdapConfigurationStore store;
  private final LdapDirectory directory;
  public LdapUserManager(EventManager events, LdapConfigurationStore store, LdapDirectory directory) {
    super(events); this.store = store; this.directory = directory;
  }
  @Override public String getSource() { return "LDAP"; }
  @Override public String getAuthenticationRealmName() { return LdapRealm.NAME; }
  @Override public boolean isConfigured() { return !store.list().isEmpty(); }
  @Override public User getUser(String id) throws UserNotFoundException { return getUser(id, null); }
  @Override public User getUser(String id, Set<String> knownRoles) throws UserNotFoundException {
    for (LdapServer server : store.list()) {
      try {
        var users = directory.users(server, store.password(server), id, false, 2);
        if (users.size() == 1) { return convert(users.get(0)); }
      }
      catch (NamingException unavailable) { log.debug("LDAP directory unavailable while looking up a user"); }
    }
    throw new UserNotFoundException(id);
  }
  @Override public Set<User> listUsers() { return searchUsers(new UserSearchCriteria()); }
  @Override public Set<String> listUserIds() {
    Set<String> ids = new LinkedHashSet<>(); listUsers().forEach(u -> ids.add(u.getUserId())); return ids;
  }
  @Override public Set<User> searchUsers(UserSearchCriteria criteria) {
    Set<User> result = new LinkedHashSet<>();
    for (LdapServer server : store.list()) {
      try { directory.users(server, store.password(server), criteria.getUserId(), true, 1000).forEach(u -> result.add(convert(u))); }
      catch (NamingException unavailable) { log.debug("LDAP directory unavailable while searching users"); }
    }
    return filterListInMemory(result, criteria);
  }
  private User convert(LdapDirectory.DirectoryUser value) {
    User user = new User(); user.setUserId(value.username()); user.setSource("LDAP"); user.setStatus(UserStatus.active);
    user.setFirstName(value.realName() == null ? value.username() : value.realName()); user.setLastName("");
    user.setEmailAddress(value.email());
    value.membership().forEach(role -> user.addRole(new RoleIdentifier("LDAP", role)));
    return user;
  }
}

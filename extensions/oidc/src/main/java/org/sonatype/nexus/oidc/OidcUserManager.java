/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.util.LinkedHashSet;
import java.util.Set;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.user.*;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component @Qualifier("OAuth2")
public class OidcUserManager extends AbstractReadOnlyUserManager {
  private final OidcPersistentStore profiles;
  private final OidcConfigurationStore configuration;
  public OidcUserManager(EventManager events, OidcPersistentStore profiles, OidcConfigurationStore configuration) {
    super(events); this.profiles = profiles; this.configuration = configuration;
  }
  public String getSource() { return "OAuth2"; }
  public String getAuthenticationRealmName() { return OidcRealm.NAME; }
  public boolean isConfigured() { return configuration.get().configured(); }
  public User getUser(String id) throws UserNotFoundException { return getUser(id, null); }
  public User getUser(String id, Set<String> knownRoles) throws UserNotFoundException {
    var profile = profiles.profile(id);
    if (profile == null || !configuration.get().issuer.equals(profile.issuer)) { throw new UserNotFoundException(id); }
    return convert(profile);
  }
  public Set<User> listUsers() { return searchUsers(new UserSearchCriteria()); }
  public Set<String> listUserIds() { Set<String> result = new LinkedHashSet<>(); listUsers().forEach(user -> result.add(user.getUserId())); return result; }
  public Set<User> searchUsers(UserSearchCriteria criteria) {
    Set<User> result = new LinkedHashSet<>(); String issuer = configuration.get().issuer;
    profiles.profiles().stream().filter(profile -> issuer.equals(profile.issuer)).map(this::convert).forEach(result::add);
    return filterListInMemory(result, criteria);
  }
  private User convert(OidcPersistentStore.Profile profile) {
    User user = new User(); user.setUserId(profile.userId); user.setSource("OAuth2"); user.setStatus(UserStatus.active);
    user.setFirstName(profile.firstName == null ? profile.userId : profile.firstName);
    user.setLastName(profile.lastName == null ? "" : profile.lastName); user.setEmailAddress(profile.email);
    profile.groups.forEach(role -> user.addRole(new RoleIdentifier("OAuth2", role)));
    return user;
  }
}

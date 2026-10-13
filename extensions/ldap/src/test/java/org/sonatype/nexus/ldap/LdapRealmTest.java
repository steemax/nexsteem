/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.List;
import java.util.Set;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.PrincipalPermissionsCacheFactory;
import org.sonatype.nexus.security.role.Role;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LdapRealmTest {
  private LdapConfigurationStore store;
  private LdapDirectory directory;
  private AuthorizationManager roles;
  private LdapServer server;
  private LdapRealm realm;
  @Before public void setup() {
    store = mock(LdapConfigurationStore.class); directory = mock(LdapDirectory.class); roles = mock(AuthorizationManager.class);
    server = new LdapServer(); server.name = "Test";
    when(store.list()).thenReturn(List.of(server)); when(store.password(server)).thenReturn("system-secret");
    realm = new LdapRealm(mock(PrincipalPermissionsCacheFactory.class), store, directory, roles);
  }
  @Test public void builtinRoleNamesAreNotImplicitMappings() throws Exception {
    when(directory.users(server, "system-secret", "reader", false, 2)).thenReturn(List.of(
        new LdapDirectory.DirectoryUser("reader", "Reader", null, "uid=reader", Set.of("nx-admin", "readers", "unmapped"))));
    when(roles.getRole("nx-admin")).thenReturn(new Role("nx-admin", "Admin", "", "default", true, Set.of(), Set.of()));
    when(roles.getRole("readers")).thenReturn(new Role("readers", "Readers", "", "default", false, Set.of(), Set.of()));
    assertEquals(Set.of("readers"), realm.doGetAuthorizationInfo(new SimplePrincipalCollection("reader", LdapRealm.NAME)).getRoles());
  }
  @Test public void otherRealmsAreNotAuthorized() {
    assertNull(realm.doGetAuthorizationInfo(new SimplePrincipalCollection("admin", "NexusAuthenticatingRealm")));
    verifyNoInteractions(directory);
  }
  @Test public void unavailableDirectoryGrantsNothing() throws Exception {
    when(directory.users(server, "system-secret", "reader", false, 2)).thenThrow(new javax.naming.CommunicationException());
    assertTrue(realm.doGetAuthorizationInfo(new SimplePrincipalCollection("reader", LdapRealm.NAME)).getRoles().isEmpty());
  }
  @Test(expected = AuthenticationException.class) public void emptyPasswordRejectedBeforeBind() {
    realm.doGetAuthenticationInfo(new UsernamePasswordToken("reader", ""));
  }
  @Test(expected = AuthenticationException.class) public void invalidPasswordDoesNotTryAnotherDirectory() throws Exception {
    LdapServer second = new LdapServer(); second.name = "Second";
    when(store.list()).thenReturn(List.of(server, second));
    when(directory.authenticate(server, "system-secret", "reader", "wrong")).thenThrow(new javax.naming.AuthenticationException());
    try { realm.doGetAuthenticationInfo(new UsernamePasswordToken("reader", "wrong")); }
    finally { verify(directory, never()).authenticate(eq(second), any(), any(), any()); }
  }
  @Test public void unavailableFirstDirectoryAllowsSecond() throws Exception {
    LdapServer second = new LdapServer(); second.name = "Second";
    when(store.list()).thenReturn(List.of(server, second)); when(store.password(second)).thenReturn("system-secret");
    when(directory.authenticate(server, "system-secret", "reader", "reader-secret")).thenThrow(new javax.naming.CommunicationException());
    when(directory.authenticate(second, "system-secret", "reader", "reader-secret")).thenReturn(
        new LdapDirectory.DirectoryUser("reader", "Reader", null, "uid=reader", Set.of("readers")));
    assertEquals("reader", realm.doGetAuthenticationInfo(new UsernamePasswordToken("reader", "reader-secret")).getPrincipals().getPrimaryPrincipal());
  }
}

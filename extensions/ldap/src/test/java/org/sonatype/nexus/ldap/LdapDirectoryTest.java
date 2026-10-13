/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import javax.naming.AuthenticationException;
import javax.naming.NameNotFoundException;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real LDAPv3 operations against a local directory, including malicious search input. */
public class LdapDirectoryTest {
  private InMemoryDirectoryServer directory;
  private LdapServer settings;
  private LdapDirectory client;
  @Before public void start() throws Exception {
    InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=org");
    config.setSchema(null);
    config.addAdditionalBindCredentials("cn=manager,dc=example,dc=org", "system-secret");
    directory = new InMemoryDirectoryServer(config); directory.startListening();
    directory.add("dn: dc=example,dc=org", "objectClass: domain", "dc: example");
    directory.add("dn: ou=people,dc=example,dc=org", "objectClass: organizationalUnit", "ou: people");
    directory.add("dn: ou=groups,dc=example,dc=org", "objectClass: organizationalUnit", "ou: groups");
    directory.add("dn: uid=reader,ou=people,dc=example,dc=org", "objectClass: inetOrgPerson", "uid: reader",
        "cn: Test Reader", "sn: Reader", "mail: reader@example.org", "userPassword: reader-secret",
        "memberOf: cn=readers,ou=groups,dc=example,dc=org");
    directory.add("dn: cn=readers,ou=groups,dc=example,dc=org", "objectClass: groupOfNames", "cn: readers",
        "member: uid=reader,ou=people,dc=example,dc=org");
    settings = new LdapServer(); settings.name = "Test LDAP"; settings.host = "127.0.0.1";
    settings.port = directory.getListenPort(); settings.searchBase = "dc=example,dc=org";
    settings.authScheme = "simple"; settings.authUsername = "cn=manager,dc=example,dc=org";
    settings.userBaseDn = "ou=people"; settings.groupBaseDn = "ou=groups";
    settings.ldapGroupsAsRoles = true; client = new LdapDirectory(null);
  }
  @After public void stop() { if (directory != null) { directory.shutDown(true); } }
  @Test public void connectionAndStaticGroups() throws Exception {
    client.verifyConnection(settings, "system-secret");
    var user = client.authenticate(settings, "system-secret", "reader", "reader-secret");
    assertEquals("Test Reader", user.realName()); assertEquals("reader@example.org", user.email());
    assertEquals(java.util.Set.of("readers"), user.membership());
    assertEquals(java.util.Set.of("readers"), client.groups(settings, "system-secret", "read"));
  }
  @Test public void dynamicGroups() throws Exception {
    settings.groupType = "dynamic"; settings.userMemberOfAttribute = "memberOf";
    assertEquals(java.util.Set.of("readers"), client.users(settings, "system-secret", "reader", false, 2).get(0).membership());
  }
  @Test public void searchPrefixAndFilter() throws Exception {
    assertEquals(1, client.users(settings, "system-secret", "rea", true, 20).size());
    settings.userLdapFilter = "(mail=nobody@example.org)";
    assertTrue(client.users(settings, "system-secret", "reader", false, 20).isEmpty());
  }
  @Test public void injectionIsEscaped() throws Exception {
    assertTrue(client.users(settings, "system-secret", "*)(uid=*)", false, 20).isEmpty());
    assertTrue(client.groups(settings, "system-secret", "*)(cn=*)").isEmpty());
  }
  @Test(expected = AuthenticationException.class) public void wrongUserPassword() throws Exception {
    client.authenticate(settings, "system-secret", "reader", "wrong");
  }
  @Test(expected = AuthenticationException.class) public void emptyPasswordCannotBindAnonymously() throws Exception {
    client.authenticate(settings, "system-secret", "reader", "");
  }
  @Test(expected = AuthenticationException.class) public void wrongSystemPassword() throws Exception {
    client.verifyConnection(settings, "wrong");
  }
  @Test(expected = NameNotFoundException.class) public void nonexistentUser() throws Exception {
    client.authenticate(settings, "system-secret", "missing", "reader-secret");
  }
  @Test(expected = IllegalArgumentException.class) public void rejectsUrlInjectedHost() {
    settings.host = "127.0.0.1/path"; settings.validate(true);
  }
  @Test(expected = IllegalArgumentException.class) public void rejectsInjectedAttribute() {
    settings.userIdAttribute = "uid)(objectClass=*"; settings.validate(true);
  }
  @Test public void ldapsTrustAndHostnameAreVerified() throws Exception {
    var certificate = com.unboundid.ldap.listener.SelfSignedCertificateGenerator.generateTemporarySelfSignedCertificate("LDAP test", "JKS");
    var ssl = new com.unboundid.util.ssl.SSLUtil(
        new com.unboundid.util.ssl.KeyStoreKeyManager(certificate.getFirst(), certificate.getSecond(), "JKS", null),
        new com.unboundid.util.ssl.TrustStoreTrustManager(certificate.getFirst(), certificate.getSecond(), "JKS", true));
    var config = new InMemoryDirectoryServerConfig("dc=example,dc=org"); config.setSchema(null);
    config.setListenerConfigs(com.unboundid.ldap.listener.InMemoryListenerConfig.createLDAPSConfig("tls", null, 0,
        ssl.createSSLServerSocketFactory(), ssl.createSSLSocketFactory()));
    config.addAdditionalBindCredentials(settings.authUsername, "system-secret");
    var secure = new InMemoryDirectoryServer(config);
    try {
      secure.add("dn: dc=example,dc=org", "objectClass: domain", "dc: example"); secure.startListening();
      var keystore = java.security.KeyStore.getInstance("JKS");
      try (var input = new java.io.FileInputStream(certificate.getFirst())) { keystore.load(input, certificate.getSecond()); }
      var cert = (java.security.cert.X509Certificate) keystore.getCertificate(keystore.aliases().nextElement());
      settings.protocol = "ldaps";
      settings.host = cert.getSubjectAlternativeNames().stream().filter(name -> Integer.valueOf(7).equals(name.get(0)))
          .map(name -> String.valueOf(name.get(1))).filter(ip -> ip.contains(".")).findFirst().orElseThrow();
      settings.port = secure.getListenPort();
      settings.useTrustStore = true;
      var trust = mock(org.sonatype.nexus.ssl.TrustStore.class);
      when(trust.getSSLContext()).thenReturn(ssl.createSSLContext());
      new LdapDirectory(trust).verifyConnection(settings, "system-secret");
      settings.useTrustStore = false;
      try { new LdapDirectory(null).verifyConnection(settings, "system-secret"); fail("Untrusted LDAPS certificate accepted"); }
      catch (javax.naming.NamingException expected) { }
      settings.useTrustStore = true; settings.host = "127.0.0.2";
      try { new LdapDirectory(trust).verifyConnection(settings, "system-secret"); fail("LDAPS hostname mismatch accepted"); }
      catch (javax.naming.NamingException expected) { }
    }
    finally { secure.shutDown(true); certificate.getFirst().delete(); }
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import java.net.InetAddress;

/** Disposable LDAPv3 server for tests, with no production credentials. */
public class DirectoryFixture {
  public static void main(String[] args) throws Exception {
    InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=org");
    config.setSchema(null);
    config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", InetAddress.getByName("0.0.0.0"), 1389, null));
    config.addAdditionalBindCredentials("cn=manager,dc=example,dc=org", "system-secret");
    InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
    server.add("dn: dc=example,dc=org", "objectClass: domain", "dc: example");
    server.add("dn: ou=people,dc=example,dc=org", "objectClass: organizationalUnit", "ou: people");
    server.add("dn: ou=groups,dc=example,dc=org", "objectClass: organizationalUnit", "ou: groups");
    server.add("dn: uid=reader,ou=people,dc=example,dc=org", "objectClass: inetOrgPerson", "uid: reader", "cn: Test Reader",
        "sn: Reader", "mail: reader@example.org", "userPassword: reader-secret", "memberOf: cn=readers,ou=groups,dc=example,dc=org");
    server.add("dn: cn=readers,ou=groups,dc=example,dc=org", "objectClass: groupOfNames", "cn: readers",
        "member: uid=reader,ou=people,dc=example,dc=org");
    server.startListening();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.shutDown(true)));
    System.out.println("LDAP fixture listening on 1389");
    new java.util.concurrent.CountDownLatch(1).await();
  }
}

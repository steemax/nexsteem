/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributorSupport;
import org.springframework.stereotype.Component;
@Component
public class LdapSecurityContributor extends SecurityContributorSupport {
  @Override public MemorySecurityConfiguration getContribution() {
    MemorySecurityConfiguration config = new MemorySecurityConfiguration();
    createCrudAndAllApplicationPrivileges("nx-ldap", "ldap").forEach(config::addPrivilege);
    return config;
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityContributorSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Administrators may grant observation without granting cluster or repository writes. */
@Component
@ConditionalOnProperty(name="nexus.cluster.enabled", havingValue="true")
public final class ClusterNodesSecurityContributor extends SecurityContributorSupport {
  @Override public MemorySecurityConfiguration getContribution() {
    MemorySecurityConfiguration configuration=new MemorySecurityConfiguration();
    configuration.addPrivilege(createApplicationPrivilege("nx-nodes-read",
        "Read Kubernetes cluster members and replication status","nodes",ACTION_READ));
    return configuration;
  }
}

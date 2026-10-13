/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import java.util.Map;
import org.sonatype.nexus.rapture.StateContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="nexus.cluster.enabled", havingValue="true")
public final class ClusterUiStateContributor implements StateContributor {
  @Override public Map<String,Object> getState() { return Map.of("nxClusterEnabled",true); }
}

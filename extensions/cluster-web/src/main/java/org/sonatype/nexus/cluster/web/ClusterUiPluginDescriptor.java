/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import org.sonatype.nexus.rapture.UiPluginDescriptorSupport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="nexus.cluster.enabled", havingValue="true")
public final class ClusterUiPluginDescriptor extends UiPluginDescriptorSupport {
  public ClusterUiPluginDescriptor() {
    super("nexus-cluster-web");
    setHasStyle(false);
    setNamespace("NX.clusterweb");
    setConfigClassName("NX.clusterweb.app.PluginConfig");
  }
}

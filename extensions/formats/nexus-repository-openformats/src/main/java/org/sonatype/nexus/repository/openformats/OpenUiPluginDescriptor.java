/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
@org.springframework.stereotype.Component
public class OpenUiPluginDescriptor extends org.sonatype.nexus.rapture.UiPluginDescriptorSupport {
 public OpenUiPluginDescriptor(){super("nexus-repository-openformats");setHasStyle(false);setNamespace("NX.openformats");setConfigClassName("NX.openformats.app.PluginConfig");}
}

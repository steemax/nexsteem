/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
@org.springframework.stereotype.Component
public class RubyYumUiPluginDescriptor extends org.sonatype.nexus.rapture.UiPluginDescriptorSupport {
  public RubyYumUiPluginDescriptor(){super("nexus-repository-ruby-yum");setHasStyle(false);setNamespace("NX.rubyyum");setConfigClassName("NX.rubyyum.app.PluginConfig");}
}

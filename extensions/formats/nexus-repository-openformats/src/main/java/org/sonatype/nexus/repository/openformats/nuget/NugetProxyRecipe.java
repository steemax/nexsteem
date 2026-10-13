/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("nuget-proxy")
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class NugetProxyRecipe extends NugetRecipe {
 public NugetProxyRecipe(@org.springframework.beans.factory.annotation.Qualifier("proxy")org.sonatype.nexus.repository.Type type,@org.springframework.beans.factory.annotation.Qualifier("nuget")org.sonatype.nexus.repository.Format format){super(type,format);}
}

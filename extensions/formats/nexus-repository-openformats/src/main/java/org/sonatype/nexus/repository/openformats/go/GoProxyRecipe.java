/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.go;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("go-proxy") @org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class GoProxyRecipe extends org.sonatype.nexus.repository.openformats.OpenRecipe {
 public GoProxyRecipe(@org.springframework.beans.factory.annotation.Qualifier("proxy") org.sonatype.nexus.repository.Type type,@org.springframework.beans.factory.annotation.Qualifier("go") org.sonatype.nexus.repository.Format format,
 @org.springframework.beans.factory.annotation.Qualifier("go") jakarta.inject.Provider<org.sonatype.nexus.repository.openformats.OpenContentFacet> content,@org.springframework.beans.factory.annotation.Qualifier("go") jakarta.inject.Provider<org.sonatype.nexus.repository.openformats.OpenSecurityFacet> security) { super(type,format,content,security); }
}

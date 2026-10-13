/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum.yum;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("yum-hosted") @org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class YumHostedRecipe extends org.sonatype.nexus.repository.rubyyum.RubyYumRecipe {
 public YumHostedRecipe(@org.springframework.beans.factory.annotation.Qualifier("hosted") org.sonatype.nexus.repository.Type type,@org.springframework.beans.factory.annotation.Qualifier("yum") org.sonatype.nexus.repository.Format format,
 @org.springframework.beans.factory.annotation.Qualifier("yum") jakarta.inject.Provider<org.sonatype.nexus.repository.rubyyum.RubyYumContentFacet> content,@org.springframework.beans.factory.annotation.Qualifier("yum") jakarta.inject.Provider<org.sonatype.nexus.repository.rubyyum.RubyYumSecurityFacet> security) { super(type,format,content,security); }
}

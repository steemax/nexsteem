/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.gitlfs;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("gitlfs-hosted") @org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class GitlfsHostedRecipe extends org.sonatype.nexus.repository.openformats.OpenRecipe {
 public GitlfsHostedRecipe(@org.springframework.beans.factory.annotation.Qualifier("hosted") org.sonatype.nexus.repository.Type type,@org.springframework.beans.factory.annotation.Qualifier("gitlfs") org.sonatype.nexus.repository.Format format,
 @org.springframework.beans.factory.annotation.Qualifier("gitlfs") jakarta.inject.Provider<org.sonatype.nexus.repository.openformats.OpenContentFacet> content,@org.springframework.beans.factory.annotation.Qualifier("gitlfs") jakarta.inject.Provider<org.sonatype.nexus.repository.openformats.OpenSecurityFacet> security) { super(type,format,content,security); }
}

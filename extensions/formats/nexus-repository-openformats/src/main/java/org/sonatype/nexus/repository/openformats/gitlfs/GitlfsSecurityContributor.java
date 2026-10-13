/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.gitlfs;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("gitlfs") public class GitlfsSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public GitlfsSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("gitlfs") org.sonatype.nexus.repository.Format format) { super(format); } }

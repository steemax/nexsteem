/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum.yum;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("yum") public class YumSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public YumSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("yum") org.sonatype.nexus.repository.Format format) { super(format); } }

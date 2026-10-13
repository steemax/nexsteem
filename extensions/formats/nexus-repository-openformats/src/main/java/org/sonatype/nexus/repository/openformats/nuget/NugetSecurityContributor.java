/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("nuget") public class NugetSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public NugetSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("nuget") org.sonatype.nexus.repository.Format format) { super(format); } }

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.go;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("go") public class GoSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public GoSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("go") org.sonatype.nexus.repository.Format format) { super(format); } }

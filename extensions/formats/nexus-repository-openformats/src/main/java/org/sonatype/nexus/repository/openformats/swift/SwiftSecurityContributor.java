/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.swift;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("swift") public class SwiftSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public SwiftSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("swift") org.sonatype.nexus.repository.Format format) { super(format); } }

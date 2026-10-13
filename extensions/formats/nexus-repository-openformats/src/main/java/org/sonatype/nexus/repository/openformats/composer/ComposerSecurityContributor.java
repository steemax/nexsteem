/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.composer;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("composer") public class ComposerSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public ComposerSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("composer") org.sonatype.nexus.repository.Format format) { super(format); } }

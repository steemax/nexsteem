/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.huggingface;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("huggingface") public class HuggingfaceSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public HuggingfaceSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("huggingface") org.sonatype.nexus.repository.Format format) { super(format); } }

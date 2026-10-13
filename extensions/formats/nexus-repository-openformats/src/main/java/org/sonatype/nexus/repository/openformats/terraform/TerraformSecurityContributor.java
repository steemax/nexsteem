/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.terraform;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("terraform") public class TerraformSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public TerraformSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("terraform") org.sonatype.nexus.repository.Format format) { super(format); } }

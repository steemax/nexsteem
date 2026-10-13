/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.cargo;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("cargo") public class CargoSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public CargoSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("cargo") org.sonatype.nexus.repository.Format format) { super(format); } }

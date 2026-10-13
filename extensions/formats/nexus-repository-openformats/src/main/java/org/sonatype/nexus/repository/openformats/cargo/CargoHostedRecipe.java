/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.cargo;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("cargo-hosted") @org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class CargoHostedRecipe extends org.sonatype.nexus.repository.openformats.OpenRecipe {
 public CargoHostedRecipe(@org.springframework.beans.factory.annotation.Qualifier("hosted") org.sonatype.nexus.repository.Type type,@org.springframework.beans.factory.annotation.Qualifier("cargo") org.sonatype.nexus.repository.Format format,
 @org.springframework.beans.factory.annotation.Qualifier("cargo") jakarta.inject.Provider<org.sonatype.nexus.repository.openformats.OpenContentFacet> content,@org.springframework.beans.factory.annotation.Qualifier("cargo") jakarta.inject.Provider<org.sonatype.nexus.repository.openformats.OpenSecurityFacet> security) { super(type,format,content,security); }
}

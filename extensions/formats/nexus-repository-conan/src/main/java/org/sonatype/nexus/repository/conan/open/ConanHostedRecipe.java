/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("conan-hosted")
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class ConanHostedRecipe extends ConanRecipe {
 @org.springframework.beans.factory.annotation.Autowired public ConanHostedRecipe(@org.springframework.beans.factory.annotation.Qualifier("hosted")org.sonatype.nexus.repository.Type type,@org.springframework.beans.factory.annotation.Qualifier("conan")org.sonatype.nexus.repository.Format format){super(type,format);}
}

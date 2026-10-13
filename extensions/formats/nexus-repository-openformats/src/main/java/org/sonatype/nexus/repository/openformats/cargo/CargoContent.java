/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.cargo;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype") @org.springframework.beans.factory.annotation.Qualifier("cargo") public class CargoContent extends org.sonatype.nexus.repository.openformats.OpenContentFacet { public CargoContent(@org.springframework.beans.factory.annotation.Qualifier("cargo") org.sonatype.nexus.repository.content.store.FormatStoreManager store) { super(store); } }

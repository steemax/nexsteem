/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.composer;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype") @org.springframework.beans.factory.annotation.Qualifier("composer") public class ComposerContent extends org.sonatype.nexus.repository.openformats.OpenContentFacet { public ComposerContent(@org.springframework.beans.factory.annotation.Qualifier("composer") org.sonatype.nexus.repository.content.store.FormatStoreManager store) { super(store); } }

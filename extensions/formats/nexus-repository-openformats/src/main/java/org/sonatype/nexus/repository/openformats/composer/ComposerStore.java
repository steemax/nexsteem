/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.composer;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("composer") public class ComposerStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<ComposerContentRepositoryDAO,ComposerComponentDAO,ComposerAssetDAO,ComposerAssetBlobDAO,ComposerBrowseNodeDAO> { }

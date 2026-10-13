/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.cocoapods.datastore;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("cocoapods")
public class CocoapodsStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<CocoapodsContentRepositoryDAO,CocoapodsComponentDAO,CocoapodsAssetDAO,CocoapodsAssetBlobDAO,CocoapodsBrowseNodeDAO> {}

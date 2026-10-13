/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum.yum;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("yum") public class YumStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<YumContentRepositoryDAO,YumComponentDAO,YumAssetDAO,YumAssetBlobDAO,YumBrowseNodeDAO> { }

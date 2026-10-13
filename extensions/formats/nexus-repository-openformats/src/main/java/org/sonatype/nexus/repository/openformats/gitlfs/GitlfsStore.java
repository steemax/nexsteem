/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.gitlfs;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("gitlfs") public class GitlfsStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<GitlfsContentRepositoryDAO,GitlfsComponentDAO,GitlfsAssetDAO,GitlfsAssetBlobDAO,GitlfsBrowseNodeDAO> { }

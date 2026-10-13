/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("conan")
public class ConanStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<ConanContentRepositoryDAO,ConanComponentDAO,ConanAssetDAO,ConanAssetBlobDAO,ConanBrowseNodeDAO> {}

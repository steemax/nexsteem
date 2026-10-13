/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.terraform;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("terraform") public class TerraformStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<TerraformContentRepositoryDAO,TerraformComponentDAO,TerraformAssetDAO,TerraformAssetBlobDAO,TerraformBrowseNodeDAO> { }

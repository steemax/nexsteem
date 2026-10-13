/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.cargo;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("cargo") public class CargoStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<CargoContentRepositoryDAO,CargoComponentDAO,CargoAssetDAO,CargoAssetBlobDAO,CargoBrowseNodeDAO> { }

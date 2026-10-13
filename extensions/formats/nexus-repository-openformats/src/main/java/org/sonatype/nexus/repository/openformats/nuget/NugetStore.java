/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("nuget") public class NugetStore extends org.sonatype.nexus.repository.content.store.FormatStoreSupport<NugetContentRepositoryDAO,NugetComponentDAO,NugetAssetDAO,NugetAssetBlobDAO,NugetBrowseNodeDAO> { }

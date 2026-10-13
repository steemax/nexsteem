/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype") @org.springframework.beans.factory.annotation.Qualifier("nuget") @org.sonatype.nexus.repository.Facet.Exposed public class NugetContent extends org.sonatype.nexus.repository.openformats.OpenContentFacet { public NugetContent(@org.springframework.beans.factory.annotation.Qualifier("nuget") org.sonatype.nexus.repository.content.store.FormatStoreManager store) { super(store); }
 @Override protected String determineContentType(org.sonatype.nexus.repository.content.Asset asset,org.sonatype.nexus.blobstore.api.Blob blob,boolean strict){
  if("METADATA".equals(asset.kind()))return "application/json";
  return super.determineContentType(asset,blob,strict);
 }
}

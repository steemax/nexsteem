/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
import java.util.*;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.common.hash.HashAlgorithm;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class ConanContent extends ContentFacetSupport {
 @org.springframework.beans.factory.annotation.Autowired public ConanContent(@org.springframework.beans.factory.annotation.Qualifier("conan") FormatStoreManager manager){super(manager);}
 public Content get(String path){return assets().path(path).find().map(FluentAsset::download).orElse(null);}
 public Content put(String path,Payload payload,String name,String version){return put(path,payload,name,version,null);}
 public Content put(String path,Payload payload,String name,String version,String expectedSha1){try(TempBlob b=blobs().ingest(payload,List.of(HashAlgorithm.MD5,HashAlgorithm.SHA1,HashAlgorithm.SHA256))){if(expectedSha1!=null&&(!expectedSha1.matches("[a-fA-F0-9]{40}")||!b.getHashes().get(HashAlgorithm.SHA1).toString().equalsIgnoreCase(expectedSha1)))throw new IllegalArgumentException("SHA1 checksum mismatch");var asset=assets().path(path).kind(name==null&&!path.matches(".*/files/.+")?"CONAN_METADATA":"CONAN_FILE").blob(b);if(name!=null)asset.component(components().name(name).version(version).getOrCreate());return asset.save().markAsCached(payload).download();}}
 @Override protected String determineContentType(org.sonatype.nexus.repository.content.Asset asset,org.sonatype.nexus.blobstore.api.Blob blob,boolean strict){
  if("CONAN_METADATA".equals(asset.kind())){
   try(var input=blob.getInputStream()){byte[] bytes=input.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024||!ConanProtocol.JSON.readTree(bytes).isObject())throw new IllegalArgumentException("Invalid Conan metadata");return "application/json";}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
  }
  return super.determineContentType(asset,blob,strict);
 }
 public List<FluentAsset> under(String prefix){var result=new ArrayList<FluentAsset>();String token=null;do{var page=assets().byFilter("path LIKE #{filterParams.prefix} ESCAPE '!'",Map.of("prefix",prefix.replace("!","!!").replace("_","!_").replace("%","!%")+"%" )).browse(1000,token);result.addAll(page);token=page.isEmpty()?null:page.nextContinuationToken();}while(token!=null);return result;}
}

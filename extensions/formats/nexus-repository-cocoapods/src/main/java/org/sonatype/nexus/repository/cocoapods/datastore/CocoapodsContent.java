/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.cocoapods.datastore;
import java.util.*;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.common.hash.HashAlgorithm;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class CocoapodsContent extends ContentFacetSupport {
 @org.springframework.beans.factory.annotation.Autowired public CocoapodsContent(@org.springframework.beans.factory.annotation.Qualifier("cocoapods") FormatStoreManager manager){super(manager);}
 public Content get(String path){return assets().path(path).find().map(FluentAsset::download).orElse(null);}
 public Content put(String path,Payload payload,String kind){return put(path,payload,kind,null,null);}
 public Content put(String path,Payload payload,String kind,String name,String version){try(TempBlob b=blobs().ingest(payload,List.of(HashAlgorithm.SHA1,HashAlgorithm.SHA256))){var asset=assets().path(path).kind(kind).blob(b);if(name!=null)asset.component(components().name(name).version(version).getOrCreate());return asset.save().markAsCached(payload).download();}}
}

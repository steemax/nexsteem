/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import java.io.*;
import java.util.*;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.*;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.*;

/** Common SQL/blob persistence; protocol semantics belong to the individual handlers. */
@org.sonatype.nexus.repository.Facet.Exposed
public abstract class RubyYumContentFacet extends ContentFacetSupport {
 public RubyYumContentFacet(FormatStoreManager manager) { super(manager); }
 public Optional<Content> get(String path) { return assets().path(path).find().map(a -> a.markAsDownloaded().download()); }
 public Content put(String path, Payload payload, String kind, String name, String version) {
  try(TempBlob blob=blobs().ingest(payload,List.of(HashAlgorithm.SHA1,HashAlgorithm.SHA256,HashAlgorithm.SHA512))) {
   return putBlob(path,blob,payload,kind,name,version);
  }
 }
 public Content putBlob(String path,TempBlob blob,Payload payload,String kind,String name,String version) {
  var builder=assets().path(path).kind(kind).blob(blob);
  if(name!=null) builder.component(components().name(name).version(version==null?"":version).getOrCreate());
  return builder.save().markAsCached(payload).download();
 }
 public Content json(String path,Object data) throws IOException { return put(path,new BytesPayload(ProtocolSupport.JSON.writeValueAsBytes(data),"application/json"),"METADATA",null,null); }
 public byte[] bytes(String path,int limit) throws IOException { var content=get(path); return content.isEmpty()?null:ProtocolSupport.read(content.get(),limit); }
 public boolean delete(String path) { return assets().path(path).find().map(FluentAsset::delete).orElse(false); }
 public Iterable<FluentAsset> all() { return () -> new Iterator<FluentAsset>() {
  private Iterator<FluentAsset> page=Collections.emptyIterator();private String token;private boolean done;
  public boolean hasNext(){if(page.hasNext())return true;if(done)return false;var next=assets().browse(1000,token);if(next.isEmpty()){done=true;return false;}token=next.nextContinuationToken();done=token==null;page=next.iterator();return page.hasNext();}
  public FluentAsset next(){if(!hasNext())throw new NoSuchElementException();return page.next();}
 }; }
 @Override protected WritePolicy writePolicy(Asset asset) { return "METADATA".equals(asset.kind())?WritePolicy.ALLOW:super.writePolicy(asset); }
}

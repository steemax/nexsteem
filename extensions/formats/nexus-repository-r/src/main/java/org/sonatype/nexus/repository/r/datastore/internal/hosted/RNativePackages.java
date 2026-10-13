/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.r.datastore.internal.hosted;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.zip.GZIPOutputStream;
import org.sonatype.nexus.repository.r.*;
import org.sonatype.nexus.repository.r.datastore.internal.RContentFacet;
import org.sonatype.nexus.repository.r.internal.hosted.RPackageVersion;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.http.HttpResponses;
public final class RNativePackages {
 public static Response get(Context context)throws IOException {
  String path=context.getRequest().getPath();
  if(path.endsWith(".rds"))return HttpResponses.notFound();
  String directory=path.substring(0,path.lastIndexOf('/')+1);
  Map<String,Map<String,String>> latest=new TreeMap<>();
  var facet=context.getRepository().facet(RContentFacet.class);String token=null;
  do {
   var page=facet.assets().byKind("ARCHIVE").browse(1000,token);
   for(FluentAsset a:page){
    if(!a.path().startsWith(directory)||a.path().substring(directory.length()).contains("/"))continue;
    Map<String,String> fields=new LinkedHashMap<>();a.attributes().child(RFormat.NAME).backing().forEach((k,v)->{if(v!=null)fields.put(k,v.toString());});
    String name=fields.get("Package");if(name==null)continue;
    a.blob().ifPresent(b->{String md5=b.checksums().get(org.sonatype.nexus.common.hash.HashAlgorithm.MD5.name());if(md5!=null)fields.put("MD5sum",md5);});
    Map<String,String> old=latest.get(name);
    if(old==null||new RPackageVersion(fields.get("Version")).compareTo(new RPackageVersion(old.get("Version")))>0)latest.put(name,fields);
   }
   token=page.isEmpty()?null:page.nextContinuationToken();
  }while(token!=null);
  return HttpResponses.ok(render(latest.values(),path.endsWith(".gz")));
 }
 public static Payload render(Collection<Map<String,String>> entries,boolean gzip)throws IOException {
  StringBuilder text=new StringBuilder();
  for(var entry:entries){
   for(String key:List.of("Package","Version","Depends","Imports","Suggests","LinkingTo","License","NeedsCompilation","MD5sum","File")){
    String value=entry.get(key);if(value!=null&&!value.isBlank())text.append(key).append(": ").append(value).append('\n');
   }
   text.append('\n');
  }
  byte[] bytes=text.toString().getBytes(StandardCharsets.UTF_8);
  if(gzip){var out=new ByteArrayOutputStream();try(var gz=new GZIPOutputStream(out)){gz.write(bytes);}bytes=out.toByteArray();}
  return new BytesPayload(bytes,gzip?"application/x-gzip":"text/plain");
 }
}

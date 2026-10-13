/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.io.*;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.*;
import static org.sonatype.nexus.repository.rubyyum.ProtocolSupport.*;
import static org.sonatype.nexus.repository.rubyyum.YumCodec.*;

final class YumProtocol {
  static Response handle(Context context,RubyYumContentFacet store) throws Exception {
    String path=path(context),action=context.getRequest().getAction();
    if (action.equals("PUT")) return put(context,store,path);
    if (action.equals("DELETE")) {
      synchronized(store) {
        boolean existed=store.delete(path);
        if(path.endsWith(".rpm")) rebuild(store,scope(context,path));
        return response(existed?204:404,null);
      }
    }
    if(!List.of("GET","HEAD").contains(action)) return response(405,null);
    if(path.equals("/"))return text(200,"Nexus Yum repository\n","text/plain");
    if(path.endsWith("/repodata/repomd.xml") && store.get(path).isEmpty()) synchronized(store) {rebuild(store,path.substring(0,path.length()-"/repodata/repomd.xml".length()));}
    return store.get(path).map(c->response(200,c)).orElseGet(()->response(404,null));
  }
  private static Response put(Context context,RubyYumContentFacet store,String path) throws Exception {
    boolean rpm=path.endsWith(".rpm");
    boolean strict=!"PERMISSIVE".equals(String.valueOf(context.getRepository().getConfiguration().attributes("yum").get("deployPolicy")));
    if(!rpm && strict)return response(400,null);
    if(!rpm) {store.put(path,context.getRequest().getPayload(),"METADATA",null,null);return response(201,null);}
    String root=scope(context,path);
    if(context.getRequest().getPayload()==null)throw new IllegalArgumentException("RPM body is required");
    var temp=Files.createTempFile("nexus-yum-",".rpm");
    try {
      try(var in=context.getRequest().getPayload().openInputStream();var out=Files.newOutputStream(temp)) {
        byte[] b=new byte[65536];long size=0;int n;while((n=in.read(b))!=-1){if((size+=n)>4L*1024*1024*1024)throw new IllegalArgumentException("RPM exceeds 4 GiB limit");out.write(b,0,n);}
      }
      RpmMetadata metadata=RpmMetadataParser.parse(temp);
      String digest;try(var in=Files.newInputStream(temp)) {var hash=java.security.MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);digest=HexFormat.of().formatHex(hash.digest());}
      verifySha256(context,digest);
      synchronized(store) {
        store.put(path,new StreamPayload(()->Files.newInputStream(temp),Files.size(temp),"application/x-rpm"),"YUM_PACKAGE",metadata.name(),metadata.version()+"-"+metadata.release());
        store.assets().path(path).find().orElseThrow().withAttribute("rpmMetadata",metadata.toAttributes()).withAttribute("rpmSha256",digest).withAttribute("rpmSize",Files.size(temp)).withAttribute("rpmTime",Instant.now().getEpochSecond());
        rebuild(store,root);
      }
      return response(201,null);
    } finally {Files.deleteIfExists(temp);}
  }
  private static String scope(Context context,String path) {
    Object value=context.getRepository().getConfiguration().attributes("yum").get("repodataDepth");
    int depth=value==null?0:Integer.parseInt(value.toString());if(depth<0||depth>5)throw new IllegalArgumentException("Invalid repodataDepth");
    String[] parts=path.substring(1).split("/");if(parts.length<=depth)throw new IllegalArgumentException("RPM path is shallower than repodataDepth");
    return depth==0?"":"/"+String.join("/",Arrays.copyOf(parts,depth));
  }
  static void rebuild(RubyYumContentFacet store,String root) {
    var rpms=new ArrayList<RpmAsset>();
    for(var asset:store.all()) {
      if(!asset.path().startsWith(root+"/")||!asset.path().endsWith(".rpm"))continue;
      Object attrs=asset.attributes().get("rpmMetadata");if(!(attrs instanceof Map<?,?> map))continue;
      RpmMetadata metadata=RpmMetadata.fromAttributes((Map<String,Object>)map);
      rpms.add(new RpmAsset(asset.path().substring(root.length()+1),metadata,String.valueOf(asset.attributes().get("rpmSha256")),((Number)asset.attributes().get("rpmSize")).longValue(),Instant.ofEpochSecond(((Number)asset.attributes().get("rpmTime")).longValue())));
    }
    rpms.sort(Comparator.comparing(RpmAsset::path));
    var entries=new ArrayList<RepomdEntry>();
    for(var entry:Map.of("primary",primaryXml(rpms),"filelists",filelistsXml(rpms),"other",otherXml(rpms)).entrySet()) {
      MetadataFile file=gzipMetadata(entry.getValue());String path="repodata/"+file.fileName();
      store.put(root+"/"+path,new BytesPayload(file.bytes(),"application/gzip"),"METADATA",null,null);entries.add(new RepomdEntry(entry.getKey(),path,file));
    }
    store.put(root+"/repodata/repomd.xml",new BytesPayload(repomdXml(entries).getBytes(java.nio.charset.StandardCharsets.UTF_8),"application/xml"),"METADATA",null,null);
  }
}

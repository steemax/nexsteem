/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.cocoapods.datastore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sonatype.nexus.repository.cocoapods.internal.proxy.SpecFileProcessor;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
@org.springframework.stereotype.Component("openCocoapodsDownloads")
public class CocoapodsDownloads extends ProxyHandler {
 private static final ObjectMapper JSON=new ObjectMapper();
 @org.springframework.beans.factory.annotation.Autowired private SpecFileProcessor specs;
 @Override public Response handle(Context context)throws Exception {
  Response response=super.handle(context);
  if(response.getStatus().getCode()!=200||!context.getRequest().getPath().endsWith(".podspec.json")||response.getPayload()==null)return response;
  byte[] original;try(var input=response.getPayload().openInputStream()){original=input.readNBytes(4*1024*1024+1);if(original.length>4*1024*1024)throw new IOException("Podspec metadata too large");}
  ObjectNode spec=(ObjectNode)JSON.readTree(original);String url=specs.extractExternalUri(new String(original,StandardCharsets.UTF_8)).toString();
  String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));
  ObjectNode source=JSON.createObjectNode();source.put("http",context.getRepository().getUrl().replaceAll("/$","")+"/_source/"+key);
  JsonNode old=spec.path("source");for(String field:List.of("sha1","sha256","type","flatten"))if(old.has(field))source.set(field,old.get(field));
  if(!source.has("type")){
   String path=java.net.URI.create(url).getPath().toLowerCase(java.util.Locale.ROOT),type=null;
   if(old.has("git"))type="tgz";else if(path.endsWith(".zip"))type="zip";else if(path.endsWith(".tgz")||path.endsWith(".tar.gz"))type="tgz";else if(path.endsWith(".tar"))type="tar";else if(path.endsWith(".tbz")||path.endsWith(".tar.bz2"))type="tbz";else if(path.endsWith(".txz")||path.endsWith(".tar.xz"))type="txz";else if(path.endsWith(".dmg"))type="dmg";
   if(type!=null)source.put("type",type);
  }
  spec.set("source",source);byte[] rewritten=JSON.writeValueAsBytes(spec);
  Content content=new Content(new BytesPayload(rewritten,"application/json"));content.getAttributes().set(Content.CONTENT_ETAG,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rewritten)));
  return new Response.Builder().copy(response).payload(content).build();
 }
}

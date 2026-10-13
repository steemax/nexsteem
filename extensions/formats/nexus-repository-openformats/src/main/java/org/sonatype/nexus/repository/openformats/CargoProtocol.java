/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
public final class CargoProtocol {
 static final String NAME="[A-Za-z][A-Za-z0-9_-]{0,63}";
 static final String VERSION="[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?";
 public static String indexPath(String name) { String n=name.toLowerCase(Locale.ROOT);return "/"+(n.length()==1?"1/":n.length()==2?"2/":n.length()==3?"3/"+n.charAt(0)+"/":n.substring(0,2)+"/"+n.substring(2,4)+"/")+n; }
 static boolean valid(String path) { if(path.equals("/config.json")||path.equals("/api/v1/crates")||path.equals("/api/v1/crates/new"))return true; if(path.matches("/api/v1/crates/"+NAME+"(?:/owners|/"+VERSION+"/(download|yank|unyank))"))return true;String name=path.substring(path.lastIndexOf('/')+1);return name.matches(NAME)&&path.equals(indexPath(name)); }
 public static Response handle(Context c,OpenContentFacet store) throws Exception {
  String path=path(c),method=c.getRequest().getAction();
  if(path.equals("/config.json")&&(method.equals("GET")||method.equals("HEAD")))return json(200,Map.of("dl",base(c)+"/api/v1/crates/{crate}/{version}/download","api",base(c),"auth-required",c.getRepository().getConfiguration().attributes("cargo").get("requireAuthentication",Boolean.class,false)));
  if(!valid(path))return response(404,null);
  if(path.equals("/api/v1/crates/new")&&method.equals("PUT"))return publish(c,store);
  if(path.matches(".*/(yank|unyank)")&&(method.equals("PUT")||method.equals("DELETE"))) {
   var p=path.split("/");String idx=indexPath(p[4]); synchronized(store) {
    var lines=lines(store.bytes(idx,8*1024*1024));boolean found=false;
    for(var line:lines) if(line.path("vers").asText().equals(p[5])){((ObjectNode)line).put("yanked",path.endsWith("/yank"));found=true;}
    if(!found)return json(404,Map.of("errors",List.of(Map.of("detail","Crate version does not exist"))));
    store.put(idx,new BytesPayload(lines(lines),"text/plain"),"METADATA",null,null);
   }return json(200,Map.of("ok",true));
  }
  if(path.equals("/api/v1/crates")&&method.equals("GET")) {
   String q=c.getRequest().getParameters().get("q"); if(q==null)q="";var crates=new ArrayList<Object>();
   for(var a:store.all())if(a.path().startsWith("/_metadata/crates/")){var m=JSON.readTree(read(a.download(),1024*1024));if(m.path("name").asText().contains(q))crates.add(Map.of("id",m.path("name").asText(),"name",m.path("name").asText(),"max_version",m.path("vers").asText(),"description",m.path("description").asText("")));}
   return json(200,Map.of("crates",crates,"meta",Map.of("total",crates.size())));
  }
  if(path.endsWith("/owners"))return json(200,Map.of("users",List.of()));
  if(!method.equals("GET")&&!method.equals("HEAD"))return response(405,null);
  String target=path.endsWith("/download")?"/crates/"+path.split("/")[4]+"/"+path.split("/")[5]+".crate":path;
  return store.get(target).map(p->response(200,p)).orElseGet(()->response(404,null));
 }
 private static Response publish(Context c,OpenContentFacet store) throws Exception {
  byte[] body=read(c.getRequest().getPayload(),128*1024*1024);if(body.length<8)throw new IllegalArgumentException("Invalid Cargo publish body");
  var buffer=ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);int metadata=buffer.getInt();if(metadata<2||metadata>1024*1024||metadata>body.length-8)throw new IllegalArgumentException("Invalid metadata length");
  byte[] encoded=new byte[metadata];buffer.get(encoded);JsonNode m=JSON.readTree(encoded);int archive=buffer.getInt();if(archive<1||archive!=buffer.remaining())throw new IllegalArgumentException("Invalid crate length");byte[] crate=new byte[archive];buffer.get(crate);
  String name=require(m,"name",NAME),version=require(m,"vers",VERSION),artifact="/crates/"+name+"/"+version+".crate";
  validateArchive(crate,name,version);
  synchronized(store) {
   var previous=lines(store.bytes(indexPath(name),8*1024*1024));for(var line:previous)if(line.path("vers").asText().equals(version))return json(409,Map.of("errors",List.of(Map.of("detail","Crate version already exists"))));
   ObjectNode index=JSON.createObjectNode();index.put("name",name);index.put("vers",version);index.put("cksum",sha256(crate));index.put("yanked",false);index.set("features",m.path("features").isObject()?m.get("features"):JSON.createObjectNode());
   ArrayNode deps=index.putArray("deps");for(var dep:m.path("deps")){var d=dep.deepCopy();((ObjectNode)d).set("req",dep.path("version_req"));((ObjectNode)d).remove("version_req");if(dep.hasNonNull("explicit_name_in_toml")){((ObjectNode)d).put("name",dep.path("explicit_name_in_toml").asText());((ObjectNode)d).put("package",dep.path("name").asText());}((ObjectNode)d).remove("explicit_name_in_toml");deps.add(d);}
   for(String field:List.of("links","rust_version"))if(m.has(field))index.set(field,m.get(field));previous.add(index);
   store.put(artifact,new BytesPayload(crate,"application/octet-stream"),"CRATE",name,version);
   try {store.put(indexPath(name),new BytesPayload(lines(previous),"text/plain"),"METADATA",null,null);store.json("/_metadata/crates/"+name,m);}catch(Exception e){store.delete(artifact);throw e;}
  }
  return json(200,Map.of("warnings",Map.of("invalid_categories",List.of(),"invalid_badges",List.of(),"other",List.of())));
 }
 static void validateArchive(byte[] data,String name,String version) throws IOException {
  String prefix=name+"-"+version+"/";boolean manifest=false;long bytes=0;int count=0;
  try(var tar=new TarArchiveInputStream(new GzipCompressorInputStream(new ByteArrayInputStream(data)))) {
   for(var entry=tar.getNextTarEntry();entry!=null;entry=tar.getNextTarEntry()) {
    if(++count>100000||!entry.getName().startsWith(prefix)||entry.getName().contains("../")||entry.isSymbolicLink()||entry.isLink())throw new IllegalArgumentException("Unsafe crate archive");
    bytes+=entry.getSize();if(bytes>512L*1024*1024)throw new IllegalArgumentException("Unpacked crate exceeds limit");
    if(entry.getName().equals(prefix+"Cargo.toml"))manifest=true;
   }
  }if(!manifest)throw new IllegalArgumentException("Cargo.toml missing from crate");
 }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import com.fasterxml.jackson.databind.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
public final class SwiftProtocol {
 static final String ID="[A-Za-z0-9][A-Za-z0-9-]{0,99}";
 static boolean valid(String p){return p.equals("/identifiers")||p.matches("/"+ID+"/"+ID+"(?:/"+CargoProtocol.VERSION+"(?:\\.(?:zip|json)|/Package(?:@swift-[0-9.]+)?\\.swift)?)?");}
 public static Response handle(Context c,OpenContentFacet store)throws Exception {
  String p=path(c),method=c.getRequest().getAction();if(!valid(p))return result(response(404,null));String[] parts=p.split("/");
  if(p.equals("/identifiers"))return result(json(200,Map.of("identifiers",List.of())));
  if(parts.length==4&&method.equals("PUT"))return publish(c,store,p,parts);
  if(!List.of("GET","HEAD").contains(method))return result(response(405,null));
  if(parts.length==3){var releases=new TreeMap<String,Object>();for(var a:store.all())if(a.path().startsWith(p+"/")&&a.path().endsWith(".json")){String version=a.path().substring(p.length()+1,a.path().length()-5);releases.put(version,Map.of("url",base(c)+p+"/"+version));}return result(json(200,Map.of("releases",releases)));}
  String target=p.endsWith(".zip")||p.endsWith(".swift")||p.endsWith(".json")?p:p+".json";
  var content=store.get(target);if(content.isEmpty())return result(response(404,null));
  if(target.endsWith(".json")) {
   JsonNode m=JSON.readTree(read(content.get(),1024*1024));String release=p.replaceFirst("\\.json$", "");var resources=(com.fasterxml.jackson.databind.node.ArrayNode)m.path("resources");
   for(var resource:resources)((com.fasterxml.jackson.databind.node.ObjectNode)resource).put("url",base(c)+release+".zip");return result(json(200,m));
  }
  return result(response(200,content.get()));
 }
 static Response result(Response r){var builder=new Response.Builder().copy(r);builder.headers().replace("Content-Version","1");if(r.getPayload()!=null&&"application/json".equals(r.getPayload().getContentType()))builder.headers().replace("Content-Type","application/json");return builder.build();}
 private static Response publish(Context c,OpenContentFacet store,String p,String[] parts)throws Exception {
  if(!c.getRequest().isMultipart())throw new IllegalArgumentException("Swift publishing requires multipart source-archive");
  byte[] archive=null,signature=null;JsonNode metadata=JSON.createObjectNode();
  for(var part:c.getRequest().getMultiparts()){byte[] bytes=read(part,128*1024*1024);if("base64".equalsIgnoreCase(part.getHeader("Content-Transfer-Encoding")))bytes=Base64.getMimeDecoder().decode(bytes);
   switch(part.getFieldName()){case "source-archive":archive=bytes;break;case "metadata":metadata=JSON.readTree(bytes);break;case "source-archive-signature":signature=bytes;break;default:break;}}
  if(!metadata.isObject())throw new IllegalArgumentException("Swift metadata must be an object");
  if(archive==null)throw new IllegalArgumentException("source-archive required");var manifests=manifests(archive);if(!manifests.containsKey("Package.swift"))throw new IllegalArgumentException("Package.swift missing from Swift archive");
  synchronized(store){if(store.get(p+".json").isPresent())return result(json(409,Map.of("detail","Release already exists")));
   var resource=new LinkedHashMap<String,Object>();resource.put("name","source-archive");resource.put("type","application/zip");resource.put("checksum",sha256(archive));resource.put("url",base(c)+p+".zip");
   if(signature!=null){String format=c.getRequest().getHeaders().get("X-Swift-Package-Signature-Format");if(!"cms-1.0.0".equals(format))throw new IllegalArgumentException("Unsupported Swift signature format");resource.put("signing",Map.of("signatureBase64Encoded",Base64.getEncoder().encodeToString(signature),"signatureFormat",format));}
   store.put(p+".zip",new BytesPayload(archive,"application/zip"),"SWIFT_SOURCE",parts[1]+"."+parts[2],parts[3]);
   for(var entry:manifests.entrySet())store.put(p+"/"+entry.getKey(),new BytesPayload(entry.getValue(),"text/x-swift"),"SWIFT_MANIFEST",parts[1]+"."+parts[2],parts[3]);
   store.json(p+".json",Map.of("id",parts[1]+"."+parts[2],"version",parts[3],"resources",List.of(resource),"metadata",metadata,"publishedAt",java.time.Instant.now().toString()));
  }
  return new Response.Builder().copy(result(json(201,Map.of("id",parts[1]+"."+parts[2],"version",parts[3])))).header("Location",base(c)+p).build();
 }
 static Map<String,byte[]> manifests(byte[] archive)throws IOException {
  var found=new HashMap<String,byte[]>();long size=0;int count=0;
  try(var zip=new ZipInputStream(new ByteArrayInputStream(archive))){for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry()){
   String name=e.getName();if(++count>100000||name.startsWith("/")||name.contains("../")||name.contains("\\"))throw new IllegalArgumentException("Unsafe Swift ZIP");
   var out=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=zip.read(b))!=-1;){size+=n;if(size>512L*1024*1024)throw new IllegalArgumentException("Swift source exceeds size limit");if(name.matches("(?:[^/]+/)?Package(?:@swift-[0-9.]+)?\\.swift"))out.write(b,0,n);}
   if(out.size()>1024*1024)throw new IllegalArgumentException("Swift manifest exceeds limit");if(out.size()>0)found.put(name.substring(name.lastIndexOf('/')+1),out.toByteArray());
  }}return found;
 }
}

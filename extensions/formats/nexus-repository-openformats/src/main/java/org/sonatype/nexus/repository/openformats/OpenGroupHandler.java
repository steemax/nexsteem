/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.sonatype.nexus.repository.group.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.springframework.stereotype.Component;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
@Component
public class OpenGroupHandler extends GroupHandler {
 @Override protected Response doGet(Context c,DispatchedRepositories dispatched)throws Exception {
  String format=c.getRepository().getFormat().getValue(),p=path(c);boolean merge=format.equals("go")&&p.endsWith("/@v/list")||format.equals("cargo")&&!p.startsWith("/api/")||format.equals("swift")&&p.split("/").length==3||format.equals("terraform")&&p.endsWith("/versions");
  if(!merge){Response first=getFirst(c,c.getRepository().facet(GroupFacet.class).members(),dispatched); if(first.getStatus().isSuccessful()&&first.getPayload()!=null&&(format.equals("swift")&&!p.startsWith("/_remote/")&&!p.endsWith(".zip")&&!p.endsWith(".swift")||format.equals("terraform")&&p.contains("/download/"))){JsonNode metadata=JSON.readTree(read(first.getPayload(),16*1024*1024));rewriteMemberUrls(c,metadata);return format.equals("swift")?SwiftProtocol.result(json(200,metadata)):json(200,metadata);}if(format.equals("terraform")&&p.startsWith("/v1/modules/")&&p.endsWith("/download")&&first.getStatus().getCode()==204){String target=first.getHeaders().get("X-Terraform-Get");for(var member:c.getRepository().facet(GroupFacet.class).members()){String prefix=member.getUrl().replaceAll("/$","");if(target!=null&&target.startsWith(prefix+"/")){var rewritten=new Response.Builder().copy(first);rewritten.headers().replace("X-Terraform-Get",base(c)+target.substring(prefix.length()));return rewritten.build();}}}return first;}
  var responses=getAll(c,c.getRepository().facet(GroupFacet.class).members(),dispatched);var records=new LinkedHashMap<String,JsonNode>();var text=new TreeSet<String>();boolean found=false;
  for(var response:responses.values()) {
   if(!response.getStatus().isSuccessful()||response.getPayload()==null)continue;found=true;byte[] data=read(response.getPayload(),16*1024*1024);
   if(format.equals("go")){for(String line:new String(data,java.nio.charset.StandardCharsets.UTF_8).split("\n"))if(!line.isBlank())text.add(line);}
   else if(format.equals("cargo")){for(var line:lines(data))records.putIfAbsent(line.path("vers").asText(),line);}
   else if(format.equals("swift")){JSON.readTree(data).path("releases").fields().forEachRemaining(entry->records.putIfAbsent(entry.getKey(),entry.getValue()));}
   else {var root=JSON.readTree(data);JsonNode versions=root.has("modules")?root.path("modules").path(0).path("versions"):root.path("versions");for(var version:versions){String key=version.path("version").asText();if(records.containsKey(key)&&version.has("platforms")){var existing=(ArrayNode)records.get(key).path("platforms");for(var platform:version.path("platforms")){boolean exists=false;for(var e:existing)if(e.equals(platform))exists=true;if(!exists)existing.add(platform);}}else records.putIfAbsent(key,version);}}
  }
  if(!found)return response(404,null);
  if(format.equals("go"))return text(200,String.join("\n",text)+"\n","text/plain");
  if(format.equals("cargo"))return response(200,new BytesPayload(lines(records.values()),"text/plain"));
  if(format.equals("swift")){records.forEach((version,node)->{if(node.isObject())((ObjectNode)node).put("url",base(c)+p+"/"+version);});return SwiftProtocol.result(json(200,Map.of("releases",records)));}
  return json(200,p.startsWith("/v1/modules/")?Map.of("modules",List.of(Map.of("versions",records.values()))):Map.of("versions",records.values()));
 }
 private void rewriteMemberUrls(Context c,JsonNode node){if(node.isObject()){var object=(ObjectNode)node;var keys=new ArrayList<String>();object.fieldNames().forEachRemaining(keys::add);for(String key:keys){var value=object.get(key);if(value.isTextual()){String text=value.asText();for(var member:c.getRepository().facet(GroupFacet.class).members()){String prefix=member.getUrl().replaceAll("/$","");if(text.startsWith(prefix+"/")){object.put(key,base(c)+text.substring(prefix.length()));break;}}}else rewriteMemberUrls(c,value);}}else if(node.isArray())for(var child:node)rewriteMemberUrls(c,child);}
}

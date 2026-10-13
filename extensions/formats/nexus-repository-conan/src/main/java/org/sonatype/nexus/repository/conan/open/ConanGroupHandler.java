/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.sonatype.nexus.repository.group.*;
import org.sonatype.nexus.repository.view.*;
@org.springframework.stereotype.Component
public class ConanGroupHandler extends GroupHandler {
 @Override protected Response doGet(Context c,DispatchedRepositories dispatched)throws Exception{
  String path=c.getRequest().getPath();
  if(path.matches(".*/files/.+"))return super.doGet(c,dispatched);
  List<JsonNode> documents=new ArrayList<>();
  for(var response:getAll(c,c.getRepository().facet(GroupFacet.class).members(),dispatched).values()){
   if(response.getStatus().getCode()==200&&response.getPayload()!=null)documents.add(ConanProtocol.JSON.readTree(ConanProtocol.read(response.getPayload(),8*1024*1024)));
  }
  if(documents.isEmpty())return notFoundResponse(c);
  if(path.endsWith("/latest"))return ConanProtocol.json(documents.stream().max(Comparator.comparing(n->n.path("time").asText())).orElseThrow());
  if(path.endsWith("/revisions")){
   Map<String,JsonNode> revisions=new TreeMap<>();for(var d:documents)for(var r:d.path("revisions"))revisions.putIfAbsent(r.path("revision").asText(),r);
   return ConanProtocol.json(Map.of("revisions",revisions.values().stream().sorted(Comparator.comparing(n->n.path("time").asText())).toList()));
  }
  if(path.equals("/v2/conans/search")){
   Set<String> refs=new TreeSet<>();for(var d:documents)for(var r:d.path("results"))refs.add(r.asText());return ConanProtocol.json(Map.of("results",refs));
  }
  Map<String,JsonNode> merged=new TreeMap<>();
  for(var d:documents){var object=path.endsWith("/files")?d.path("files"):d;object.fields().forEachRemaining(e->merged.putIfAbsent(e.getKey(),e.getValue()));}
  return ConanProtocol.json(path.endsWith("/files")?Map.of("files",merged):merged);
 }
}

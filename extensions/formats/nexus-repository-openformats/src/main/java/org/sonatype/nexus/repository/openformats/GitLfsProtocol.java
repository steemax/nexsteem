/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.util.*;
import org.apache.shiro.SecurityUtils;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
public final class GitLfsProtocol {
 static final String OID="[a-f0-9]{64}";
 static boolean valid(String p){return p.equals("/info/lfs/objects/batch")||p.equals("/objects/batch")||p.matches("(?:/info/lfs)?/objects/"+OID+"(?:/verify)?")||p.matches("(?:/info/lfs)?/locks(?:/verify|/[^/]+/unlock)?");}
 public static Response handle(Context c,OpenContentFacet store) throws Exception {
  String p=path(c).replaceFirst("^/info/lfs", ""),method=c.getRequest().getAction();
  if(p.equals("/objects/batch")&&method.equals("POST")) {
   var req=json(c.getRequest().getPayload());String operation=req.path("operation").asText();if(!List.of("download","upload").contains(operation))throw new IllegalArgumentException("Unknown LFS operation");
   if(operation.equals("upload")&&!SecurityUtils.getSubject().isPermitted("nexus:repository-view:gitlfs:"+c.getRepository().getName()+":edit"))return json(403,Map.of("message","Upload is forbidden"));
   var result=new ArrayList<Object>();if(!req.path("objects").isArray()||req.path("objects").size()>1000)throw new IllegalArgumentException("Invalid LFS object list");
   for(var item:req.path("objects")) {
    String oid=require(item,"oid",OID);long size=item.path("size").asLong(-1);if(size<0)throw new IllegalArgumentException("Invalid LFS size");
    var object=new LinkedHashMap<String,Object>();object.put("oid",oid);object.put("size",size);object.put("authenticated",true);
    var stored=store.get("/objects/"+oid);boolean present=stored.isPresent()&&stored.get().getSize()==size;
    if(operation.equals("download")&&!present)object.put("error",Map.of("code",404,"message","Object does not exist"));
    else if(operation.equals("download"))object.put("actions",Map.of("download",Map.of("href",base(c)+"/objects/"+oid)));
    else if(!present)object.put("actions",Map.of("upload",Map.of("href",base(c)+"/objects/"+oid),"verify",Map.of("href",base(c)+"/objects/"+oid+"/verify")));
    result.add(object);
   }return new Response.Builder().copy(json(200,Map.of("transfer","basic","objects",result))).header("Content-Type","application/vnd.git-lfs+json").build();
  }
  if(p.matches("/objects/"+OID+"/verify")&&method.equals("POST")) {var m=json(c.getRequest().getPayload());String oid=require(m,"oid",OID);if(!p.equals("/objects/"+oid+"/verify"))return json(422,Map.of("message","Object identity mismatch"));var stored=store.get("/objects/"+oid);return stored.isPresent()&&stored.get().getSize()==m.path("size").asLong(-1)?response(200,null):json(422,Map.of("message","Object size or digest mismatch"));}
  if(p.matches("/objects/"+OID)) {
   if(method.equals("GET")||method.equals("HEAD"))return store.get(p).map(a->response(200,a)).orElseGet(()->response(404,null));
   if(method.equals("PUT"))try(TempBlob blob=store.blobs().ingest(c.getRequest().getPayload(),List.of(HashAlgorithm.SHA256))) {
    if(!blob.getHashes().get(HashAlgorithm.SHA256).toString().equals(p.substring(9)))return json(422,Map.of("message","Object digest mismatch"));
    synchronized(store){if(store.get(p).isEmpty())store.putBlob(p,blob,c.getRequest().getPayload(),"LFS_OBJECT",p.substring(9),"1");}return response(200,null);
   }
  }
  if(p.startsWith("/locks"))return locks(c,store,p,method);
  return response(404,null);
 }
 private static Response locks(Context c,OpenContentFacet store,String p,String method)throws Exception {
  synchronized(store) {
   var bytes=store.bytes("/_metadata/locks.json",8*1024*1024);var locks=bytes==null?JSON.createArrayNode():(com.fasterxml.jackson.databind.node.ArrayNode)JSON.readTree(bytes);
   String owner=String.valueOf(SecurityUtils.getSubject().getPrincipal());
   if(p.equals("/locks")&&method.equals("POST")){var request=json(c.getRequest().getPayload());String path=request.path("path").asText("");if(path.isBlank()||path.startsWith("/")||path.contains("../"))throw new IllegalArgumentException("Invalid lock path");for(var lock:locks)if(lock.path("path").asText().equals(path))return json(409,Map.of("message","Already locked","lock",lock));var lock=JSON.valueToTree(Map.of("id",UUID.randomUUID().toString(),"path",path,"locked_at",java.time.Instant.now().toString(),"owner",Map.of("name",owner)));locks.add(lock);store.json("/_metadata/locks.json",locks);return json(201,Map.of("lock",lock));}
   if(p.equals("/locks/verify")&&method.equals("POST")){var ours=JSON.createArrayNode();var theirs=JSON.createArrayNode();for(var lock:locks)(lock.path("owner").path("name").asText().equals(owner)?ours:theirs).add(lock);return json(200,Map.of("ours",ours,"theirs",theirs,"next_cursor",""));}
   if(p.matches("/locks/[^/]+/unlock")&&method.equals("POST")){String id=p.split("/")[2];for(int i=0;i<locks.size();i++)if(locks.get(i).path("id").asText().equals(id)){var lock=locks.get(i);if(!lock.path("owner").path("name").asText().equals(owner)&&!SecurityUtils.getSubject().isPermitted("nexus:*"))return json(403,Map.of("message","Lock belongs to another user"));locks.remove(i);store.json("/_metadata/locks.json",locks);return json(200,Map.of("lock",lock));}return response(404,null);}
   if(p.equals("/locks")&&method.equals("GET"))return json(200,Map.of("locks",locks,"next_cursor",""));
  }return response(405,null);
 }
}

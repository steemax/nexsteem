/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.group.GroupHandler;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.openformats.nuget.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
@Component
public class NugetProtocol implements Handler {
 @Autowired private ProxyHandler downloads;
 @Autowired private NugetKeyManager keys;
 @Autowired @Qualifier("default")private GroupHandler first;
 @Override public Response handle(Context c)throws Exception{
  try{
   String p=path(c).replaceAll("/+$",""),method=c.getRequest().getAction(),type=c.getRepository().getType().getValue();var store=c.getRepository().facet(NugetContent.class);
   if((p.equals("/index.json")||p.equals("/v3/index.json"))&&Set.of("GET","HEAD").contains(method))return service(c);
   if(!valid(p))return response(404,null);
   if(!type.equals("hosted")){
    if(!Set.of("GET","HEAD").contains(method))return response(405,null);
    if(type.equals("proxy")){var result=downloads.handle(c);return result.getStatus().getCode()==200&&result.getPayload()!=null?c.getRepository().facet(NugetProxy.class).outgoing(c,result):result;}
    if(p.endsWith(".nupkg")||p.endsWith(".nuspec")||p.endsWith(".sha512"))return first.handle(c);
    return grouped(c,p);
   }
   if(p.equals("/v2/package")&&(method.equals("PUT")||method.equals("POST"))){if(!keys.valid(apiKey(c)))return response(401,null);return publish(c,store);}
   if(p.startsWith("/v2/package/")&&Set.of("DELETE","POST").contains(method)){
    if(!keys.valid(apiKey(c)))return response(401,null);
    String[] parts=p.split("/");if(parts.length!=5)return response(404,null);String id=NugetPackage.id(parts[3]),v=NugetPackage.version(parts[4]);byte[] data=store.bytes(metadata(id,v),4*1024*1024);if(data==null)return response(404,null);ObjectNode catalog=(ObjectNode)JSON.readTree(data);catalog.put("listed",method.equals("POST"));saveMetadata(store,id,v,catalog);return response(method.equals("POST")?200:204,null);
   }
   if(!Set.of("GET","HEAD").contains(method))return response(405,null);
   if(p.startsWith("/v2/package/")){String[] parts=p.split("/");if(parts.length!=5)return response(404,null);var data=store.get(packagePath(NugetPackage.id(parts[3]),NugetPackage.version(parts[4])));return data.isEmpty()?response(404,null):response(200,data.get());}
   if(p.startsWith("/v3-flatcontainer/")){
    String[] parts=p.split("/");if(parts.length==4&&parts[3].equals("index.json")){var packages=packages(store,NugetPackage.id(parts[2]));return packages.isEmpty()?response(404,null):json(200,Map.of("versions",versions(packages)));}
    if(parts.length==5){String id=NugetPackage.id(parts[2]),v=NugetPackage.version(parts[3]);String tail=parts[4];if(!tail.equals(id+"."+v+".nupkg")&&!tail.equals(id+"."+v+".nupkg.sha512")&&!tail.equals(id+".nuspec"))return response(404,null);var content=store.get(p);return content.isEmpty()?response(404,null):response(200,content.get());}
   }
   if(p.startsWith("/registration/")){
    String[] parts=p.split("/");String id=NugetPackage.id(parts[2]);var packages=packages(store,id);if(packages.isEmpty())return response(404,null);
    if(parts.length==4&&parts[3].equals("index.json"))return registration(c,id,packages);
    if(parts.length==4&&parts[3].endsWith(".json")){String v=NugetPackage.version(parts[3].substring(0,parts[3].length()-5));var found=packages.stream().filter(n->n.path("version").asText().equals(v)).findFirst();return found.isEmpty()?response(404,null):json(200,leaf(c,id,found.get()));}
   }
   if(p.equals("/query"))return search(c,packages(store,null));
   return response(404,null);
  }catch(IllegalArgumentException e){return json(400,Map.of("message",e.getMessage()==null?"Invalid NuGet request":e.getMessage()));}
 }
 static boolean valid(String p){return p.equals("/v2/package")||p.startsWith("/v2/package/")||p.equals("/query")||p.matches("/(?:v3-flatcontainer|registration)/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.+-]+){1,3}")||p.matches("/_remote/[a-f0-9]{64}");}
 static String metadata(String id,String v){return "/_metadata/nuget/"+id+"/"+v+".json";}
 static String packagePath(String id,String v){return "/v3-flatcontainer/"+id+"/"+v+"/"+id+"."+v+".nupkg";}
 static String packageUrl(Context c,String id,String v){return base(c)+packagePath(id,v);}
 private static String apiKey(Context c){String value=null;for(String name:c.getRequest().getHeaders().names())if(name.equalsIgnoreCase("X-NuGet-ApiKey"))for(String entry:c.getRequest().getHeaders().getAll(name)){if(value!=null)return null;value=entry;}return value;}
 private Response service(Context c)throws IOException{
  var resources=new ArrayList<Map<String,String>>();for(String type:List.of("RegistrationsBaseUrl/3.0.0","RegistrationsBaseUrl/3.6.0","RegistrationsBaseUrl/Versioned","RegistrationsBaseUrl/3.4.0"))resources.add(Map.of("@id",base(c)+"/registration/","@type",type));
  resources.add(Map.of("@id",base(c)+"/v3-flatcontainer/","@type","PackageBaseAddress/3.0.0"));for(String type:List.of("SearchQueryService/3.5.0","SearchQueryService/Versioned","SearchQueryService/3.4.0","SearchQueryService/3.0.0-beta","SearchQueryService/3.0.0-rc","SearchQueryService"))resources.add(Map.of("@id",base(c)+"/query","@type",type));
  if(c.getRepository().getType().getValue().equals("hosted"))resources.add(Map.of("@id",base(c)+"/v2/package","@type","PackagePublish/2.0.0"));return json(200,Map.of("version","3.0.0","resources",resources));
 }
 private Response publish(Context c,NugetContent store)throws Exception{
  Payload payload=c.getRequest().getPayload();if(c.getRequest().isMultipart()){payload=null;for(var part:c.getRequest().getMultiparts())if(!part.isFormField()){payload=part;break;}}if(payload==null)throw new IllegalArgumentException("NuGet package body required");
  if(payload.getSize()>512L*1024*1024)return json(413,Map.of("message","NuGet package exceeds maximum size"));
  final Payload original=payload;
  final Payload source=new Payload(){
   public long getSize(){return original.getSize();}public String getContentType(){return original.getContentType();}
   public InputStream openInputStream()throws IOException{return new FilterInputStream(original.openInputStream()){
    long size;private void count(int n)throws IOException{if(n>0&&(size+=n)>512L*1024*1024)throw new IOException("NuGet package exceeds maximum size");}
    public int read()throws IOException{int value=in.read();count(value<0?0:1);return value;}
    public int read(byte[] buffer,int off,int len)throws IOException{int value=in.read(buffer,off,len);count(value);return value;}
   };}
  };
  try(var blob=store.blobs().ingest(source,List.of(HashAlgorithm.SHA1,HashAlgorithm.SHA256,HashAlgorithm.SHA512))){
   NugetPackage.Metadata pkg;try{pkg=NugetPackage.parse(blob);}catch(java.io.IOException|org.xml.sax.SAXException|javax.xml.parsers.ParserConfigurationException e){return json(400,Map.of("message","Invalid NuGet package archive or manifest"));}String id=pkg.lowerId(),v=pkg.version(),path=packagePath(id,v);
   synchronized(store){if(store.get(path).isPresent())return response(409,null);
    String hash=Base64.getEncoder().encodeToString(blob.getHashes().get(HashAlgorithm.SHA512).asBytes());
    return org.sonatype.nexus.transaction.Transactional.operation.withStore(store).throwing(Exception.class).call(()->{
     store.putBlob(path,blob,source,"NUGET_PACKAGE",pkg.id(),v);store.put("/v3-flatcontainer/"+id+"/"+v+"/"+id+".nuspec",new BytesPayload(pkg.nuspec(),"application/xml"),"NUGET_MANIFEST",pkg.id(),v);store.put(path+".sha512",new BytesPayload(hash.getBytes(StandardCharsets.US_ASCII),"text/plain"),"NUGET_CHECKSUM",pkg.id(),v);
     ObjectNode catalog=JSON.valueToTree(pkg.catalog());catalog.put("packageHash",hash);catalog.put("packageHashAlgorithm","SHA512");saveMetadata(store,id,v,catalog);return response(201,null);
    });
   }
  }
 }
 static void saveMetadata(NugetContent store,String id,String v,JsonNode catalog)throws IOException{store.put(metadata(id,v),new BytesPayload(JSON.writeValueAsBytes(catalog),"application/json"),"METADATA",catalog.path("id").asText(),v);}
 static List<JsonNode> packages(NugetContent store,String id)throws IOException{
  var result=new ArrayList<JsonNode>();String prefix="/_metadata/nuget/"+(id==null?"":id+"/");for(var asset:store.all())if(asset.path().startsWith(prefix)&&asset.path().endsWith(".json"))result.add(JSON.readTree(read(asset.download(),4*1024*1024)));result.sort((one,two)->NugetVersions.compare(one.path("version").asText(),two.path("version").asText()));return result;
 }
 static List<String> versions(List<JsonNode> entries){return entries.stream().map(n->n.path("version").asText()).distinct().sorted(NugetVersions::compare).toList();}
 static Map<String,Object> leaf(Context c,String id,JsonNode node){String v=NugetPackage.version(node.path("version").asText());var catalog=((ObjectNode)node).deepCopy();catalog.put("@id",base(c)+"/registration/"+id+"/"+v+".json#catalog");for(var group:catalog.path("dependencyGroups"))for(var dep:group.path("dependencies"))((ObjectNode)dep).put("registration",base(c)+"/registration/"+NugetPackage.id(dep.path("id").asText())+"/index.json");return Map.of("@id",base(c)+"/registration/"+id+"/"+v+".json","catalogEntry",catalog,"packageContent",packageUrl(c,id,v),"listed",node.path("listed").asBoolean(true),"published",node.path("published").asText(),"registration",base(c)+"/registration/"+id+"/index.json");}
 static Response registration(Context c,String id,List<JsonNode> entries)throws IOException{
  var leaves=entries.stream().map(n->leaf(c,id,n)).toList();return json(200,Map.of("@id",base(c)+"/registration/"+id+"/index.json","count",1,"items",List.of(Map.of("@id",base(c)+"/registration/"+id+"/index.json","count",leaves.size(),"lower",entries.get(0).path("version").asText(),"upper",entries.get(entries.size()-1).path("version").asText(),"items",leaves))));
 }
 private Response search(Context c,List<JsonNode> entries)throws IOException{
  String q=Objects.toString(c.getRequest().getParameters().get("q"),"").toLowerCase(Locale.ROOT),packageType=Objects.toString(c.getRequest().getParameters().get("packageType"),"");boolean pre=Boolean.parseBoolean(c.getRequest().getParameters().get("prerelease")),semVer2="2.0.0".equals(c.getRequest().getParameters().get("semVerLevel"));int skip=integer(c,"skip",0,0,Integer.MAX_VALUE),take=integer(c,"take",20,0,1000);Map<String,List<JsonNode>> ids=new TreeMap<>();
  for(var n:entries){String id=NugetPackage.id(n.path("id").asText());if(!n.path("listed").asBoolean(true)||!pre&&n.path("version").asText().contains("-")||!semVer2&&n.path("semVer2").asBoolean(false))continue;if(!packageType.isBlank()){boolean type=false;for(var t:n.path("packageTypes"))if(packageType.equalsIgnoreCase(t.path("name").asText()))type=true;if(!type)continue;}String text=(id+" "+n.path("title").asText()+" "+n.path("description").asText()+" "+n.path("tags").asText()).toLowerCase(Locale.ROOT);boolean matches=q.startsWith("packageid:")?id.equals(q.substring(10)):Arrays.stream(q.split("\\s+")).allMatch(text::contains);if(matches)ids.computeIfAbsent(id,ignored->new ArrayList<>()).add(n);}
  var output=new ArrayList<Object>();for(var e:ids.entrySet()){var sorted=e.getValue();sorted.sort((x,y)->NugetVersions.compare(x.path("version").asText(),y.path("version").asText()));var latest=(ObjectNode)sorted.get(sorted.size()-1).deepCopy();latest.put("@id",base(c)+"/registration/"+e.getKey()+"/index.json");latest.put("registration",base(c)+"/registration/"+e.getKey()+"/index.json");latest.put("totalDownloads",0);ArrayNode versions=latest.putArray("versions");for(var n:sorted)versions.addObject().put("version",n.path("version").asText()).put("downloads",0).put("@id",base(c)+"/registration/"+e.getKey()+"/"+n.path("version").asText()+".json");output.add(latest);}
  int from=Math.min(skip,output.size()),to=Math.min(output.size(),from+take);return json(200,Map.of("totalHits",output.size(),"data",output.subList(from,to)));
 }
 private static int integer(Context c,String name,int fallback,int min,int max){String value=c.getRequest().getParameters().get(name);int result=value==null?fallback:Integer.parseInt(value);if(result<min||result>max)throw new IllegalArgumentException("Invalid NuGet pagination");return result;}
 private Response grouped(Context c,String p)throws Exception{
  List<JsonNode> entries=new ArrayList<>();Map<String,JsonNode> seen=new LinkedHashMap<>();
  if(p.startsWith("/v3-flatcontainer/")&&p.endsWith("/index.json")){Set<String> versions=new TreeSet<>(NugetVersions::compare);for(var member:c.getRepository().facet(GroupFacet.class).members()){var response=member.facet(ViewFacet.class).dispatch(new Request.Builder().copy(c.getRequest()).build(),c);if(response.getStatus().getCode()==200&&response.getPayload()!=null)for(var v:JSON.readTree(read(response.getPayload(),16*1024*1024)).path("versions"))versions.add(v.asText());}return versions.isEmpty()?response(404,null):json(200,Map.of("versions",versions));}
  if(p.startsWith("/registration/")){
   String id=NugetPackage.id(p.split("/")[2]);for(var member:c.getRepository().facet(GroupFacet.class).members()){var response=member.facet(ViewFacet.class).dispatch(new Request.Builder().copy(c.getRequest()).path("/registration/"+id+"/index.json").build(),c);if(response.getStatus().getCode()==200&&response.getPayload()!=null)collect(c,member,JSON.readTree(read(response.getPayload(),16*1024*1024)),seen,new HashSet<>());}
   entries.addAll(seen.values());entries.sort((x,y)->NugetVersions.compare(x.path("version").asText(),y.path("version").asText()));if(entries.isEmpty())return response(404,null);if(p.endsWith("/index.json"))return registration(c,id,entries);String v=NugetPackage.version(p.substring(p.lastIndexOf('/')+1,p.length()-5));return seen.containsKey(v)?json(200,leaf(c,id,seen.get(v))):response(404,null);
  }
  if(p.equals("/query")){
   int skip=integer(c,"skip",0,0,3000),take=integer(c,"take",20,0,1000),window=skip+Math.max(1,take),duplicates=0;long totalHits=0;
   // Only retrieve the requested window. Exhausting a large public feed for each search is unbounded work.
   for(var member:c.getRepository().facet(GroupFacet.class).members())for(int offset=0;offset<window;){
    int count=Math.min(1000,window-offset);Parameters parameters=new Parameters();for(String name:c.getRequest().getParameters().names())parameters.set(name,c.getRequest().getParameters().getAll(name));parameters.replace("skip",Integer.toString(offset));parameters.replace("take",Integer.toString(count));
    var result=member.facet(ViewFacet.class).dispatch(new Request.Builder().copy(c.getRequest()).parameters(parameters).build(),c);if(result.getStatus().getCode()!=200||result.getPayload()==null)break;JsonNode response=JSON.readTree(read(result.getPayload(),16*1024*1024)),page=response.path("data");if(offset==0)totalHits+=response.path("totalHits").asLong(page.size());
    for(var n:page){String id=NugetPackage.id(n.path("id").asText());JsonNode previous=seen.get(id);if(previous==null)seen.put(id,n);else{
     duplicates++;ObjectNode merged=(ObjectNode)(NugetVersions.compare(n.path("version").asText(),previous.path("version").asText())>0?n:previous).deepCopy();var versions=new TreeMap<String,JsonNode>(NugetVersions::compare);for(var v:previous.path("versions"))versions.put(v.path("version").asText(),v);for(var v:n.path("versions"))versions.putIfAbsent(v.path("version").asText(),v);ArrayNode output=merged.putArray("versions");versions.values().forEach(output::add);seen.put(id,merged);
    }}if(page.size()<count)break;offset+=count;
   }
   List<JsonNode> results=seen.values().stream().map(n->{var copy=(ObjectNode)n.deepCopy();String id=NugetPackage.id(n.path("id").asText());copy.put("@id",base(c)+"/registration/"+id+"/index.json");copy.put("registration",base(c)+"/registration/"+id+"/index.json");for(var v:copy.path("versions"))((ObjectNode)v).put("@id",base(c)+"/registration/"+id+"/"+v.path("version").asText()+".json");return (JsonNode)copy;}).toList();int from=Math.min(skip,results.size()),to=Math.min(results.size(),from+take);return json(200,Map.of("totalHits",Math.max(results.size(),totalHits-duplicates),"data",results.subList(from,to)));
  }
  return response(404,null);
 }
 private void collect(Context c,Repository member,JsonNode node,Map<String,JsonNode> entries,Set<String> pages)throws Exception{
  if(node.path("catalogEntry").isObject()){collect(c,member,node.path("catalogEntry"),entries,pages);return;}
  if(node.path("id").isTextual()&&node.path("version").isTextual()){entries.putIfAbsent(NugetPackage.version(node.path("version").asText()),node);return;}
  if(node.path("catalogEntry").isTextual()){collectReference(c,member,node.path("catalogEntry").asText(),entries,pages);return;}
  for(var child:node.path("items")){
   if(child.has("items")||child.has("catalogEntry"))collect(c,member,child,entries,pages);else if(child.path("@id").isTextual()){
    collectReference(c,member,child.path("@id").asText(),entries,pages);
   }
  }
 }
 private void collectReference(Context c,Repository member,String url,Map<String,JsonNode> entries,Set<String> pages)throws Exception{
  String path=java.net.URI.create(url).getPath(),prefix="/repository/"+member.getName();if(!path.startsWith(prefix+"/")||!pages.add(path)||pages.size()>10000)throw new IllegalArgumentException("Invalid registration page");var response=member.facet(ViewFacet.class).dispatch(new Request.Builder().copy(c.getRequest()).action("GET").path(path.substring(prefix.length())).build(),c);if(response.getStatus().getCode()==200&&response.getPayload()!=null)collect(c,member,JSON.readTree(read(response.getPayload(),16*1024*1024)),entries,pages);
 }
}

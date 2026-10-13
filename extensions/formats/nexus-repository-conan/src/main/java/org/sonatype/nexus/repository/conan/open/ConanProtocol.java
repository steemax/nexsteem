/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.*;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.springframework.beans.factory.annotation.Autowired;
@org.springframework.stereotype.Component
public class ConanProtocol implements Handler {
 static final ObjectMapper JSON=new ObjectMapper();
 @Autowired private ConanTokenManager tokens;
 @Autowired private ProxyHandler downloads;
 @Autowired private ConanGroupHandler groups;
 static byte[] read(Payload payload,int limit)throws IOException {if(payload==null)throw new IllegalArgumentException("Body required");try(var in=payload.openInputStream()){byte[] bytes=in.readNBytes(limit+1);if(bytes.length>limit)throw new IllegalArgumentException("Metadata too large");return bytes;}}
 static Response json(Object body)throws IOException {return HttpResponses.ok(new BytesPayload(JSON.writeValueAsBytes(body),"application/json"));}
 static Response text(String text){return HttpResponses.ok(new BytesPayload(text.getBytes(StandardCharsets.UTF_8),"text/plain"));}
 static boolean files(String path){return path.matches("/v2/conans/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+/[A-Za-z0-9_.+-]+/revisions/[a-f0-9]{32,64}/(?:packages/[a-f0-9]{32,64}/revisions/[a-f0-9]{32,64}/)?files/.+");}
 static String checksum(Context context){String value=null;for(String name:context.getRequest().getHeaders().names())if(name.equalsIgnoreCase("X-Checksum-Sha1"))for(String entry:context.getRequest().getHeaders().getAll(name)){if(value!=null)return "";value=entry;}return value;}
 @Override public Response handle(Context context)throws Exception {
  String path=context.getRequest().getPath(), method=context.getRequest().getAction();
  if(path.contains("\\")||path.contains("\0")||path.matches(".*(^|/)\\.\\.?(/|$).*"))return HttpResponses.badRequest("Invalid path");
  if(path.equals("/v1/ping")||path.equals("/v2/ping"))return new Response.Builder().status(new Status(true,200)).header("X-Conan-Server-Capabilities","revisions").header("X-Conan-Server-Version","2.0").header("X-Conan-Server-Type","Nexus Open").build();
  if(path.equals("/v2/users/authenticate")||path.equals("/v1/users/authenticate"))return text(tokens.token());
  if(path.equals("/v2/users/check_credentials")||path.equals("/v1/users/check_credentials"))return text(tokens.username());
  String type=context.getRepository().getType().getValue();
  if(!"hosted".equals(type)){
   if(!Set.of("GET","HEAD").contains(method))return HttpResponses.methodNotAllowed(method,"GET","HEAD");
   return "group".equals(type)?groups.handle(context):downloads.handle(context);
  }
  ConanContent store=context.getRepository().facet(ConanContent.class);
  if(files(path)){
   if(method.equals("PUT")){
    String[] parts=path.split("/");String name=parts[3]+"@"+parts[5]+"/"+parts[6];
    try{store.put(path,context.getRequest().getPayload(),name,parts[4],checksum(context));}catch(IllegalArgumentException e){return HttpResponses.badRequest(e.getMessage());}return HttpResponses.ok();
   }
   if(Set.of("GET","HEAD").contains(method)){Content data=store.get(path);return data==null?HttpResponses.notFound():HttpResponses.ok(data);}
  }
  if(method.equals("DELETE")&&path.startsWith("/v2/conans/")){
   var assets=store.under(path+"/");if(assets.isEmpty())return HttpResponses.notFound();assets.forEach(FluentAsset::delete);return HttpResponses.ok();
  }
  if(!Set.of("GET","HEAD").contains(method))return HttpResponses.methodNotAllowed(method,"GET","HEAD","PUT","DELETE");
  if(path.equals("/v2/conans/search")){
   String query=context.getRequest().getParameters().get("q");
   Pattern matches=wildcard(query==null?"*":query);Set<String> refs=new TreeSet<>();
   for(var a:store.under("/v2/conans/")){
    String[] parts=a.path().split("/");if(parts.length<11||!a.path().endsWith("/files/conanmanifest.txt")||a.path().contains("/packages/"))continue;
    String ref=parts[3]+"/"+parts[4]+(parts[5].equals("_")&&parts[6].equals("_")?"":"@"+parts[5]+"/"+parts[6]);if(matches.matcher(ref).matches())refs.add(ref);
   }
   return json(Map.of("results",refs));
  }
  if(path.endsWith("/files")){
   var files=new TreeMap<String,Object>();String prefix=path+"/";
   for(var a:store.under(prefix))files.put(a.path().substring(prefix.length()),Map.of());
   return files.isEmpty()?HttpResponses.notFound():json(Map.of("files",files));
  }
  if(path.endsWith("/revisions")||path.endsWith("/latest")){
   String prefix=path.endsWith("/latest")?path.substring(0,path.length()-7)+"/revisions/":path+"/";
   List<Map<String,String>> revisions=revisions(store,prefix);
   if(revisions.isEmpty())return HttpResponses.notFound();
   return json(path.endsWith("/latest")?revisions.get(revisions.size()-1):Map.of("revisions",revisions));
  }
  if(path.endsWith("/search")&&path.startsWith("/v2/conans/")){
   String prefix=path.substring(0,path.length()-7);Map<String,Object> packages=new TreeMap<>();
   if(!prefix.contains("/revisions/")){
    var revs=revisions(store,prefix+"/revisions/");if(revs.isEmpty())return HttpResponses.notFound();prefix+="/revisions/"+revs.get(revs.size()-1).get("revision");
   }
   prefix+="/packages/";
   for(var a:store.under(prefix)){
    if(!a.path().endsWith("/files/conaninfo.txt"))continue;
    String id=a.path().substring(prefix.length()).split("/")[0];
    packages.put(id,packageInfo(new String(read(a.download(),1024*1024),StandardCharsets.UTF_8)));
   }
   return json(packages);
  }
  return HttpResponses.notFound();
 }
 private static Pattern wildcard(String expression){if(expression.length()>1024)throw new IllegalArgumentException("Search too large");String regex=Arrays.stream(expression.split("\\*",-1)).map(Pattern::quote).collect(java.util.stream.Collectors.joining(".*"));return Pattern.compile(regex,Pattern.CASE_INSENSITIVE);}
 private static List<Map<String,String>> revisions(ConanContent store,String prefix){
  var records=new TreeMap<String,OffsetDateTime>();
  for(var a:store.under(prefix)){
   String rest=a.path().substring(prefix.length());String[] parts=rest.split("/");
   if(parts.length==3&&parts[1].equals("files")&&parts[2].equals("conanmanifest.txt"))records.merge(parts[0],a.lastUpdated(),(one,two)->one.isAfter(two)?one:two);
  }
  return records.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(e->Map.of("revision",e.getKey(),"time",e.getValue().toInstant().toString())).toList();
 }
 private static Map<String,Object> packageInfo(String text){
  Map<String,String> settings=new TreeMap<>(),options=new TreeMap<>();List<String> requires=new ArrayList<>();String section="";
  for(String line:text.split("\\r?\\n")){
   line=line.strip();if(line.startsWith("[")&&line.endsWith("]")){section=line.substring(1,line.length()-1);continue;}
   if(line.isEmpty())continue;
   if(section.equals("requires")){requires.add(line);continue;}
   int split=line.indexOf('=');if(split>0&&Set.of("settings","options").contains(section))(section.equals("settings")?settings:options).put(line.substring(0,split),line.substring(split+1));
  }
  return Map.of("settings",settings,"options",options,"requires",requires);
 }
}

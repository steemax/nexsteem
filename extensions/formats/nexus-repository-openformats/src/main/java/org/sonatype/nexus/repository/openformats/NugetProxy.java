/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.openformats.nuget.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
@Component @Scope("prototype") @org.sonatype.nexus.repository.Facet.Exposed
public class NugetProxy extends ContentProxyFacetSupport {
 private NugetContent content(){return facet(NugetContent.class);}
 @org.springframework.beans.factory.annotation.Autowired private org.sonatype.nexus.extensions.http.ScopedProxyRequests scopedRequests;
 @Override protected Content getCachedContent(Context c)throws IOException{return content().get(key(c)).orElse(null);}
 @Override protected Content store(Context c,Content payload)throws IOException{
  String p=path(c);Content result;
  if(binary(c)){
   String id=null,version=null;String[] parts=p.split("/");
   if(p.startsWith("/v3-flatcontainer/")&&parts.length==5){id=NugetPackage.id(parts[2]);version=NugetPackage.version(parts[3]);}
   else if(p.startsWith("/_remote/")){byte[] mapping=content().bytes("/_metadata/nuget-remote"+p,8192);if(mapping!=null){JsonNode m=JSON.readTree(mapping);id=m.path("id").asText(null);version=m.path("version").asText(null);}}
   result=content().put(key(c),payload,"NUGET_PACKAGE",id,version);
  }
  else{JsonNode data=JSON.readTree(read(payload,16*1024*1024));if(data==null)throw new IOException("Invalid NuGet JSON metadata");result=content().put(key(c),new BytesPayload(JSON.writeValueAsBytes(data),"application/json"),"METADATA",null,null);}
  content().assets().path(key(c)).find().ifPresent(asset->asset.markAsCached(payload));return content().get(key(c)).orElse(result);
 }
 @Override protected String getUrl(Context c){
  String p=path(c);
  try{
   if(p.startsWith("/_remote/")){byte[] mapping=content().bytes("/_metadata/nuget-remote"+p,8192);return mapping==null?null:JSON.readTree(mapping).path("url").asText();}
   if(p.startsWith("/v3-flatcontainer/"))return resource("PackageBaseAddress/3.0.0")+p.substring("/v3-flatcontainer/".length());
   if(p.startsWith("/registration/"))return registration()+p.substring("/registration/".length());
   if(p.equals("/query"))return resource("SearchQueryService/3.5.0","SearchQueryService/Versioned","SearchQueryService/3.4.0","SearchQueryService/3.0.0","SearchQueryService/3.0.0-rc","SearchQueryService/3.0.0-beta","SearchQueryService");
   return null;
  }catch(IOException e){throw new java.io.UncheckedIOException(e);}
 }
 private String indexUrl(){String configured=getRepository().getConfiguration().attributes("proxy").require("remoteUrl",String.class).replaceAll("/+$","");return configured.endsWith("index.json")?configured:configured+"/index.json";}
 private JsonNode index()throws IOException{
  String path="/_metadata/nuget-service-index.json",originPath="/_metadata/nuget-service-origin.json";
  byte[] origin=content().bytes(originPath,8192);boolean same=origin!=null&&JSON.readTree(origin).path("url").asText().equals(indexUrl());
  Content saved=same?content().get(path).orElse(null):null;
  if(saved!=null){var info=saved.getAttributes().get(org.sonatype.nexus.repository.cache.CacheInfo.class);if(info!=null&&!cacheControllerHolder.getMetadataCacheController().isStale(info))return JSON.readTree(read(saved,4*1024*1024));}
  // Bootstrap metadata through the ordinary Nexus fetch path (SSL, SSRF, timeout and repository authentication).
  Context context=new Context(getRepository(),new Request.Builder().action("GET").path("/v3/index.json").build());
  Content result=fetch(indexUrl(),context,saved);if(result==null)throw new IOException("NuGet upstream service index unavailable");JsonNode node=JSON.readTree(read(result,4*1024*1024));if(!node.path("resources").isArray())throw new IOException("NuGet V3 service index missing resources");content().json(path,node);content().assets().path(path).find().ifPresent(asset->asset.markAsCached(result).markAsCached(cacheControllerHolder.getMetadataCacheController().current()));content().json(originPath,Map.of("url",indexUrl()));return node;
 }
 private static boolean type(JsonNode resource,String wanted){var field=resource.path("@type");if(field.isTextual())return field.asText().equals(wanted);for(var t:field)if(t.asText().equals(wanted))return true;return false;}
 private String resource(String...types)throws IOException{for(String wanted:types)for(var r:index().path("resources"))if(type(r,wanted)){URI uri=URI.create(indexUrl()).resolve(r.path("@id").asText());if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IOException("Invalid NuGet resource URL");return uri.toString();}throw new IOException("NuGet resource unavailable: "+types[0]);}
 private String registration()throws IOException{return resource("RegistrationsBaseUrl/3.6.0","RegistrationsBaseUrl/3.4.0","RegistrationsBaseUrl/3.0.0","RegistrationsBaseUrl/Versioned");}
 public Response outgoing(Context c,Response response)throws IOException{
  if(c.getRequest().getAction().equals("HEAD")||binary(c))return response;JsonNode tree=JSON.readTree(read(response.getPayload(),16*1024*1024));rewrite(c,tree,resource("PackageBaseAddress/3.0.0"),registration());Content rewritten=new Content(new BytesPayload(JSON.writeValueAsBytes(tree),"application/json"));return new Response.Builder().copy(response).payload(rewritten).build();
 }
 private void rewrite(Context c,JsonNode node,String flat,String registration)throws IOException{
  if(node.isObject()){var obj=(ObjectNode)node;var names=new ArrayList<String>();obj.fieldNames().forEachRemaining(names::add);for(String field:names){var value=obj.get(field);if(value.isTextual()&&Set.of("@id","registration","packageContent","catalogEntry").contains(field)){String url=value.asText();if(url.startsWith("http://")||url.startsWith("https://"))obj.put(field,mapped(c,url,field,obj,flat,registration));}else rewrite(c,value,flat,registration);}}
  else if(node.isArray())for(var item:node)rewrite(c,item,flat,registration);
 }
 private String mapped(Context c,String url,String field,JsonNode parent,String flat,String registration)throws IOException{
  if(url.startsWith(flat))return base(c)+"/v3-flatcontainer/"+url.substring(flat.length());if(url.startsWith(registration))return base(c)+"/registration/"+url.substring(registration.length());
  URI target=URI.create(url);if(!Set.of("http","https").contains(target.getScheme())||target.getHost()==null||target.getUserInfo()!=null)throw new IOException("Invalid NuGet metadata URL");String fragment=target.getRawFragment(),fetchUrl=fragment==null?url:url.substring(0,url.indexOf('#'));String path="/_remote/"+sha256(fetchUrl.getBytes(StandardCharsets.UTF_8));var mapping=new LinkedHashMap<String,Object>();mapping.put("url",fetchUrl);boolean binary=field.equals("packageContent")||target.getPath().endsWith(".nupkg")||target.getPath().endsWith(".nuspec");mapping.put("binary",binary);
  if(binary){JsonNode catalog=parent.path("catalogEntry").isObject()?parent.path("catalogEntry"):parent;String id=catalog.path("id").asText(null),v=catalog.path("version").asText(null);String[] parts=path(c).split("/");if(id==null&&parts.length>=4&&parts[1].equals("registration")){id=parts[2];if(!parts[3].equals("index.json"))v=parts[3].replaceFirst("\\.json$","");}if(id!=null&&v!=null){mapping.put("id",NugetPackage.id(id));mapping.put("version",NugetPackage.version(v));}}
  content().json("/_metadata/nuget-remote"+path,mapping);return base(c)+path+(fragment==null?"":"#"+fragment);
 }
 private static boolean binary(String p){return p.endsWith(".nupkg")||p.endsWith(".nuspec")||p.endsWith(".sha512");}
 private boolean binary(Context c)throws IOException{String p=path(c);if(binary(p))return true;if(p.startsWith("/_remote/")){byte[] data=content().bytes("/_metadata/nuget-remote"+p,8192);return data!=null&&JSON.readTree(data).path("binary").asBoolean(false);}return false;}
 private String key(Context c)throws IOException{String p=path(c);var params=c.getRequest().getParameters();if(params.isEmpty())return p;var values=new TreeMap<String,List<String>>();for(String name:params.names())values.put(name,params.getAll(name));return p+"?"+sha256(JSON.writeValueAsBytes(values));}
 @Override protected HttpRequestBase buildFetchHttpRequest(URI uri,Context c,Content stale){try{URIBuilder builder=new URIBuilder(uri);if(path(c).equals("/query"))for(String name:c.getRequest().getParameters().names())for(String value:c.getRequest().getParameters().getAll(name))builder.addParameter(name,value);return super.buildFetchHttpRequest(builder.build(),c,stale);}catch(URISyntaxException e){throw new IllegalArgumentException("Invalid NuGet query",e);}}
 @Override protected org.sonatype.nexus.repository.cache.CacheController getCacheController(Context c){try{return binary(c)?cacheControllerHolder.getContentCacheController():cacheControllerHolder.getMetadataCacheController();}catch(IOException e){throw new java.io.UncheckedIOException(e);}}
 @Override protected HttpResponse execute(Context context,HttpClient client,HttpRequestBase request)throws IOException{
  return scopedRequests.execute(context,client,request,getConfiguration().getRemoteURL(),
      facet(org.sonatype.nexus.repository.httpclient.HttpClientFacet.class).getBearerToken());
 }
}

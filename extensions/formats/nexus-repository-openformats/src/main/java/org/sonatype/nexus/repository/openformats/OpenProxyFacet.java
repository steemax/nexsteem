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
import org.sonatype.nexus.extensions.http.ScopedProxyRequests;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
@Component @Scope("prototype")
public class OpenProxyFacet extends ContentProxyFacetSupport {
 @org.springframework.beans.factory.annotation.Autowired private ScopedProxyRequests requests;
 private OpenContentFacet content(){return getRepository().facet(OpenContentFacet.class);}
 @Override protected Content getCachedContent(Context c) throws IOException {return content().get(cacheKey(c)).orElse(null);}
 @Override protected Content store(Context c,Content payload) throws IOException {
  String p=path(c),type=payload.getContentType();String format=getRepository().getFormat().getValue();boolean json=type!=null&&(type.contains("json")||type.contains("vnd.swift"))||p.endsWith(".json")||format.equals("terraform")&&p.contains("/download/")||format.equals("swift")&&!p.endsWith(".zip")&&!p.endsWith(".swift");
  Content result;
  if(json&&!p.startsWith("/_remote/")&&!(format.equals("huggingface")&&!p.startsWith("/api/"))) {
   JsonNode tree=JSON.readTree(read(payload,16*1024*1024));rewrite(c,tree);result=content().put(cacheKey(c),new BytesPayload(JSON.writeValueAsBytes(tree),type==null?"application/json":type),"METADATA",null,null);
  } else {
   String name=metadata(p)?null:componentName(format,p),version=componentVersion(format,p);
   if(p.startsWith("/_remote/")){byte[] mapping=content().bytes("/_metadata/upstream"+p,8192);if(mapping!=null){JsonNode coordinates=JSON.readTree(mapping);name=coordinates.path("componentName").asText(null);version=coordinates.path("componentVersion").asText("");}}
   if(format.equals("huggingface")&&!metadata(p)){String commit=remoteHeaders(c).get("X-Repo-Commit");if(commit!=null)version=commit;}
   result=content().put(cacheKey(c),payload,metadata(p)?"METADATA":"PACKAGE",name,version);
  }
  content().assets().path(cacheKey(c)).find().ifPresent(asset->{asset.markAsCached(payload);if(json&&!p.startsWith("/_remote/"))asset.withAttribute("openMetadataBase",base(c));});
  result=content().get(cacheKey(c)).orElse(result);
  // Preserve protocol headers in addition to the standard validators stored by Nexus.
  var headers=new LinkedHashMap<String,String>();HttpResponse remote=c.getAttributes().get(HTTP_RESPONSE,HttpResponse.class);
  if(remote!=null)for(String key:List.of("X-Repo-Commit","X-Linked-ETag","X-Linked-Size","Content-Version","X-Terraform-Get","Link")){Header value=remote.getFirstHeader(key);if(value!=null){String text=value.getValue();if(key.equals("X-Terraform-Get"))text=mapped(c,resolve(text));headers.put(key,text);}}
  if(!headers.isEmpty())content().assets().path(cacheKey(c)).find().ifPresent(asset->asset.withAttribute("openProtocolHeaders",headers));
  return content().get(cacheKey(c)).orElse(result);
 }
 @Override protected String getUrl(Context c) {
  String p=path(c);
  try {
   if(p.startsWith("/_remote/")){byte[] data=content().bytes("/_metadata/upstream"+p,8192);if(data==null)return null;return JSON.readTree(data).path("url").asText();}
   if(getRepository().getFormat().getValue().equals("cargo")&&p.equals("/api/v1/crates")){String api=cargoConfig(c).path("api").asText();return api.isBlank()?null:api.replaceAll("/$","")+p;}
   if(getRepository().getFormat().getValue().equals("cargo")&&p.endsWith("/download")) {
    JsonNode config=cargoConfig(c);String[] parts=p.split("/");String name=parts[4],version=parts[5],template=config.path("dl").asText();
    if(template.isBlank())return null;
    if(!template.contains("{"))template=template.replaceAll("/$","")+"/"+name+"/"+version+"/download";
    return resolve(template.replace("{crate}",name).replace("{version}",version).replace("{prefix}",prefix(name)).replace("{lowerprefix}",prefix(name.toLowerCase(Locale.ROOT))));
   }
   if(getRepository().getFormat().getValue().equals("composer")&&p.startsWith("/p2/")) {
    byte[] config=content().bytes("/_metadata/composer-template.json",8192);if(config!=null){String pattern=JSON.readTree(config).path("url").asText();return pattern.replace("%package%",p.substring(4).replaceFirst("\\.json$", ""));}
   }
  }catch(IOException e){throw new IllegalStateException("Cannot resolve upstream metadata",e);}
  return p.substring(1);
 }
 private String prefix(String name){return name.length()==1?"1":name.length()==2?"2":name.length()==3?"3/"+name.charAt(0):name.substring(0,2)+"/"+name.substring(2,4);}
 private JsonNode cargoConfig(Context c)throws IOException {
  byte[] saved=content().bytes("/_metadata/cargo-upstream.json",1024*1024);if(saved!=null)return JSON.readTree(saved);
  Content config=fetch("config.json",c,null);if(config==null)throw new IllegalArgumentException("Cargo upstream config.json unavailable");JsonNode tree=JSON.readTree(read(config,1024*1024));content().json("/_metadata/cargo-upstream.json",tree);return tree;
 }
 @Override protected Content build3xxResponseContent(Context c,URI uri,Content stale,HttpResponse response,org.sonatype.nexus.repository.cache.CacheInfo info,boolean unmodified)throws org.sonatype.nexus.repository.proxy.ProxyServiceException,IOException {
  if(getRepository().getFormat().getValue().equals("terraform")&&path(c).startsWith("/v1/modules/")&&path(c).endsWith("/download")&&response.getStatusLine().getStatusCode()==204){Header target=response.getFirstHeader("X-Terraform-Get");if(target==null)throw new IOException("Terraform module download header missing");Content metadata=new Content(new BytesPayload(JSON.writeValueAsBytes(Map.of("openModuleDownload",mapped(c,resolve(target.getValue()))+"?archive=tar.gz")),"application/json"));metadata.getAttributes().set(org.sonatype.nexus.repository.cache.CacheInfo.class,info);return metadata;}return super.build3xxResponseContent(c,uri,stale,response,info,unmodified);
 }
 public static Map<String,String> remoteHeaders(Context c){var result=new LinkedHashMap<String,String>();HttpResponse response=c.getAttributes().get(HTTP_RESPONSE,HttpResponse.class);if(response!=null)for(String name:List.of("X-Repo-Commit","X-Linked-ETag","X-Linked-Size","Content-Version")){Header header=response.getFirstHeader(name);if(header!=null)result.put(name,header.getValue());}return result;}
 private String resolve(String url){return resolveTemplate(getRemoteUrl(),url);}
 public String mapped(Context c,String url)throws IOException {String format=getRepository().getFormat().getValue();return mapped(c,url,componentName(format,path(c)),componentVersion(format,path(c)));}
 private String mapped(Context c,String url,String name,String version)throws IOException {
  URI uri=URI.create(url);if(!Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Invalid upstream artifact URL");
  // Only URLs found in metadata returned by the configured upstream can acquire a mapping.
  // Requests cannot supply destinations; Nexus additionally validates resolved/private addresses before fetching.
  String path="/_remote/"+sha256(url.getBytes(StandardCharsets.UTF_8));var coordinates=new LinkedHashMap<String,Object>();coordinates.put("url",url);if(name!=null&&version!=null&&!version.isBlank()){coordinates.put("componentName",name);coordinates.put("componentVersion",version);}content().json("/_metadata/upstream"+path,coordinates);return base(c)+path;
 }
 private void rewrite(Context c,JsonNode node)throws IOException {String format=getRepository().getFormat().getValue();rewrite(c,node,componentName(format,path(c)),componentVersion(format,path(c)));}
 private void rewrite(Context c,JsonNode node,String name,String version)throws IOException {
  if(node.isObject()) {
   ObjectNode object=(ObjectNode)node;if(object.hasNonNull("version")&&(object.hasNonNull("name")||object.hasNonNull("id"))){name=object.path(object.hasNonNull("id")?"id":"name").asText();version=object.path("version").asText();}var fields=new ArrayList<String>();object.fieldNames().forEachRemaining(fields::add);
   for(String field:fields) {
    JsonNode value=object.get(field);if(value.isTextual()) {
     String format=getRepository().getFormat().getValue(),text=value.asText();
     if(format.equals("composer")&&field.equals("metadata-url")){String template=resolve(text);content().json("/_metadata/composer-template.json",Map.of("url",template));object.put(field,base(c)+"/p2/%package%.json");}
     else if(format.equals("composer")&&field.equals("providers-url")){object.put(field,base(c)+"/p/%package%$%hash%.json");}
     else if((format.equals("terraform")&&List.of("download_url","shasums_url","shasums_signature_url").contains(field))||(format.equals("swift")&&field.equals("url"))||(format.equals("composer")&&field.equals("url")&&List.of("zip","tar","rar","phar","gzip").contains(object.path("type").asText())))object.put(field,mapped(c,resolve(text),name,version));
    }else rewrite(c,value,name,version);
   }
  }else if(node.isArray())for(var child:node)rewrite(c,child,name,version);
 }
 @Override protected HttpRequestBase buildFetchHttpRequest(URI uri,Context c,Content stale) {
  try {var builder=new URIBuilder(uri);var params=c.getRequest().getParameters();for(String name:params.names())for(String value:params.getAll(name))builder.addParameter(name,value);return super.buildFetchHttpRequest(builder.build(),c,stale);}catch(URISyntaxException e){throw new IllegalArgumentException("Invalid proxy query",e);}
 }
 @Override protected HttpResponse execute(Context context,HttpClient client,HttpRequestBase request)throws IOException {
  return requests.execute(context,client,request,getConfiguration().getRemoteURL(),facet(HttpClientFacet.class).getBearerToken());
 }
 private String cacheKey(Context c)throws IOException{String p=path(c);var params=c.getRequest().getParameters();if(params.isEmpty())return p;var map=new TreeMap<String,List<String>>();for(String key:params.names())map.put(key,params.getAll(key));return p+"?"+sha256(JSON.writeValueAsBytes(map));}
 @Override protected org.sonatype.nexus.repository.cache.CacheController getCacheController(Context c){return metadata(path(c))?cacheControllerHolder.getMetadataCacheController():cacheControllerHolder.getContentCacheController();}
 private boolean metadata(String path){String format=getRepository().getFormat().getValue();if(path.startsWith("/_remote/"))return false;if(format.equals("cargo"))return !path.endsWith("/download");if(format.equals("huggingface"))return path.startsWith("/api/");return !path.matches(".*\\.(zip|crate|tar\\.gz|tgz|whl|bin|safetensors)$");}
 private String componentName(String format,String p){if(format.equals("go")&&p.contains("/@v/"))return p.substring(1,p.indexOf("/@v/"));if(format.equals("cargo")&&p.endsWith("/download"))return p.split("/")[4];if(format.equals("swift")&&p.split("/").length>=4)return p.split("/")[1]+"."+p.split("/")[2];if(format.equals("terraform")&&p.startsWith("/v1/")){String[] parts=p.split("/");if(parts.length>=6)return parts[2].equals("modules")&&parts.length>=7?parts[3]+"/"+parts[4]+"/"+parts[5]:parts[3]+"/"+parts[4];}if(format.equals("huggingface")){int marker=p.indexOf("/resolve/");if(marker<0)marker=p.indexOf("/raw/");if(marker>0)return p.substring(1,marker);}return p.startsWith("/_remote/")?null:p.substring(1);}
 private String componentVersion(String format,String p){if(format.equals("go")&&p.contains("/@v/"))return p.substring(p.indexOf("/@v/")+4).replaceFirst("\\.(info|mod|zip)$", "");if(format.equals("cargo")&&p.endsWith("/download"))return p.split("/")[5];if(format.equals("swift")&&p.split("/").length>=4)return p.split("/")[3].replaceFirst("\\.zip$", "");if(format.equals("terraform")&&p.startsWith("/v1/")){String[] parts=p.split("/");if(parts.length>=6)return parts[2].equals("modules")&&parts.length>=7?parts[6]:parts[5];}return "";}
}

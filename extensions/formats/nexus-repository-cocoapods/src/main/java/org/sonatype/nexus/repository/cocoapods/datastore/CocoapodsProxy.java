/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.cocoapods.datastore;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.cocoapods.internal.AssetKind;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class CocoapodsProxy extends ContentProxyFacetSupport {
 private static final ObjectMapper JSON=new ObjectMapper();
 @org.springframework.beans.factory.annotation.Autowired private org.sonatype.nexus.httpclient.HttpClientManager clients;
 @org.springframework.beans.factory.annotation.Autowired private org.sonatype.nexus.repository.cocoapods.internal.proxy.SpecFileProcessor specs;
 @Override protected Content getCachedContent(Context context){return facet(CocoapodsContent.class).get(context.getRequest().getPath());}
 @Override protected String getUrl(Context context){
  String path=context.getRequest().getPath();
  if(path.startsWith("/_source/")){
   if(!path.matches("/_source/[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid source key");
   Content descriptor=facet(CocoapodsContent.class).get("/_source-map/"+path.substring(9));
   if(descriptor==null)return null;
   try(var in=descriptor.openInputStream()){return JSON.readTree(in).path("url").asText();}catch(IOException e){throw new java.io.UncheckedIOException(e);}
  }
  return path.substring(1);
 }
 @Override protected Content store(Context context,Content content)throws IOException {
  String path=context.getRequest().getPath();
  if(path.endsWith(".podspec.json")){
   byte[] bytes;try(var in=content.openInputStream()){bytes=in.readNBytes(4*1024*1024+1);if(bytes.length>4*1024*1024)throw new IOException("Podspec metadata too large");}
   JsonNode spec=JSON.readTree(bytes);if(!spec.path("name").asText().matches("[A-Za-z0-9][A-Za-z0-9_.+-]{0,254}")||!spec.path("version").asText().matches("[0-9][A-Za-z0-9_.+-]{0,254}"))throw new IOException("Invalid podspec coordinates");JsonNode source=spec.get("source");
   if(source instanceof ObjectNode obj){
    String url;try{url=specs.extractExternalUri(new String(bytes,StandardCharsets.UTF_8)).toString();}catch(org.sonatype.nexus.repository.cocoapods.internal.proxy.InvalidSpecFileException e){throw new IOException("Invalid pod source",e);}
    URI uri=URI.create(url);
    if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Unsupported pod source URL");
    String key;try{key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
    facet(CocoapodsContent.class).put("/_source-map/"+key,new BytesPayload(JSON.writeValueAsBytes(Map.of("url",url,"name",spec.path("name").asText(),"version",spec.path("version").asText())),"application/json"),"SOURCE_DESCRIPTOR",spec.path("name").asText(),spec.path("version").asText());
    
   }
   return facet(CocoapodsContent.class).put(path,new BytesPayload(bytes,"application/json"),AssetKind.SPEC.name(),spec.path("name").asText(),spec.path("version").asText());
  }
  if(path.startsWith("/_source/")){
   var descriptor=facet(CocoapodsContent.class).get("/_source-map/"+path.substring(9));
   if(descriptor==null)throw new IOException("Missing pod source descriptor");
   try(var input=descriptor.openInputStream()){var data=JSON.readTree(input);return facet(CocoapodsContent.class).put(path,content,AssetKind.POD.name(),data.path("name").asText(),data.path("version").asText());}
  }
  return facet(CocoapodsContent.class).put(path,content,AssetKind.CDN_METADATA.name());
 }
 @Override protected org.sonatype.nexus.repository.cache.CacheController getCacheController(Context context){return cacheControllerHolder.require(context.getRequest().getPath().startsWith("/_source/")?org.sonatype.nexus.repository.cache.CacheControllerHolder.CONTENT:org.sonatype.nexus.repository.cache.CacheControllerHolder.METADATA);}
 @Override protected org.apache.http.HttpResponse execute(Context context,org.apache.http.client.HttpClient client,org.apache.http.client.methods.HttpRequestBase request)throws IOException {
  var provider=new org.apache.http.impl.client.BasicCredentialsProvider();
  URI remote=URI.create(getRepository().getConfiguration().attributes("proxy").require("remoteUrl",String.class));
  var cfg=facet(org.sonatype.nexus.repository.config.ConfigurationFacet.class).readSection(getRepository().getConfiguration(),"httpclient",org.sonatype.nexus.repository.httpclient.HttpClientConfig.class);
  CocoapodsCredentials.add(provider,remote.getHost(),CocoapodsCredentials.port(remote),cfg.authentication);
  var global=clients.getConfiguration().getProxy();
  if(global!=null){for(var proxy:java.util.Arrays.asList(global.getHttp(),global.getHttps()))if(proxy!=null)CocoapodsCredentials.add(provider,proxy.getHost(),proxy.getPort(),proxy.getAuthentication());}
  var http=org.apache.http.client.protocol.HttpClientContext.create();http.setCredentialsProvider(provider);
  http.setAttribute(org.sonatype.nexus.repository.httpclient.OutboundRequestMetricRecorder.CONTEXT_FORMAT,"cocoapods");http.setAttribute(org.sonatype.nexus.repository.httpclient.OutboundRequestMetricRecorder.CONTEXT_REPOSITORY_TYPE,"proxy");
  var response=client.execute(request,http);context.setAttribute(HTTP_CONTEXT,http);return response;
 }

}

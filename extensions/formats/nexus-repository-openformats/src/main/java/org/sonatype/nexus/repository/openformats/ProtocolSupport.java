/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
public final class ProtocolSupport {
 public static final ObjectMapper JSON=new ObjectMapper();
 public static byte[] read(Payload payload,int limit) throws IOException {
  if(payload==null) throw new IllegalArgumentException("Request body required");
  try(var in=payload.openInputStream()) { var bytes=in.readNBytes(limit+1); if(bytes.length>limit) throw new IllegalArgumentException("Metadata exceeds size limit"); return bytes; }
 }
 public static JsonNode json(Payload payload) throws IOException { return JSON.readTree(read(payload,8*1024*1024)); }
 public static Response json(int status,Object body) throws IOException { return response(status,new BytesPayload(JSON.writeValueAsBytes(body),"application/json")); }
 public static Response text(int status,String body,String type) { return response(status,new BytesPayload(body.getBytes(StandardCharsets.UTF_8),type)); }
 public static Response response(int status,Payload payload) { return new Response.Builder().status(new Status(status<400,status)).payload(payload).build(); }
 public static void rebase(JsonNode node,String from,String to){if(node.isObject()){var object=(com.fasterxml.jackson.databind.node.ObjectNode)node;var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);for(String name:names){JsonNode value=object.get(name);if(value.isTextual()&&value.asText().startsWith(from+"/"))object.put(name,to+value.asText().substring(from.length()));else if(!value.isTextual())rebase(value,from,to);}}else if(node.isArray())for(var child:node)rebase(child,from,to);}
 public static String resolveTemplate(java.net.URI base,String value){String template=value.replace("%package%","NEXUS_PACKAGE_PLACEHOLDER").replace("%hash%","NEXUS_HASH_PLACEHOLDER");return base.resolve(template).toString().replace("NEXUS_PACKAGE_PLACEHOLDER","%package%").replace("NEXUS_HASH_PLACEHOLDER","%hash%");}
 /** Keep Hub pagination inside Nexus; the SDK sends its authentication header on each next-page request. */
 public static String repositoryLinks(String header,java.net.URI upstream,String endpoint){
  var matcher=java.util.regex.Pattern.compile("<([^>]+)>").matcher(header);StringBuffer result=new StringBuffer();String prefix=Objects.toString(upstream.getRawPath(),"/");if(!prefix.endsWith("/"))prefix+="/";
  while(matcher.find()){
   java.net.URI target=upstream.resolve(matcher.group(1));String path=Objects.toString(target.getRawPath(),"/");
   if(target.getHost()==null||target.getUserInfo()!=null||target.getFragment()!=null||!upstream.getScheme().equalsIgnoreCase(target.getScheme())||!upstream.getHost().equalsIgnoreCase(target.getHost())||uriPort(upstream)!=uriPort(target)||!path.startsWith(prefix))throw new IllegalArgumentException("Hub pagination URL outside configured upstream");
   String url=endpoint.replaceAll("/$","")+"/"+path.substring(prefix.length())+(target.getRawQuery()==null?"":"?"+target.getRawQuery());matcher.appendReplacement(result,java.util.regex.Matcher.quoteReplacement("<"+url+">"));
  }
  matcher.appendTail(result);return result.toString();
 }
 private static int uriPort(java.net.URI uri){return uri.getPort()<0?("https".equalsIgnoreCase(uri.getScheme())?443:80):uri.getPort();}
 public static String sha256(byte[] bytes) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);} }
 public static String path(Context c) {
  String path=c.getRequest().getPath();
  if(path.contains("\0")||path.contains("\\")||path.matches(".*(^|/)\\.\\.?(/|$).*")) throw new IllegalArgumentException("Invalid protocol path");
  return path.startsWith("/")?path:"/"+path;
 }
 public static String base(Context c) { return c.getRepository().getUrl().replaceAll("/$",""); }
 public static String require(JsonNode node,String field,String pattern) { String v=node.path(field).asText(""); if(!v.matches(pattern)) throw new IllegalArgumentException("Invalid "+field); return v; }
 public static List<JsonNode> lines(byte[] bytes) throws IOException { var result=new ArrayList<JsonNode>(); if(bytes!=null) for(String line:new String(bytes,StandardCharsets.UTF_8).split("\n")) if(!line.isBlank())result.add(JSON.readTree(line)); return result; }
 public static byte[] lines(Collection<? extends JsonNode> lines) throws IOException { var result=new ByteArrayOutputStream();for(var line:lines){result.write(JSON.writeValueAsBytes(line));result.write('\n');}return result.toByteArray(); }
}

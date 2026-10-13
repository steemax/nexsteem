/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
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
 public static String sha256(byte[] bytes) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);} }
 public static void verifySha256(Context context,String actual) {
  String expected=context.getRequest().getHeaders().get("X-Checksum-Sha256");
  if(expected!=null&&(!expected.matches("[A-Fa-f0-9]{64}")||!actual.equalsIgnoreCase(expected)))throw new IllegalArgumentException("SHA-256 checksum mismatch");
 }
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

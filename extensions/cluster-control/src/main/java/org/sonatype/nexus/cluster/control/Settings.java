/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import java.nio.file.*;
import java.net.URI;
import java.util.*;

final class Settings {
  final Properties properties=new Properties();
  final String cluster,node,owner,endpoint,sqlUrl,sqlUser,sqlPasswordFile,controlUser,controlPasswordFile,applicationHash,version;
  final int frontendPort,corePort,leaseSeconds,heartbeatSeconds;
  final Path root;
  Settings(Map<String,String> env)throws Exception {
    properties.putAll(env);
    Path nexusHome=Path.of(env.getOrDefault("NEXUS_HOME","/opt/nexus"));
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    applicationHash=mapper.readTree(Files.readAllBytes(nexusHome.resolve("EXTENSIONS-PROVENANCE.json"))).path("application_sha256").asText();
    version=mapper.readTree(Files.readAllBytes(nexusHome.resolve("BUILD-PROVENANCE.json"))).path("version").asText();
    if(!applicationHash.matches("[a-f0-9]{64}")||version.isBlank())throw new IllegalArgumentException("Missing immutable application provenance");
    java.util.List<Path> jars;try(var stream=Files.newDirectoryStream(nexusHome.resolve("bin"),"nexus-repository-core-*.jar")){jars=new java.util.ArrayList<>();stream.forEach(jars::add);}
    if(jars.size()!=1)throw new IllegalArgumentException("Expected exactly one assembled Core jar");
    var sha=java.security.MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(jars.getFirst());var out=new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),sha)){in.transferTo(out);}
    if(!applicationHash.equals(java.util.HexFormat.of().formatHex(sha.digest())))throw new IllegalArgumentException("Application jar does not match immutable provenance");
    cluster=required(env,"NX_CLUSTER_ID");node=required(env,"NX_CLUSTER_NODE_ID");
    owner=env.getOrDefault("NX_CLUSTER_OWNER_ID",node+":"+env.getOrDefault("NX_CLUSTER_POD_UID","local")+":"+UUID.randomUUID());
    endpoint=required(env,"NX_CLUSTER_NODE_URL");URI uri=URI.create(endpoint);if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("Invalid member endpoint");
    sqlUrl=required(env,"NX_CLUSTER_SQL_URL");sqlUser=required(env,"NX_CLUSTER_SQL_USER");sqlPasswordFile=required(env,"NX_CLUSTER_SQL_PASSWORD_FILE");
    controlUser=required(env,"NX_CLUSTER_CONTROL_USER");controlPasswordFile=required(env,"NX_CLUSTER_CONTROL_PASSWORD_FILE");
    frontendPort=Integer.parseInt(env.getOrDefault("NX_CLUSTER_FRONTEND_PORT","8081"));corePort=Integer.parseInt(env.getOrDefault("NX_CLUSTER_CORE_PORT","19081"));
    leaseSeconds=Integer.parseInt(env.getOrDefault("NX_CLUSTER_LEASE_SECONDS","30"));heartbeatSeconds=Integer.parseInt(env.getOrDefault("NX_CLUSTER_HEARTBEAT_SECONDS","3"));
    if(leaseSeconds<3||leaseSeconds>60||heartbeatSeconds<1||heartbeatSeconds>=leaseSeconds/2)throw new IllegalArgumentException("Invalid lease/heartbeat timing");
    root=Path.of(required(env,"NX_CLUSTER_ROOT"));Files.createDirectories(root);properties.setProperty("NX_CLUSTER_OWNER_ID",owner);
  }
  static String required(Map<String,String> env,String key){String value=env.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing "+key);return value;}
  static String password(String filename)throws Exception{return Files.readString(Path.of(filename)).stripTrailing();}
}

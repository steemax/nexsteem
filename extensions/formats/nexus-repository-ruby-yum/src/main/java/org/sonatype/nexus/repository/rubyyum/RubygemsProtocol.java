/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.*;
import static org.sonatype.nexus.repository.rubyyum.ProtocolSupport.*;
import static org.sonatype.nexus.repository.rubyyum.RubygemsCodec.*;

final class RubygemsProtocol {
  static Response handle(Context context, RubyYumContentFacet store) throws Exception {
    String path=path(context), action=context.getRequest().getAction();
    if ("POST".equals(action) && path.equals("/api/v1/gems")) return push(context,store);
    if ("DELETE".equals(action)) {
      synchronized(store) {
        if (path.equals("/api/v1/gems/yank")) {
          var parameters=context.getRequest().getParameters();
          String name=parameters.get("gem_name"),version=parameters.get("version"),platform=parameters.get("platform");
          if ((name==null || version==null) && context.getRequest().getPayload()!=null) {
            var form=RubygemsCodec.query(new String(read(context.getRequest().getPayload(),8192),StandardCharsets.UTF_8));
            name=form.get("gem_name");version=form.get("version");platform=form.get("platform");
          }
          validateIdentity(name,version,platform==null?"ruby":platform);
          path="/"+gemPath(name,version,platform==null?"ruby":platform);
        }
        if (!path.startsWith("/gems/")) return response(405,null);
        return text(store.delete(path)?200:404,"Successfully removed gem\n","text/plain");
      }
    }
    if (!List.of("GET","HEAD").contains(action)) return response(405,null);
    if (path.startsWith("/gems/")) return store.get(path).map(c -> response(200,c)).orElseGet(() -> response(404,null));
    List<GemMetadata> gems=storedGems(store);
    if (path.equals("/") || path.endsWith("specs.4.8.gz")) {
      if (path.equals("/latest_specs.4.8.gz")) gems=latestSpecs(gems);
      else if (path.equals("/prerelease_specs.4.8.gz")) gems=gems.stream().filter(g->g.version().matches(".*[A-Za-z].*")).toList();
      else gems=gems.stream().filter(g->!g.version().matches(".*[A-Za-z].*")).toList();
      return response(200,new BytesPayload(gzip(rubyMarshalSpecs(gems)),"application/octet-stream"));
    }
    var byName=byName(gems);
    if (path.equals("/names")) return text(200,String.join("\n",byName.keySet())+"\n","text/plain");
    if (path.equals("/versions")) return compact(compactVersionsBody(byName));
    if (path.startsWith("/info/")) return byName.containsKey(path.substring(6)) ? compact(compactInfoBody(byName.get(path.substring(6)))) : response(404,null);
    if (path.equals("/api/v1/dependencies")) {
      String query=context.getRequest().getParameters().get("gems");
      var names=query==null?Set.<String>of():new LinkedHashSet<>(Arrays.asList(query.split(",")));
      var records=new ArrayList<Object>();
      for (GemMetadata gem:gems) if(names.isEmpty()||names.contains(gem.name())) records.add(Map.of("name",gem.name(),"number",gem.version(),"platform",gem.platform(),"dependencies",dependencyApiDependencies(gem.dependencies())));
      return marshal(records);
    }
    if (path.startsWith("/quick/Marshal.4.8/")) {
      String name=path.substring("/quick/Marshal.4.8/".length(),path.length()-".gemspec.rz".length());
      for(GemMetadata gem:gems) if(fullName(gem).equals(name)) return response(200,new BytesPayload(deflateLikeRuby(rubyMarshalGemSpecification(gem)),"application/octet-stream"));
    }
    return response(404,null);
  }
  static Response compact(String body) {
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
    return new Response.Builder().copy(text(200,body,"text/plain")).header("ETag","\""+md5(bytes)+"\"").header("Repr-Digest","sha-256=:"+Base64.getEncoder().encodeToString(hexSha(bytes))+":").build();
  }
  static byte[] hexSha(byte[] bytes) { return HexFormat.of().parseHex(sha256(bytes)); }
  static Response marshal(List<Object> values) {
    var out=new ByteArrayOutputStream();out.writeBytes(new byte[]{4,8,'['});writeFixnum(out,values.size());for(Object value:values)writeObject(out,value);
    return response(200,new BytesPayload(out.toByteArray(),"application/octet-stream"));
  }
  static List<GemMetadata> storedGems(RubyYumContentFacet store) {
    var result=new ArrayList<GemMetadata>();
    for(var asset:store.all()) {
      Object metadata=asset.attributes().get("rubygemsMetadata");
      if(metadata!=null && asset.path().endsWith(".gem")) result.add(JSON.convertValue(metadata,GemMetadata.class));
    }
    return sorted(result);
  }
  private static Response push(Context context,RubyYumContentFacet store) throws Exception {
    if(context.getRequest().getPayload()==null)throw new IllegalArgumentException("Gem archive body is required");
    var temp=Files.createTempFile("nexus-rubygems-",".gem");
    try {
      try(var in=context.getRequest().getPayload().openInputStream(); var out=Files.newOutputStream(temp)) {
        byte[] buffer=new byte[65536];long length=0;int count;
        while((count=in.read(buffer))!=-1) { if((length+=count)>512L*1024*1024)throw new IllegalArgumentException("Gem exceeds 512 MiB limit");out.write(buffer,0,count); }
      }
      GemMetadata metadata;
      try {validateArchive(temp);metadata=readGemMetadata(temp);}catch(IOException invalid){throw new IllegalArgumentException("Invalid gem archive",invalid);}
      validateIdentity(metadata.name(),metadata.version(),metadata.platform());
      try(var in=Files.newInputStream(temp)) {var hash=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)hash.update(buffer,0,count);metadata=metadata.withSha256(HexFormat.of().formatHex(hash.digest()));}
      verifySha256(context,metadata.sha256());
      final GemMetadata saved=metadata;
      String path="/"+gemPath(metadata);
      synchronized(store) {
        Content content=store.put(path,new StreamPayload(()->Files.newInputStream(temp),Files.size(temp),"application/octet-stream"),"RUBYGEMS_PACKAGE",metadata.name(),metadata.version());
        store.assets().path(path).find().orElseThrow().withAttribute("rubygemsMetadata",JSON.convertValue(saved,Map.class));
      }
      return text(201,"Successfully registered gem: "+metadata.name()+" ("+metadata.version()+")\n","text/plain");
    } finally {Files.deleteIfExists(temp);}
  }
  private static void validateArchive(java.nio.file.Path file)throws Exception {
    var hashes=new HashMap<String,Map<String,String>>();byte[] checksumYaml=null;boolean metadata=false,data=false;int count=0;
    try(var in=Files.newInputStream(file);var tar=new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(in)) {
      for(var entry=tar.getNextTarEntry();entry!=null;entry=tar.getNextTarEntry()) {
        if(++count>32||entry.isDirectory()||!Set.of("metadata.gz","data.tar.gz","checksums.yaml.gz","metadata.gz.sig","data.tar.gz.sig","checksums.yaml.gz.sig").contains(entry.getName()))throw new IllegalArgumentException("Unexpected gem archive entry");
        if(hashes.containsKey(entry.getName()))throw new IllegalArgumentException("Duplicate gem archive entry");
        var sha256=java.security.MessageDigest.getInstance("SHA-256");var sha512=java.security.MessageDigest.getInstance("SHA-512");
        var capture=entry.getName().equals("checksums.yaml.gz")?new ByteArrayOutputStream():null;
        byte[] buffer=new byte[65536];long length=0;int size;while((size=tar.read(buffer))!=-1) {
          if((length+=size)>512L*1024*1024)throw new IllegalArgumentException("Gem entry exceeds limit");
          sha256.update(buffer,0,size);sha512.update(buffer,0,size);if(capture!=null){if(length>4*1024*1024)throw new IllegalArgumentException("Gem checksums exceed limit");capture.write(buffer,0,size);}
        }
        hashes.put(entry.getName(),Map.of("SHA256",HexFormat.of().formatHex(sha256.digest()),"SHA512",HexFormat.of().formatHex(sha512.digest())));
        if(entry.getName().equals("metadata.gz"))metadata=true;if(entry.getName().equals("data.tar.gz"))data=true;
        if(capture!=null)try(var gzip=new java.util.zip.GZIPInputStream(new ByteArrayInputStream(capture.toByteArray()))){checksumYaml=RubygemsCodec.boundedMetadata(gzip);}
      }
    }
    if(!metadata||!data)throw new IllegalArgumentException("Gem must contain metadata.gz and data.tar.gz");
    if(checksumYaml!=null) {
      var options=new org.yaml.snakeyaml.LoaderOptions();options.setAllowDuplicateKeys(false);options.setMaxAliasesForCollections(0);options.setCodePointLimit(4*1024*1024);
      Object parsed;try {parsed=new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(options)).load(new String(checksumYaml,StandardCharsets.UTF_8));}catch(org.yaml.snakeyaml.error.YAMLException error){throw new IllegalArgumentException("Invalid gem checksum manifest",error);}
      if(!(parsed instanceof Map<?,?> manifest))throw new IllegalArgumentException("Invalid gem checksum manifest");
      for(var algorithm:manifest.entrySet()) {
        if(!Set.of("SHA256","SHA512").contains(String.valueOf(algorithm.getKey())))continue;
        if(!(algorithm.getValue()instanceof Map<?,?> entries))throw new IllegalArgumentException("Invalid gem checksum manifest");
        for(var checksum:entries.entrySet()){var digest=hashes.get(String.valueOf(checksum.getKey()));if(digest==null||!digest.get(String.valueOf(algorithm.getKey())).equalsIgnoreCase(String.valueOf(checksum.getValue())))throw new IllegalArgumentException("Gem archive checksum mismatch");}
      }
    }
  }
  static void validateIdentity(String name,String version,String platform) {
    if(name==null||version==null||!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}")||!version.matches("[0-9][A-Za-z0-9_.-]{0,254}")||!platform.matches("[A-Za-z0-9_.-]{1,254}"))throw new IllegalArgumentException("Invalid gem identity");
  }
}

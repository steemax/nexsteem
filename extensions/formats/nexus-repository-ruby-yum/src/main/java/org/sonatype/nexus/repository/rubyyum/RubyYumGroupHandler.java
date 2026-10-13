/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.group.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.springframework.stereotype.Component;
import static org.sonatype.nexus.repository.rubyyum.ProtocolSupport.*;

@Component
public class RubyYumGroupHandler extends GroupHandler {
  @Override protected Response doGet(Context context,DispatchedRepositories dispatched) throws Exception {
    String path=path(context),format=context.getRepository().getFormat().getValue();
    if(format.equals("yum")) {
      var memberPath=java.util.regex.Pattern.compile("^.*?/__members/([^/]+)(/.*)$").matcher(path);
      if(memberPath.matches()) {
        for(var member:context.getRepository().facet(GroupFacet.class).members())if(member.getName().equals(memberPath.group(1)))return fetchMember(context,member,memberPath.group(2),dispatched);
        return response(404,null);
      }
      var cached=context.getRepository().facet(RubyYumContentFacet.class).get(path);
      if(path.endsWith("/repodata/repomd.xml")) return YumGroupMetadata.build(context,this,dispatched);
      if(cached.isPresent()&&path.contains("/repodata/"))return response(200,cached.get());
      return getFirst(context,context.getRepository().facet(GroupFacet.class).members(),dispatched);
    }
    if(path.endsWith("specs.4.8.gz") || path.equals("/")) {
      var values=new LinkedHashMap<String,RubygemsCodec.GemMetadata>();boolean found=false;
      for(var response:getAll(context,context.getRepository().facet(GroupFacet.class).members(),dispatched).values()) {
        if(!response.getStatus().isSuccessful()||response.getPayload()==null)continue;
        found=true;byte[] archive=read(response.getPayload(),64*1024*1024);
        try(var gzip=new GZIPInputStream(new ByteArrayInputStream(archive))){byte[] body=gzip.readNBytes(64*1024*1024+1);if(body.length>64*1024*1024)throw new IllegalArgumentException("Gem index limit");for(var gem:RubyMarshal.specs(body))values.putIfAbsent(RubygemsCodec.fullName(gem),gem);}
      }
      return found?response(200,new BytesPayload(RubygemsCodec.gzip(RubygemsCodec.rubyMarshalSpecs(RubygemsCodec.sorted(new ArrayList<>(values.values())))),"application/octet-stream")):response(404,null);
    }
    if(path.equals("/names")) {
      var names=new TreeSet<String>();boolean found=false;
      for(var response:getAll(context,context.getRepository().facet(GroupFacet.class).members(),dispatched).values())if(response.getStatus().isSuccessful()&&response.getPayload()!=null){found=true;for(String name:new String(read(response.getPayload(),64*1024*1024),StandardCharsets.UTF_8).split("\\R"))if(!name.isBlank())names.add(name);}
      return found?text(200,String.join("\n",names)+"\n","text/plain"):response(404,null);
    }
    if(path.startsWith("/info/"))return compactInfo(context,path,dispatched);
    if(path.equals("/versions")) {
      var names=new TreeSet<String>();boolean found=false;
      for(var response:getAll(context,context.getRepository().facet(GroupFacet.class).members(),dispatched).values())if(response.getStatus().isSuccessful()&&response.getPayload()!=null){found=true;for(String line:new String(read(response.getPayload(),64*1024*1024),StandardCharsets.UTF_8).split("\\R"))if(line.matches("[A-Za-z0-9_.-]+ .+ .+"))names.add(line.substring(0,line.indexOf(' ')));}
      if(!found)return response(404,null);
      StringBuilder body=new StringBuilder("created_at: 0\n---\n");
      for(String name:names) {
        Response merged=compactInfo(context,"/info/"+name,fresh(context,dispatched));
        if(!merged.getStatus().isSuccessful())continue;
        byte[] info=read(merged.getPayload(),64*1024*1024);var versions=new ArrayList<String>();
        for(String line:new String(info,StandardCharsets.UTF_8).split("\\R"))if(!line.isBlank()&&!line.equals("---"))versions.add(line.split("[ |]",2)[0]);
        body.append(name).append(' ').append(String.join(",",versions)).append(' ').append(RubygemsCodec.md5(info)).append('\n');
      }
      return RubygemsProtocol.compact(body.toString());
    }
    if(path.equals("/api/v1/dependencies")) {
      var values=new LinkedHashMap<String,Object>();boolean found=false;
      for(var response:getAll(context,context.getRepository().facet(GroupFacet.class).members(),dispatched).values())if(response.getStatus().isSuccessful()&&response.getPayload()!=null){found=true;Object root=RubyMarshal.decode(read(response.getPayload(),64*1024*1024));if(!(root instanceof List<?> records))throw new IllegalArgumentException("Invalid dependency API response");for(Object record:records){if(!(record instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid dependency record");values.putIfAbsent(map.get("name")+"|"+map.get("number")+"|"+map.get("platform"),record);}}
      return found?RubygemsProtocol.marshal(new ArrayList<>(values.values())):response(404,null);
    }
    return getFirst(context,context.getRepository().facet(GroupFacet.class).members(),dispatched);
  }
  private Response compactInfo(Context context,String path,DispatchedRepositories dispatched)throws Exception {
    var records=new LinkedHashMap<String,String>();boolean found=false;
    for(var response:fetch(context,path,dispatched).values())if(response.getStatus().isSuccessful()&&response.getPayload()!=null){found=true;boolean header=true;for(String line:new String(read(response.getPayload(),64*1024*1024),StandardCharsets.UTF_8).split("\\R")){if(line.equals("---")){header=false;continue;}if(header||line.isBlank())continue;records.putIfAbsent(line.split("[ |]",2)[0],line);}}
    return found?RubygemsProtocol.compact("---\n"+String.join("\n",records.values())+"\n"):response(404,null);
  }
  LinkedHashMap<Repository,Response> fetch(Context context,String path,DispatchedRepositories dispatched)throws Exception {
    Request request=new Request.Builder().copy(context.getRequest()).path(path).action("GET").attributes(new AttributesMap(new HashMap<>(context.getRequest().getAttributes().backing()))).build();
    request.getAttributes().set(DispatchedRepositories.class,dispatched);
    return getAll(request,context,context.getRepository().facet(GroupFacet.class).members(),dispatched);
  }
  Response fetchMember(Context context,Repository member,String path,DispatchedRepositories dispatched)throws Exception {
    Request request=new Request.Builder().copy(context.getRequest()).path(path).action("GET").attributes(new AttributesMap(new HashMap<>(context.getRequest().getAttributes().backing()))).build();
    var visit=fresh(context,dispatched);request.getAttributes().set(DispatchedRepositories.class,visit);
    return member.facet(ViewFacet.class).dispatch(request,context);
  }
  private DispatchedRepositories fresh(Context context,DispatchedRepositories old) {
    var copy=new DispatchedRepositories();copy.add(context.getRepository());return copy;
  }
}

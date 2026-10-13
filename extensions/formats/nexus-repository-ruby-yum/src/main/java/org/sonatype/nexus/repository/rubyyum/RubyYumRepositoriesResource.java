/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.util.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.sonatype.nexus.rest.Resource;
import org.springframework.stereotype.Component;

@Component @Path("/v1/repositories") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class RubyYumRepositoriesResource implements Resource {
  private final RepositoryManager manager;
  private final AuthorizingRepositoryManager authorized;
  public RubyYumRepositoriesResource(RepositoryManager manager,AuthorizingRepositoryManager authorized){this.manager=manager;this.authorized=authorized;}
  @POST @Path("/rubygems/{type}") @RequiresAuthentication
  public Response rubygems(@PathParam("type")String type,Map<String,Object> request)throws Exception{return create("rubygems",type,request);}
  @POST @Path("/yum/{type}") @RequiresAuthentication
  public Response yum(@PathParam("type")String type,Map<String,Object> request)throws Exception{return create("yum",type,request);}
  private Response create(String format,String type,Map<String,Object> request)throws Exception {
    if(!Set.of("hosted","proxy","group").contains(type))throw new BadRequestException("Unsupported recipe");
    var config=manager.newConfiguration();config.setRecipeName(format+"-"+type);String name=String.valueOf(request.get("name"));if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}"))throw new BadRequestException("Invalid repository name");config.setRepositoryName(name);config.setOnline(!Boolean.FALSE.equals(request.get("online")));
    var attributes=new HashMap<String,Map<String,Object>>();for(var entry:request.entrySet())if(entry.getValue()instanceof Map<?,?> map){var section=new LinkedHashMap<String,Object>();map.forEach((k,v)->section.put(String.valueOf(k),v));
      if(entry.getKey().equals("httpClient")&&section.get("authentication")instanceof Map<?,?> source){
        var authentication=new LinkedHashMap<String,Object>();source.forEach((k,v)->authentication.put(String.valueOf(k),v));
        // Match the standard Core REST converter; the secret encoder encrypts this value before storage.
        if(authentication.containsKey("bearerToken")){authentication.put("bearerTokenId",authentication.remove("bearerToken"));}
        section.put("authentication",authentication);
      }
      attributes.put(entry.getKey().equals("httpClient")?"httpclient":entry.getKey(),section);
    }config.setAttributes(attributes);authorized.create(config);return Response.status(201).build();
  }
}

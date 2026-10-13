/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
import java.util.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.sonatype.nexus.rest.Resource;
@org.springframework.stereotype.Component @Path("/v1/repositories/nuget") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class NugetRepositoriesResource implements Resource {
 private final RepositoryManager manager;private final AuthorizingRepositoryManager authorized;
 public NugetRepositoriesResource(RepositoryManager manager,AuthorizingRepositoryManager authorized){this.manager=manager;this.authorized=authorized;}
 @POST @Path("/{type}") @RequiresAuthentication public Response create(@PathParam("type")String type,Map<String,Object> request)throws Exception{
  if(!Set.of("hosted","proxy","group").contains(type))throw new BadRequestException("Unsupported NuGet recipe");Configuration cfg=configuration(type,request);authorized.create(cfg);return Response.status(201).build();
 }
 @PUT @Path("/{type}/{name}") @RequiresAuthentication public Response update(@PathParam("type")String type,@PathParam("name")String name,Map<String,Object> request)throws Exception{
  if(!Set.of("hosted","proxy","group").contains(type)||!name.equals(request.get("name")))throw new BadRequestException("Invalid NuGet repository update");authorized.update(configuration(type,request));return Response.noContent().build();
 }
 private Configuration configuration(String type,Map<String,Object> request){Configuration cfg=manager.newConfiguration();cfg.setRecipeName("nuget-"+type);String name=String.valueOf(request.get("name"));if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}"))throw new BadRequestException("Invalid repository name");cfg.setRepositoryName(name);cfg.setOnline(!Boolean.FALSE.equals(request.get("online")));var attributes=new HashMap<String,Map<String,Object>>();for(var e:request.entrySet())if(e.getValue()instanceof Map<?,?>map){var section=new LinkedHashMap<String,Object>();map.forEach((k,v)->section.put(String.valueOf(k),v));if(e.getKey().equals("httpClient")){Object auth=section.get("authentication");if(auth instanceof Map<?,?> mapAuth){var adjusted=new LinkedHashMap<String,Object>();mapAuth.forEach((k,v)->adjusted.put(String.valueOf(k),v));if(adjusted.containsKey("bearerToken"))adjusted.put("bearerTokenId",adjusted.remove("bearerToken"));section.put("authentication",adjusted);}}attributes.put(e.getKey().equals("httpClient")?"httpclient":e.getKey(),section);}cfg.setAttributes(attributes);return cfg;}
}

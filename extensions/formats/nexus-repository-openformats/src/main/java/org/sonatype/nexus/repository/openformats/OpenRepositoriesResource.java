/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.util.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.sonatype.nexus.rest.Resource;
import org.springframework.stereotype.Component;
@Component @Path("/v1/repositories") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class OpenRepositoriesResource implements Resource {
 private static final Map<String,Set<String>> SUPPORTED=Map.of("cargo",Set.of("hosted","proxy","group"),"gitlfs",Set.of("hosted"),"go",Set.of("proxy","group"),"composer",Set.of("proxy"),"huggingface",Set.of("proxy"),"swift",Set.of("hosted","proxy","group"),"terraform",Set.of("hosted","proxy","group"));
 private final RepositoryManager manager;private final AuthorizingRepositoryManager authorized;
 public OpenRepositoriesResource(RepositoryManager manager,AuthorizingRepositoryManager authorized){this.manager=manager;this.authorized=authorized;Configuration.addSensitiveFieldName("terraformSigning");}
 @POST @Path("/{format}/{type}") @RequiresAuthentication
 public Response create(@PathParam("format")String format,@PathParam("type")String type,Map<String,Object> request)throws Exception {
  if(!SUPPORTED.getOrDefault(format,Set.of()).contains(type))throw new BadRequestException("Unsupported format recipe");
  Configuration cfg=manager.newConfiguration();cfg.setRecipeName(format+"-"+type);String name=String.valueOf(request.get("name"));if(!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}"))throw new BadRequestException("Invalid repository name");cfg.setRepositoryName(name);cfg.setOnline(!Boolean.FALSE.equals(request.get("online")));
  var attributes=new HashMap<String,Map<String,Object>>();for(var entry:request.entrySet())if(entry.getValue()instanceof Map<?,?> map){var section=new LinkedHashMap<String,Object>();map.forEach((k,v)->section.put(String.valueOf(k),v));attributes.put(entry.getKey().equals("httpClient")?"httpclient":entry.getKey(),section);}var http=attributes.get("httpclient");if(http!=null&&http.get("authentication")instanceof Map<?,?> auth&&auth.containsKey("bearerToken")){var converted=new LinkedHashMap<String,Object>();auth.forEach((k,v)->converted.put(String.valueOf(k),v));converted.put("bearerTokenId",converted.remove("bearerToken"));http.put("authentication",converted);}
  cfg.setAttributes(attributes);authorized.create(cfg);return Response.status(201).build();
 }
}

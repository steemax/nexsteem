/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.util.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.repository.config.*;
import org.sonatype.nexus.repository.rest.api.AuthorizingRepositoryManager;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.stereotype.Component;
@Component @Path("/v1/repositories/docker") @Consumes("application/json") @Produces("application/json")
public class DockerRepositoriesApi implements Resource {
  private final ConfigurationStore configs; private final AuthorizingRepositoryManager manager;
  public DockerRepositoriesApi(ConfigurationStore configs, AuthorizingRepositoryManager manager) { this.configs = configs; this.manager = manager; }
  @SuppressWarnings("unchecked")
  private Configuration configuration(String type, Map<String,Object> input) {
    if (!List.of("hosted", "proxy", "group").contains(type)) { throw new BadRequestException("Invalid Docker repository type"); }
    String name = Objects.toString(input.get("name"), "");
    if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,199}")) { throw new BadRequestException("Invalid repository name"); }
    Configuration config = configs.newConfiguration(); config.setRepositoryName(name); config.setRecipeName("docker-" + type);
    config.setOnline(!Boolean.FALSE.equals(input.get("online"))); Map<String,Map<String,Object>> sections = new HashMap<>();
    for (String key : List.of("storage", "docker", "proxy", "negativeCache", "group", "httpClient", "cleanup")) {
      if (input.get(key) instanceof Map<?,?> values) { sections.put(key.equals("httpClient") ? "httpclient" : key, new HashMap<>((Map<String,Object>) values)); }
    }
    var http = sections.get("httpclient");
    if (http != null && http.get("authentication") instanceof Map<?,?> original) {
      Map<String,Object> authentication = new HashMap<>((Map<String,Object>) original);
      if (authentication.containsKey("bearerToken")) { authentication.put("bearerTokenId", authentication.remove("bearerToken")); }
      http.put("authentication", authentication);
    }
    config.setAttributes(sections); return config;
  }
  @POST @Path("/{type}") @RequiresAuthentication
  public Response create(@PathParam("type") String type, Map<String,Object> input) throws Exception {
    manager.create(configuration(type, input)); return Response.status(201).build();
  }
  @PUT @Path("/{type}/{name}") @RequiresAuthentication
  public Response update(@PathParam("type") String type, @PathParam("name") String name, Map<String,Object> input) throws Exception {
    if (!name.equals(input.get("name"))) { throw new BadRequestException("Repository name cannot change"); }
    return Response.status(manager.update(configuration(type, input)) ? 204 : 404).build();
  }
  @GET @Path("/{type}/{name}") @RequiresAuthentication
  public Map<String,Object> read(@PathParam("type") String type, @PathParam("name") String name) {
    var repo = manager.getRepositoryWithAdmin(name).orElseThrow(NotFoundException::new);
    if (!repo.getConfiguration().getRecipeName().equals("docker-" + type)) { throw new NotFoundException(); }
    Map<String,Object> output = new HashMap<>(); output.put("name", name); output.put("online", repo.getConfiguration().isOnline());
    repo.getConfiguration().getAttributes().forEach((key, value) -> { if (!key.equals("httpclient")) { output.put(key, value); } });
    return output;
  }
}

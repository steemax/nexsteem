/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.List;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.sonatype.nexus.rest.Resource;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.stereotype.Component;

@Component @Path("/v1/security/ldap") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class LdapResource implements Resource {
  private final LdapConfigurationStore store;
  public LdapResource(LdapConfigurationStore store) { this.store = store; }
  @GET @RequiresPermissions("nexus:ldap:read")
  public List<LdapServer> list() { return store.list(); }
  @GET @Path("/{name}") @RequiresPermissions("nexus:ldap:read")
  public LdapServer get(@PathParam("name") String name) { return store.get(name); }
  @POST @RequiresPermissions("nexus:ldap:create")
  public LdapServer create(LdapServer server) { return store.save(server, true); }
  @PUT @Path("/{name}") @RequiresPermissions("nexus:ldap:update")
  public LdapServer update(@PathParam("name") String name, LdapServer server) { server.id = store.get(name).id; return store.save(server, false); }
  @DELETE @Path("/{name}") @RequiresPermissions("nexus:ldap:delete")
  public void delete(@PathParam("name") String name) { store.remove(name); }
  @POST @Path("/change-order") @RequiresPermissions("nexus:ldap:update")
  public void reorder(List<String> ids) { store.reorder(ids); }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.sonatype.nexus.rest.Resource;
import org.springframework.stereotype.Component;
@Component @Path("/internal/ui/oauth2") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class OidcConfigurationResource implements Resource {
  private final OidcConfigurationStore store;
  public OidcConfigurationResource(OidcConfigurationStore store) { this.store = store; }
  @GET @RequiresPermissions("nexus:*") public OidcConfiguration get() { return store.get(); }
  @PUT @RequiresPermissions("nexus:*") public OidcConfiguration save(OidcConfiguration configuration) { return store.save(configuration); }
}

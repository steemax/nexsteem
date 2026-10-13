/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.rest.Resource;
import org.springframework.stereotype.Component;

@Component
@Path("/v1/blobstores/cluster-local")
@Produces("application/json")
public final class ClusterBlobResource implements Resource
{
  private final BlobStoreManager stores;
  public ClusterBlobResource(final BlobStoreManager stores) { this.stores = stores; }

  @POST @RequiresPermissions("nexus:blobstores:create")
  public Response createRequest(final java.util.Map<String, Object> request) throws Exception {
    Object name = request == null ? null : request.get("name");
    return create(name instanceof String ? (String) name : "");
  }

  @POST @Path("/{name}") @RequiresPermissions("nexus:blobstores:create")
  public Response create(@PathParam("name") final String name) throws Exception {
    if (!name.matches("[A-Za-z0-9_.-]{1,128}")) { return Response.status(400).build(); }
    org.sonatype.nexus.blobstore.api.BlobStoreConfiguration config = stores.newConfiguration();
    config.setName(name); config.setType(ClusterBlobStore.TYPE);
    stores.create(config); return Response.status(201).build();
  }
}

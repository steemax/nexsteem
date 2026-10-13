/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.SecurityUtils;
import org.sonatype.nexus.rest.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="nexus.cluster.enabled", havingValue="true")
@Path("/internal/ui/cluster/nodes")
@Produces("application/json")
public final class ClusterNodesResource implements Resource {
  private final ClusterNodesMonitor monitor;
  public ClusterNodesResource(ClusterNodesMonitor monitor) { this.monitor=monitor; }

  @GET @RequiresPermissions("nexus:nodes:read")
  public Response nodes() {
    // cluster-web does not weave security aspects; enforce the permission here too.
    SecurityUtils.getSubject().checkPermission("nexus:nodes:read");
    return Response.ok(monitor.snapshot()).header("Cache-Control","no-store").build();
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
import java.nio.charset.StandardCharsets;
import java.util.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.shiro.authz.annotation.*;
import org.sonatype.nexus.rest.Resource;
@org.springframework.stereotype.Component @Path("/v1/security/nuget-apikey") @Produces(MediaType.APPLICATION_JSON)
public class NugetKeyResource implements Resource {
 private final NugetKeyManager keys;
 public NugetKeyResource(NugetKeyManager keys){this.keys=keys;}
 @GET @RequiresAuthentication @RequiresPermissions("nexus:apikey:read") public Map<String,String> get(){return Map.of("apiKey",keys.get(false));}
 @DELETE @RequiresAuthentication @RequiresPermissions("nexus:apikey:delete") public Map<String,String> reset(){return Map.of("apiKey",keys.get(true));}
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
import java.util.*;
import java.nio.charset.StandardCharsets;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.apache.shiro.authz.annotation.*;
import org.sonatype.nexus.rest.Resource;
@org.springframework.stereotype.Component @Path("/internal/nuget-api-key") @Produces(MediaType.APPLICATION_JSON)
public class NugetUiKeyResource implements Resource {
 private final NugetKeyManager keys;private final org.sonatype.nexus.common.wonderland.AuthTicketService tickets;
 public NugetUiKeyResource(NugetKeyManager keys,org.sonatype.nexus.common.wonderland.AuthTicketService tickets){this.keys=keys;this.tickets=tickets;}
 @GET @RequiresAuthentication @RequiresPermissions("nexus:apikey:read") public Map<String,String> get(@QueryParam("authToken")String token){check(token);return Map.of("apiKey",keys.get(false));}
 @DELETE @RequiresAuthentication @RequiresPermissions("nexus:apikey:delete") public Map<String,String> reset(@QueryParam("authToken")String token){check(token);return Map.of("apiKey",keys.get(true));}
 private void check(String value){if(value==null||value.length()>4096)throw new ForbiddenException("Authentication ticket required");String token;try{token=new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw new ForbiddenException("Invalid authentication ticket");}if(!tickets.redeemTicket(token))throw new ForbiddenException("Invalid authentication ticket");}
}

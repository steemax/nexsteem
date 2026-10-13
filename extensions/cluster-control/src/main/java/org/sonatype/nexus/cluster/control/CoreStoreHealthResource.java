/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.rest.Resource;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;

/** A started web server is insufficient: every configured replicated blob store must actually be started. */
@Component @ConditionalOnProperty(name="nexus.cluster.enabled",havingValue="true")
@Path("/v1/cluster/core-ready") @Produces("application/json")
public final class CoreStoreHealthResource implements Resource {
  private final BlobStoreManager stores;
  private final byte[] key;
  private final String cluster,owner,pgTerm;
  private final long epoch;
  public CoreStoreHealthResource(BlobStoreManager stores,@Value("${nexus.cluster.id}")String cluster,
      @Value("${nexus.cluster.owner}")String owner,@Value("${nexus.cluster.epoch}")long epoch,
      @Value("${nexus.cluster.pg-term}")String pgTerm,
      @Value("${NX_CLUSTER_SECRET_FILE}")String secretFile)throws Exception {
    this.stores=stores;this.pgTerm=pgTerm;this.cluster=cluster;this.owner=owner;this.epoch=epoch;this.key=Files.readAllBytes(java.nio.file.Path.of(secretFile));
  }
  @GET public Response health(@HeaderParam("X-Nexus-Health-Time")String time,
      @HeaderParam("X-Nexus-Health-Nonce")String nonce,@HeaderParam("X-Nexus-Health-Signature")String signature){
    try {
      if(time==null||nonce==null||signature==null||!nonce.matches("[a-f0-9-]{36}")||Math.abs(Instant.now().getEpochSecond()-Long.parseLong(time))>10
          ||!MessageDigest.isEqual(CoreHealthProof.sign(key,cluster,owner,epoch,pgTerm,time,nonce).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))return Response.status(403).build();
      var defaultStore=stores.get("default");boolean ready=defaultStore!=null;
      for(var store:stores.browse())ready=ready&&store.isStarted()&&store.isWritable()&&store.isStorageAvailable()&&"ClusterLocal".equals(store.getBlobStoreConfiguration().getType());
      return Response.status(ready?200:503).header("Cache-Control","no-store").entity(Map.of("ready",ready,"epoch",epoch,"pgTerm",pgTerm)).build();
    }catch(Exception unavailable){return Response.status(503).header("Cache-Control","no-store").entity(Map.of("ready",false)).build();}
  }
}

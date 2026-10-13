/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.extensions.health;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.*;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.rest.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real database + filesystem readiness. Public response exposes no internals. */
@Component @jakarta.ws.rs.Path("/v1/open/health") @Produces(MediaType.APPLICATION_JSON)
public final class OpenHaHealthResource implements Resource {
  private final DataStoreManager stores;
  private final Path directory;
  private final String keyFile;
  private final long minimumFreeBytes;
  private final ThreadPoolExecutor probes=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<>(),task->{var thread=new Thread(task,"nexus-open-health");thread.setDaemon(true);return thread;});
  private volatile long lastSuccessfulProbe;
  public OpenHaHealthResource(DataStoreManager stores,@Value("${karaf.data}")String data,
      @Value("${nexus.secrets.file:}")String keyFile,
      @Value("${nexus.open.health.minimum-free-bytes:67108864}")long minimumFreeBytes) {
    this.stores=stores;this.directory=Path.of(data);this.keyFile=keyFile;this.minimumFreeBytes=minimumFreeBytes;
    if(minimumFreeBytes<0)throw new IllegalArgumentException("Negative free space threshold");
  }
  @GET public Response health() {
    boolean ready=false;
    if(lastSuccessfulProbe!=0&&System.nanoTime()-lastSuccessfulProbe<TimeUnit.SECONDS.toNanos(1))ready=true;
    else {
      Future<Boolean> probe=null;
      try {
        probe=probes.submit(this::probe);ready=probe.get(5,TimeUnit.SECONDS);
        if(ready)lastSuccessfulProbe=System.nanoTime();
      } catch(Exception unavailable) {
        if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
        if(probe!=null)probe.cancel(true);
      }
    }
    return Response.status(ready?200:503).header("Cache-Control","no-store").entity(Map.of("ready",ready)).build();
  }
  private boolean probe() throws Exception {
    var store=stores.get(DataStoreManager.DEFAULT_DATASTORE_NAME).orElse(null);
    if(store==null||!store.isStarted())return false;
    try(var connection=store.openConnection();var statement=connection.createStatement()) {
      if(connection.isReadOnly())return false;
      statement.setQueryTimeout(3);
      try(var result=statement.executeQuery("SELECT 1")){if(!result.next()||result.getInt(1)!=1)return false;}
      if(connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"))
        try(var result=statement.executeQuery("SELECT pg_is_in_recovery() OR current_setting('transaction_read_only')::boolean")){if(!result.next()||result.getBoolean(1))return false;}
    }
    if(!Files.isDirectory(directory)||!Files.isWritable(directory)||Files.getFileStore(directory).getUsableSpace()<minimumFreeBytes)return false;
    if(!keyFile.isBlank()&&(!Files.isRegularFile(Path.of(keyFile))||!Files.isReadable(Path.of(keyFile))))return false;
    Path temporary=directory.resolve("tmp");Files.createDirectories(temporary);
    Path file=Files.createTempFile(temporary,".nexus-health-",".probe");
    try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)) {
      channel.write(ByteBuffer.wrap(new byte[]{1}));channel.force(true);
    } finally {Files.deleteIfExists(file);}
    return true;
  }
  @PreDestroy public void stop(){probes.shutdownNow();}
}

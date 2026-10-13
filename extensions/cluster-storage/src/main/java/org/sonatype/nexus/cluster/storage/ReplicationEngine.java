/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Shared by the Nexus SPI and the always-running lightweight replica process. */
public final class ReplicationEngine
{
  private static final System.Logger log = System.getLogger(ReplicationEngine.class.getName());
  final ClusterSettings settings;
  final ClusterJournal journal;
  final DurableObjects objects;
  final ReplicaTransport transport;
  final String generation;
  private volatile boolean initiallyVerified;
  private long verifyCursor;
  private long scrubCursor;
  private long lastScrub;
  private final java.util.concurrent.ExecutorService stages = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

  public ReplicationEngine(final ClusterSettings settings, final ClusterJournal journal,
      final DurableObjects objects, final ReplicaTransport transport) throws IOException
  {
    this.settings = settings; this.journal = journal; this.objects = objects; this.transport = transport;
    generation = objects.generation();
  }

  public BlobVersion create(final BlobVersion version, final InputStream bytes) throws IOException, SQLException {
    if (!journal.validWriter(settings.cluster, settings.owner, settings.epoch)) {
      throw new IOException("Inactive or fenced Nexus writer");
    }
    BlobVersion content = objects.ingest(bytes);
    version.sha256 = content.sha256; version.sha1 = content.sha1; version.size = content.size;
    return mutate(version);
  }

  public BlobVersion mutate(final BlobVersion version) throws IOException, SQLException {
    version.cluster = settings.cluster; version.epoch = settings.epoch; version.owner = settings.owner;
    version.changed = System.currentTimeMillis();
    if (!journal.validWriter(settings.cluster, settings.owner, settings.epoch)) {
      throw new IOException("Inactive or fenced Nexus writer");
    }
    if (!"HARD_DELETED".equals(version.state) && !objects.has(version)) { repair(version); }
    version.sequence = 0;
    journal.prepare(version);
    objects.record(version);
    journal.acknowledge(version.sequence, settings.node, generation);
    java.util.Map<String, String> liveGenerations = new java.util.LinkedHashMap<>();
    liveGenerations.put(settings.node, generation);
    ClusterJournal.DurabilityPolicy policy = journal.policy(settings.cluster, settings.epoch);
    java.util.concurrent.CompletionService<StageAck> completion = new java.util.concurrent.ExecutorCompletionService<>(stages);
    List<java.util.concurrent.Future<StageAck>> pending = new ArrayList<>();
    if (!satisfies(policy, liveGenerations)) {
      for (String member : settings.members.keySet()) {
        if (member.equals(settings.node)) { continue; }
        pending.add(completion.submit(() -> {
          try { return new StageAck(member, transport.stage(member, version, objects.object(version.sha256))); }
          catch (IOException failed) {
            if (!Thread.currentThread().isInterrupted()) {
              int status = failed instanceof ReplicaTransport.StageHttpException ? ((ReplicaTransport.StageHttpException) failed).status : 0;
              log.log(System.Logger.Level.WARNING,"Replica stage failed seq={0} node={1} exception={2} http={3}",
                  version.sequence,member,failed.getClass().getSimpleName(),status);
            }
            throw failed;
          }
        }));
      }
      long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(settings.transferTimeoutSeconds);
      int remaining = pending.size();
      try {
        while (!satisfies(policy, liveGenerations) && remaining > 0) {
          long wait = deadline - System.nanoTime();
          if (wait <= 0) { break; }
          java.util.concurrent.Future<StageAck> finished = completion.poll(wait, java.util.concurrent.TimeUnit.NANOSECONDS);
          if (finished == null) { break; }
          remaining--;
          try { StageAck ack = finished.get(); liveGenerations.put(ack.node(), ack.generation()); }
          catch (java.util.concurrent.ExecutionException failedReplica) { /* another configured replica may satisfy policy */ }
        }
      }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Blob quorum wait interrupted", e); }
      finally { for (var future : pending) { if (!future.isDone()) { future.cancel(true); } } }
    }
    // Upload and fsync happen before this short row lock, so lease heartbeats are never held behind an upload.
    try (ClusterJournal.WriteFence fence = journal.beginWrite(settings.cluster, settings.owner, settings.epoch)) {
      fence.lockObject(version.sha256);
      if (!"HARD_DELETED".equals(version.state) && !objects.has(version)) {
        throw new IOException("Local durable copy disappeared before publication");
      }
      fence.publish(version, settings.node, liveGenerations, settings.domains);
    }
    return version;
  }

  private boolean satisfies(final ClusterJournal.DurabilityPolicy policy, final java.util.Map<String, String> copies) {
    if (copies.size() < policy.copies) { return false; }
    return !policy.remote || copies.keySet().stream().anyMatch(member ->
        !settings.domains.get(settings.node).equals(settings.domains.get(member)));
  }

  private record StageAck(String node, String generation) {}

  public void close() { stages.shutdownNow(); }

  public void repair(final BlobVersion version) throws SQLException, IOException {
    if ("HARD_DELETED".equals(version.state)) { objects.record(version); return; }
    if (objects.has(version)) { objects.record(version); return; }
    List<String> replicas = journal.replicas(version.sequence);
    for (String member : replicas) {
      if (member.equals(settings.node) || !settings.members.containsKey(member)) { continue; }
      try (InputStream bytes = transport.download(member, version)) {
        objects.receive(version, bytes); journal.acknowledge(version.sequence, settings.node, generation); return;
      }
      catch (IOException e) { /* try another acknowledged member; never return unverified bytes */ }
    }
    throw new IOException("No durable replica available for committed blob version " + version.sequence);
  }

  public int catchUp() throws SQLException, IOException {
    List<BlobVersion> missing = journal.missing(settings.cluster, settings.node, generation, 100);
    for (BlobVersion v : missing) {
      repair(v);
      objects.record(v);
      journal.acknowledge(v.sequence, settings.node, generation);
    }
    if (!initiallyVerified) {
      List<BlobVersion> batch = journal.headsAfter(settings.cluster, verifyCursor, 100);
      for (BlobVersion v : batch) { repair(v); verifyCursor = v.sequence; }
      if (batch.size() < 100) { initiallyVerified = true; }
    }
    else if (System.currentTimeMillis() - lastScrub > 30000) {
      List<BlobVersion> batch = journal.headsAfter(settings.cluster, scrubCursor, 32);
      for (BlobVersion v : batch) {
        if (!"HARD_DELETED".equals(v.state) && !objects.scrub(v)) { repair(v); }
        scrubCursor = v.sequence;
      }
      if (batch.size() < 32) { scrubCursor = 0; }
      lastScrub = System.currentTimeMillis();
    }
    return missing.size();
  }

  public boolean caughtUp() throws SQLException, IOException {
    return initiallyVerified && !journal.hasPendingHeads(settings.cluster, settings.node, generation);
  }

  public long collectGarbage(final java.time.Duration retention) throws SQLException, IOException {
    if (!caughtUp()) { return 0; }
    long minimum = java.time.Duration.ofHours(24).toMillis();
    long before = System.currentTimeMillis() - Math.max(minimum, retention.toMillis());
    long removed = 0;
    try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(objects.root().resolve("objects"))) {
      java.util.Iterator<java.nio.file.Path> paths = files.filter(java.nio.file.Files::isRegularFile).iterator();
      while (paths.hasNext()) {
        java.nio.file.Path path = paths.next();
        String name = path.getFileName().toString();
        if (!name.matches("[a-f0-9]{64}\\.bytes") || java.nio.file.Files.getLastModifiedTime(path).toMillis() > before) { continue; }
        long size = java.nio.file.Files.size(path);
        if (journal.purgeUnreferenced(settings.cluster, name.substring(0, 64), path, before)) { removed += size; }
      }
    }
    journal.pruneHistory(settings.cluster);
    try (java.util.stream.Stream<java.nio.file.Path> manifests = java.nio.file.Files.list(objects.root().resolve("journal"))) {
      int checked = 0;
      java.util.Iterator<java.nio.file.Path> paths = manifests.iterator();
      while (paths.hasNext()) {
        java.nio.file.Path path = paths.next();
        String name = path.getFileName().toString();
        if (!name.matches("[1-9][0-9]{0,18}\\.json") || java.nio.file.Files.getLastModifiedTime(path).toMillis() > before) { continue; }
        if (journal.event(Long.parseLong(name.substring(0, name.length()-5)), false) == null) { java.nio.file.Files.deleteIfExists(path); }
        if (++checked >= 1000) { break; }
      }
    }
    DurableObjects.forceDirectory(objects.root().resolve("journal"));
    return removed;
  }

  public boolean quorumAvailable() {
    ClusterJournal.DurabilityPolicy policy;
    try { policy = journal.policy(settings.cluster, settings.epoch); }
    catch (SQLException unavailable) { return false; }
    if (policy.copies == 1 && !policy.remote) {
      try { return objects.available(); }
      catch (IOException unavailable) { return false; }
    }
    int reachable = 1;
    boolean remote = false;
    for (String member : settings.members.keySet()) {
      if (member.equals(settings.node)) { continue; }
      ReplicaTransport.PeerStatus peer;
      try { peer = transport.status(member); } catch (IOException unavailable) { continue; }
      if (peer.caughtUp() && peer.epoch() == settings.epoch && peer.owner().equals(settings.owner)) {
        reachable++;
        if (!settings.domains.get(settings.node).equals(settings.domains.get(member))) { remote = true; }
      }
    }
    return reachable >= policy.copies && (!policy.remote || remote);
  }
}

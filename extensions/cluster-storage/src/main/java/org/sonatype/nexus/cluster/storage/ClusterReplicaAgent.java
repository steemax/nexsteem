/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.cluster.api.ReplicaAgent;
import org.sonatype.nexus.cluster.api.ReplicaStatus;

public final class ClusterReplicaAgent implements ReplicaAgent
{
  private final ReplicationEngine engine;
  private final ReplicaServer server;
  private final ScheduledExecutorService recovery = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService statistics = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService garbage = Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService availability = Executors.newSingleThreadScheduledExecutor();
  private volatile String recoveryDetail = "Recovery has not run";
  private volatile long activeEpoch;
  private volatile String activeOwner;
  private volatile long pendingHeads = -1;
  private volatile PeerCapacity capacity = new PeerCapacity(0,"",1,false,0);

  public ClusterReplicaAgent(final ReplicationEngine engine, final ReplicaServer server) {
    this.engine = engine; this.server = server;
    activeEpoch = engine.settings.epoch; activeOwner = engine.settings.owner;
  }

  @Override public void start() {
    server.start();
    recovery.scheduleWithFixedDelay(() -> {
      try {
        while (engine.catchUp() == 100) { /* bounded journal batches */ }
        recoveryDetail = "Committed heads recovered";
      }
      catch (Exception pending) { recoveryDetail = "Committed blob recovery pending"; }
    }, 0, 2, TimeUnit.SECONDS);
    statistics.scheduleWithFixedDelay(() -> {
      try { pendingHeads = engine.journal.pendingHeads(engine.settings.cluster, engine.settings.node, engine.generation); }
      catch (Exception unavailable) { /* Diagnostic count remains stale; eligibility always uses a fresh SQL EXISTS. */ }
    }, 0, 30, TimeUnit.SECONDS);
    garbage.scheduleWithFixedDelay(() -> {
      try { engine.collectGarbage(java.time.Duration.ofHours(24)); }
      catch (Exception unavailable) { /* Do not hold catch-up behind a large filesystem/GC scan. */ }
    }, 5, 5, TimeUnit.MINUTES);
    availability.scheduleWithFixedDelay(this::refreshCapacity, 0, 2, TimeUnit.SECONDS);
  }

  @Override public boolean isCaughtUp() throws Exception { return engine.caughtUp(); }

  @Override public boolean fencePeers(final long epoch, final String owner) throws Exception {
    engine.journal.assertCurrent(engine.settings.cluster, owner, epoch);
    ClusterJournal.DurabilityPolicy policy = engine.journal.policy(engine.settings.cluster, epoch);
    server.persistFence(epoch, owner);
    int copies = 1;
    boolean remote = false;
    for (String member : engine.settings.members.keySet()) {
      if (member.equals(engine.settings.node)) { continue; }
      try {
        engine.transport.fence(member, epoch, owner);
        ReplicaTransport.PeerStatus state = engine.transport.status(member);
        if (!state.caughtUp() || state.epoch() != epoch || !owner.equals(state.owner())) { continue; }
        copies++;
        if (!engine.settings.domains.get(member).equals(engine.settings.domains.get(engine.settings.node))) { remote = true; }
      }
      catch (java.io.IOException unavailable) { /* quorum policy below fails closed */ }
    }
    activeEpoch = epoch; activeOwner = owner;
    capacity = new PeerCapacity(epoch,owner,copies,remote,System.nanoTime());
    return copies >= policy.copies && (!policy.remote || remote) && isCaughtUp();
  }

  /** A peer restored after degraded startup must receive the current durable fence before it can be counted. */
  private void refreshCapacity() {
    try {
      long epoch = engine.journal.currentEpoch(engine.settings.cluster);
      String owner = engine.journal.currentOwner(engine.settings.cluster);
      int available = 1; boolean remote = false;
      for (String member : engine.settings.members.keySet()) {
        if (member.equals(engine.settings.node)) { continue; }
        try {
          ReplicaTransport.PeerStatus state = engine.transport.status(member);
          if (!state.caughtUp()) { continue; }
          if (epoch > 0 && (state.epoch() != epoch || !owner.equals(state.owner()))) {
            engine.journal.assertCurrent(engine.settings.cluster,owner,epoch);
            engine.transport.fence(member,epoch,owner);
            state = engine.transport.status(member);
          }
          if (state.caughtUp() && state.epoch() == epoch && owner.equals(state.owner())) {
            available++;
            if (!engine.settings.domains.get(member).equals(engine.settings.domains.get(engine.settings.node))) { remote = true; }
          }
        }
        catch (Exception unavailable) { /* missing peers reduce cached capacity, never relax the authoritative policy */ }
      }
      capacity = new PeerCapacity(epoch,owner,available,remote,System.nanoTime());
    }
    catch (Exception unavailable) { /* stale cache is excluded from readiness below */ }
  }

  private record PeerCapacity(long epoch,String owner,int copies,boolean remote,long measuredNanos) {}

  @Override public ReplicaStatus status() {
    try {
      long epoch = engine.journal.currentEpoch(engine.settings.cluster);
      String expectedOwner = engine.journal.currentOwner(engine.settings.cluster);
      ClusterJournal.DurabilityPolicy policy = epoch > 0 ? engine.journal.policy(engine.settings.cluster, epoch)
          : new ClusterJournal.DurabilityPolicy(engine.settings.copies, true, false);
      PeerCapacity cached = capacity;
      boolean fresh = cached.epoch()==epoch && cached.owner().equals(expectedOwner)
          && System.nanoTime()-cached.measuredNanos() < TimeUnit.SECONDS.toNanos(10);
      int available = fresh ? cached.copies() : 1;
      boolean remote = fresh && cached.remote();
      boolean caughtUp = isCaughtUp();
      boolean memberReady = engine.objects.available();
      long durable = ((Number) server.readFence().get("epoch")).longValue();
      return new ReplicaStatus(memberReady, caughtUp, memberReady && available >= policy.copies && (!policy.remote || remote),
          durable, policy.copies, available, policy.degraded, recoveryDetail + "; pendingHeads=" +
              (pendingHeads < 0 ? "unknown" : Long.toString(pendingHeads)));
    }
    catch (Exception unavailable) { return new ReplicaStatus(false, false, false, 0, 0, 0, false, "Replication authority unavailable"); }
  }

  @Override public void close() {
    recovery.shutdownNow(); statistics.shutdownNow(); garbage.shutdownNow(); availability.shutdownNow(); server.close(); engine.close();
  }
}

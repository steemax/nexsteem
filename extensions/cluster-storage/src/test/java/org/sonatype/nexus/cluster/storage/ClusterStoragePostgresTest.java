/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL guards plus three authenticated HTTP replica servers, including Nexus's actual BlobStore SPI. */
public class ClusterStoragePostgresTest
{
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test public void spiReplicationQuorumDegradedPvcReplacementAndGc() throws Exception {
    String url = System.getenv("NX_STORAGE_TEST_JDBC_URL"); Assume.assumeTrue(url != null && !url.isBlank());
    var source = new StandaloneReplicaMain.JdbcSource(url, "postgres", System.getenv("NX_STORAGE_TEST_PASSWORD"));
    String cluster = "storage-" + UUID.randomUUID();
    String owner = "a:fixture-" + UUID.randomUUID();
    try (Connection c = source.getConnection(); var s = c.createStatement()) {
      s.execute("DO $$ BEGIN CREATE ROLE nexus; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
      s.execute("DO $$ BEGIN CREATE ROLE nxcontrol; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
      s.execute(Files.readString(Path.of("/src/cluster-control.sql")));
      try (var in = getClass().getClassLoader().getResourceAsStream("sql/cluster-storage.sql")) {
        s.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      try (var p = c.prepareStatement("INSERT INTO nx_cluster_leader VALUES(?,1,?,clock_timestamp()+interval '5 minutes','http://a','READY')")) {
        p.setString(1, cluster); p.setString(2, owner); p.execute();
      }
      try (var p = c.prepareStatement("INSERT INTO nx_cluster_lease VALUES(?,1,?,clock_timestamp()+interval '5 minutes')")) {
        p.setString(1, cluster); p.setString(2, owner); p.execute();
      }
      try (var p = c.prepareStatement("INSERT INTO nx_cluster_policy VALUES(?,1,2,true,false,'fixture-dcs-r1')")) {
        p.setString(1, cluster); p.execute();
      }
    }
    ClusterJournal journal = new ClusterJournal(source); journal.initialize();
    Path key = temp.newFile("secret").toPath(); Files.write(key, new byte[32]);
    Path[] roots = {temp.newFolder("a").toPath(), temp.newFolder("b").toPath(), temp.newFolder("c").toPath()};
    int[] ports = new int[3];
    for (int i = 0; i < 3; i++) { try (java.net.ServerSocket s = new java.net.ServerSocket(0)) { ports[i] = s.getLocalPort(); } }
    String peers = "{\"a\":{\"url\":\"http://127.0.0.1:" + ports[0] + "\",\"dc\":\"dc1\"}," +
        "\"b\":{\"url\":\"http://127.0.0.1:" + ports[1] + "\",\"dc\":\"dc2\"}," +
        "\"c\":{\"url\":\"http://127.0.0.1:" + ports[2] + "\",\"dc\":\"dc1\"}}";
    Properties[] config = new Properties[3]; ReplicationEngine[] engines = new ReplicationEngine[3];
    ReplicaServer[] servers = new ReplicaServer[3];
    Map<String, String> saved = new LinkedHashMap<>(); ClusterBlobStore spi = null;
    try {
      for (int i = 0; i < 3; i++) {
        config[i] = settings(cluster, owner, "" + (char) ('a' + i), ports[i], roots[i], key, peers);
        ClusterSettings set = new ClusterSettings(config[i]);
        engines[i] = new ReplicationEngine(set, journal, new DurableObjects(roots[i]), new ReplicaTransport(set, null));
        engines[i].catchUp();
        servers[i] = new ReplicaServer(engines[i], null); servers[i].start();
      }
      for (String k : config[0].stringPropertyNames()) { saved.put(k, System.getProperty(k)); System.setProperty(k, config[0].getProperty(k)); }
      DataStoreManager stores = mock(DataStoreManager.class); DataStore<?> store = mock(DataStore.class);
      when(store.getDataSource()).thenReturn(source); doReturn(java.util.Optional.of(store)).when(stores).get("nexus");
      BlobStoreConfiguration blobConfig = mock(BlobStoreConfiguration.class); when(blobConfig.getName()).thenReturn("default");
      spi = new ClusterBlobStore(stores); spi.init(blobConfig); spi.start();
      byte[] bytes = "genuine-artifact-payload".repeat(10000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Map<String, String> headers = Map.of(BlobStore.BLOB_NAME_HEADER, "artifact.dat", BlobStore.CREATED_BY_HEADER, "fixture");
      Blob first = spi.create(new ByteArrayInputStream(bytes), headers);
      BlobVersion head = journal.head(cluster, "default", first.getId().asUniqueString());
      assertTrue(journal.replicas(head.sequence).containsAll(java.util.List.of("a", "b")));
      assertTrue(engines[1].objects.has(head)); engines[2].catchUp(); assertTrue(engines[2].objects.has(head));
      try (var in = spi.get(first.getId()).getInputStream()) { assertArrayEquals(bytes, in.readAllBytes()); }
      Blob copy = spi.copy(first.getId(), Map.of(BlobStore.BLOB_NAME_HEADER, "copy.dat", BlobStore.CREATED_BY_HEADER, "fixture"));
      assertNotEquals(first.getId(), copy.getId()); assertEquals("copy.dat", copy.getHeaders().get(BlobStore.BLOB_NAME_HEADER));
      try (var in = copy.getInputStream()) { assertArrayEquals(bytes, in.readAllBytes()); }

      // Two same-DC copies are insufficient under the normal cross-DC policy.
      servers[1].close(); servers[1] = null;
      BlobId rejected = new BlobId(UUID.randomUUID().toString());
      try { spi.create(new ByteArrayInputStream(bytes), headers, rejected); fail("Missing remote DC accepted"); }
      catch (BlobStoreException expected) {}
      assertNull(journal.head(cluster, "default", rejected.asUniqueString()));

      // Only a SQL-authoritative policy with proof can enable the user-approved degraded writes.
      setPolicy(source, cluster, owner, true);
      servers[2].close(); servers[2] = null;
      Blob degraded;
      try (FaultPeer blackHole = new FaultPeer(ports[2])) {
        long started = System.nanoTime();
        assertTrue("Degraded local writer readiness waited on peer probes", spi.isStorageAvailable());
        degraded = spi.create(new ByteArrayInputStream(bytes), headers);
        assertTrue("Degraded upload waited for an unnecessary remote", System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
        assertEquals("Degraded1 policy must not send replica requests", 0, blackHole.requests.get());
      }
      assertNotNull(spi.get(degraded.getId()));
      assertTrue(spi.delete(first.getId(), "fixture removal")); assertNull(spi.get(first.getId()));
      assertNotNull(spi.get(first.getId(), true)); assertTrue(spi.deleteHard(first.getId()));

      // A replaced PVC has a new generation; old ACKs do not assert any local bytes.
      String oldGeneration = engines[1].generation;
      Path replacement = temp.newFolder("replacement-b").toPath(); config[1].setProperty("NX_CLUSTER_ROOT", replacement.toString());
      ClusterSettings set = new ClusterSettings(config[1]);
      engines[1] = new ReplicationEngine(set, journal, new DurableObjects(replacement), new ReplicaTransport(set, null));
      assertNotEquals(oldGeneration, engines[1].generation); assertFalse(engines[1].caughtUp());
      assertFalse(journal.missing(cluster, "b", engines[1].generation, 10).isEmpty());
      servers[1] = new ReplicaServer(engines[1], null); servers[1].start();
      while (!engines[1].caughtUp()) { engines[1].catchUp(); }
      BlobVersion live = journal.head(cluster, "default", degraded.getId().asUniqueString());
      assertTrue(engines[1].objects.has(live));
      assertEquals("HARD_DELETED", journal.head(cluster, "default", first.getId().asUniqueString()).state);
      setPolicy(source, cluster, owner, false);
      Blob restored;
      try (FaultPeer blackHole = new FaultPeer(ports[2])) {
        long started = System.nanoTime();
        restored = spi.create(new ByteArrayInputStream(bytes), headers);
        assertTrue("Normal upload waited after a sufficient remote ACK", System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
      }
      assertNotNull(spi.get(restored.getId()));

      // Authentication rejects unauthenticated requests on the actual HTTP endpoint.
      var unauth = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
          java.net.URI.create("http://127.0.0.1:" + ports[1] + "/v1/status")).GET().build(), java.net.http.HttpResponse.BodyHandlers.discarding());
      assertEquals(401, unauth.statusCode());

      // An old generation cannot be counted toward a fresh publication.
      BlobVersion ghost = live.copy(); ghost.blob = UUID.randomUUID().toString(); ghost.epoch = 1; ghost.owner = owner; ghost.sequence = 0;
      journal.prepare(ghost); journal.acknowledge(ghost.sequence, "a", engines[0].generation);
      journal.acknowledge(ghost.sequence, "b", oldGeneration);
      try (var fence = journal.beginWrite(cluster, owner, 1)) {
        try { fence.publish(ghost, "a", Map.of("a", engines[0].generation, "b", engines[1].generation), engines[0].settings.domains);
          fail("Old PVC generation counted"); }
        catch (java.sql.SQLException expected) {}
      }
      assertNull(journal.head(cluster, "default", ghost.blob));

      // GC keeps old bytes pinned by a fresh PREPARED event, and preserves live SQL heads.
      Path orphan = engines[0].objects.object(live.sha256);
      Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - java.time.Duration.ofDays(2).toMillis()));
      assertFalse(journal.purgeUnreferenced(cluster, live.sha256, orphan, System.currentTimeMillis()));

      BlobVersion pin = engines[0].objects.ingest(new ByteArrayInputStream("unfinished-pinned-upload".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      pin.cluster = cluster; pin.store = "default"; pin.blob = UUID.randomUUID().toString(); pin.owner = owner; pin.epoch = 1;
      journal.prepare(pin);
      Path pinnedObject = engines[0].objects.object(pin.sha256);
      Files.setLastModifiedTime(pinnedObject, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - java.time.Duration.ofDays(2).toMillis()));
      assertFalse(journal.purgeUnreferenced(cluster, pin.sha256, pinnedObject, System.currentTimeMillis()));
      try (Connection c = source.getConnection(); var p = c.prepareStatement("UPDATE nx_cluster_blob_events SET created_at=clock_timestamp()-interval '8 days' WHERE seq=?")) {
        p.setLong(1, pin.sequence); p.execute();
      }
      assertTrue(journal.purgeUnreferenced(cluster, pin.sha256, pinnedObject, System.currentTimeMillis()));
      assertFalse(Files.exists(pinnedObject));
      journal.pruneHistory(cluster); assertNull(journal.event(pin.sequence, false));

      // All format tables get the SQL/blob attachment guard, including tables created after bootstrap.
      String table = "fixture_" + cluster.replace('-', '_') + "_asset_blob";
      try (Connection c = source.getConnection(); var s = c.createStatement()) {
        s.execute("CREATE TABLE " + table + "(blob_ref varchar PRIMARY KEY)");
        try (var bind = c.prepareStatement("SELECT nx_cluster_bind(?,?,1)")) { bind.setString(1, cluster); bind.setString(2, owner); bind.execute(); }
        try (var p = c.prepareStatement("INSERT INTO " + table + " VALUES (?)")) {
          p.setString(1, "default@" + UUID.randomUUID());
          try { p.execute(); fail("Dangling SQL blob reference accepted"); }
          catch (java.sql.SQLException expected) { assertTrue(expected.getMessage().contains("NX_CLUSTER_BLOB_NOT_COMMITTED")); }
        }
        byte[] referencedBytes = "referenced-object-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Blob referenced = spi.create(new ByteArrayInputStream(referencedBytes), headers);
        BlobVersion ref = journal.head(cluster, "default", referenced.getId().asUniqueString());
        try (var p = c.prepareStatement("INSERT INTO " + table + " VALUES (?)")) { p.setString(1, "default@" + ref.blob); p.execute(); }
        assertTrue(journal.isReferenced(ref));
        spi.deleteHard(referenced.getId());
        Path referencedObject = engines[0].objects.object(ref.sha256);
        Files.setLastModifiedTime(referencedObject, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - java.time.Duration.ofDays(2).toMillis()));
        assertFalse(journal.purgeUnreferenced(cluster, ref.sha256, referencedObject, System.currentTimeMillis()));
        try (var p = c.prepareStatement("DELETE FROM " + table + " WHERE blob_ref=?")) { p.setString(1, "default@" + ref.blob); p.execute(); }
        assertFalse(journal.isReferenced(ref));
        assertTrue(journal.purgeUnreferenced(cluster, ref.sha256, referencedObject, System.currentTimeMillis()));
      }

      // A normal-policy peer is not counted before a new PVC has caught up all committed heads.
      Path pending = temp.newFolder("pending-c").toPath(); config[2].setProperty("NX_CLUSTER_ROOT", pending.toString());
      ClusterSettings pendingSettings = new ClusterSettings(config[2]);
      ReplicationEngine pendingEngine = new ReplicationEngine(pendingSettings, journal, new DurableObjects(pending), new ReplicaTransport(pendingSettings, null));
      ReplicaServer pendingServer = new ReplicaServer(pendingEngine, null); servers[2] = pendingServer; pendingServer.start();
      pendingServer.persistFence(1, owner);
      assertFalse(engines[0].transport.status("c").caughtUp());
      while (!pendingEngine.caughtUp()) { pendingEngine.catchUp(); }
      assertTrue(engines[0].transport.status("c").caughtUp());

      // Default bootstrap and all subsequent blob stores in this profile must be replicated.
      try (Connection c = source.getConnection(); var s = c.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS blob_store_configuration(id uuid PRIMARY KEY,name varchar UNIQUE,type varchar,attributes jsonb)");
        try (var bind = c.prepareStatement("SELECT nx_cluster_bind(?,?,1)")) { bind.setString(1, cluster); bind.setString(2, owner); bind.execute(); }
        try (var p = c.prepareStatement("INSERT INTO blob_store_configuration VALUES (?,?,'File','{}')")) {
          p.setObject(1, UUID.randomUUID()); p.setString(2, "unreplicated-" + cluster);
          try { p.execute(); fail("Unreplicated file store accepted"); }
          catch (java.sql.SQLException expected) { assertEquals("23514", expected.getSQLState()); }
        }
        try (var p = c.prepareStatement("INSERT INTO blob_store_configuration VALUES (?,?,'ClusterLocal','{}')")) {
          p.setObject(1, UUID.randomUUID()); p.setString(2, "replicated-" + cluster); p.execute();
        }
      }

      // Enumeration traverses multiple pages without loading all manifests, and SQL metrics keep exact totals.
      long beforeCount = spi.getMetrics().getBlobCount(), beforeSize = spi.getMetrics().getTotalSize();
      java.util.Set<String> added = new java.util.HashSet<>();
      for (int i = 0; i < 205; i++) {
        added.add(spi.create(new ByteArrayInputStream(bytes), headers).getId().asUniqueString());
      }
      assertEquals(beforeCount + 205, spi.getMetrics().getBlobCount());
      assertEquals(beforeSize + 205L * bytes.length, spi.getMetrics().getTotalSize());
      try (var ids = spi.getBlobIdStream()) {
        java.util.Set<String> enumerated = ids.map(BlobId::asUniqueString).collect(java.util.stream.Collectors.toSet());
        assertTrue(enumerated.containsAll(added));
      }

      // A late restored remote peer has verified data but fence=0; background convergence makes it countable.
      servers[1].close(); servers[1] = null; servers[2].close(); servers[2] = null;
      setPolicy(source, cluster, owner, true);
      servers[0].close(); servers[0] = null;
      ReplicaServer agentServer = new ReplicaServer(engines[0], null);
      try (ClusterReplicaAgent agent = new ClusterReplicaAgent(engines[0], agentServer)) {
        agent.start(); assertTrue(agent.fencePeers(1, owner));
        Path lateRoot = temp.newFolder("late-b").toPath(); config[1].setProperty("NX_CLUSTER_ROOT", lateRoot.toString());
        ClusterSettings lateSettings = new ClusterSettings(config[1]);
        ReplicationEngine lateEngine = new ReplicationEngine(lateSettings, journal, new DurableObjects(lateRoot), new ReplicaTransport(lateSettings, null));
        ReplicaServer lateServer = new ReplicaServer(lateEngine, null); servers[1] = lateServer; lateServer.start();
        while (!lateEngine.caughtUp()) { lateEngine.catchUp(); }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (agent.status().availableCopies() < 2 && System.nanoTime() < deadline) { Thread.sleep(100); }
        assertEquals(2, agent.status().availableCopies());
        assertTrue(agent.status().degraded());
        assertEquals(1L, ((Number) lateServer.readFence().get("epoch")).longValue());
        setPolicy(source, cluster, owner, false);
        assertTrue(agent.fencePeers(1, owner)); assertTrue(agent.status().quorumReady());
        assertFalse(agent.status().degraded());
      }

      // Expired fixed epoch rejects SPI writes and cannot publish an event.
      try (Connection c = source.getConnection(); var p = c.prepareStatement("UPDATE nx_cluster_lease SET lease_until=clock_timestamp()-interval '1 second' WHERE cluster_id=?")) {
        p.setString(1, cluster); p.execute();
      }
      try { spi.create(new ByteArrayInputStream(bytes), headers); fail("Expired writer accepted"); }
      catch (BlobStoreException expected) {}
    }
    finally {
      if (spi != null) { spi.shutdown(); }
      for (ReplicaServer server : servers) { if (server != null) { server.close(); } }
      for (Map.Entry<String, String> e : saved.entrySet()) { if (e.getValue() == null) { System.clearProperty(e.getKey()); } else { System.setProperty(e.getKey(), e.getValue()); } }
    }
  }

  private void setPolicy(final StandaloneReplicaMain.JdbcSource source, final String cluster, final String owner,
      final boolean degraded) throws Exception
  {
    try (Connection c = source.getConnection(); var p = c.prepareStatement("SELECT nx_cluster_set_policy(?,?,1,?,?,?,'fixture-dcs-r2')")) {
      p.setString(1, cluster); p.setString(2, owner); p.setInt(3, degraded ? 1 : 2);
      p.setBoolean(4, !degraded); p.setBoolean(5, degraded); p.execute();
    }
  }

  private Properties settings(final String cluster, final String owner, final String node, final int port,
      final Path root, final Path key, final String peers)
  {
    Properties p = new Properties();
    p.setProperty("NX_CLUSTER_ID", cluster); p.setProperty("NX_CLUSTER_OWNER_ID", owner); p.setProperty("NX_CLUSTER_NODE_ID", node);
    p.setProperty("NX_CLUSTER_EPOCH", "1"); p.setProperty("NX_CLUSTER_ROOT", root.toString()); p.setProperty("NX_CLUSTER_SECRET_FILE", key.toString());
    p.setProperty("NX_CLUSTER_PEERS_JSON", peers); p.setProperty("NX_CLUSTER_PEER_PORT", Integer.toString(port));
    p.setProperty("NX_CLUSTER_ALLOW_HTTP_TEST", "true"); return p;
  }

  /** A real HTTP peer that accepts connections and headers, then stalls full uploads. */
  private static final class FaultPeer implements AutoCloseable {
    final java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
    private final com.sun.net.httpserver.HttpServer server;
    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    FaultPeer(final int port) throws Exception {
      server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", port), 16);
      server.setExecutor(executor);
      server.createContext("/", x -> {
        requests.incrementAndGet();
        try {
          if (x.getRequestURI().getPath().startsWith("/v1/retain/")) { x.sendResponseHeaders(404, -1); }
          else { try { new java.util.concurrent.CountDownLatch(1).await(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        }
        finally { x.close(); }
      });
      server.start();
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
  }
}

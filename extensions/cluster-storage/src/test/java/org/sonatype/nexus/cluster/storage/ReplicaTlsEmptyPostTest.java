/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;
import java.util.UUID;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Real TLS transport, fsync objects and PostgreSQL ACKs with the production zero-drain setting. */
public class ReplicaTlsEmptyPostTest
{
  @Rule public TemporaryFolder temp = new TemporaryFolder();

  @Test public void emptyRetainAndTombstonePostsKeepTlsConnectionUsable() throws Exception {
    String url = System.getenv("NX_STORAGE_TEST_JDBC_URL");
    Assume.assumeTrue(url != null && !url.isBlank());
    System.setProperty("sun.net.httpserver.drainAmount", "0");
    var source = new StandaloneReplicaMain.JdbcSource(url, "postgres", System.getenv("NX_STORAGE_TEST_PASSWORD"));
    String cluster = "tls-" + UUID.randomUUID(), owner = "a:fixture-" + UUID.randomUUID();
    try (var c = source.getConnection(); var s = c.createStatement()) {
      s.execute("DO $$ BEGIN CREATE ROLE nexus; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
      s.execute("DO $$ BEGIN CREATE ROLE nxcontrol; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
      s.execute(Files.readString(Path.of("/src/cluster-control.sql")));
      try (var in = getClass().getClassLoader().getResourceAsStream("sql/cluster-storage.sql")) {
        s.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      try (var p = c.prepareStatement("INSERT INTO nx_cluster_leader VALUES(?,1,?,clock_timestamp()+interval '15 minutes','https://a','READY')")) {
        p.setString(1, cluster); p.setString(2, owner); p.execute();
      }
      try (var p = c.prepareStatement("INSERT INTO nx_cluster_lease VALUES(?,1,?,clock_timestamp()+interval '15 minutes')")) {
        p.setString(1, cluster); p.setString(2, owner); p.execute();
      }
      try (var p = c.prepareStatement("INSERT INTO nx_cluster_policy VALUES(?,1,2,true,false,'fixture')")) {
        p.setString(1, cluster); p.execute();
      }
    }
    ClusterJournal journal = new ClusterJournal(source); journal.initialize();
    Path key = temp.newFile("peer-key").toPath(); Files.write(key, new byte[32]);
    int port;
    try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    Properties config = new Properties();
    config.setProperty("NX_CLUSTER_ID", cluster); config.setProperty("NX_CLUSTER_NODE_ID", "a");
    config.setProperty("NX_CLUSTER_OWNER_ID", owner); config.setProperty("NX_CLUSTER_EPOCH", "1");
    config.setProperty("NX_CLUSTER_ROOT", temp.newFolder("sender").toString());
    config.setProperty("NX_CLUSTER_SECRET_FILE", key.toString());
    config.setProperty("NX_CLUSTER_PEER_PORT", Integer.toString(port));
    config.setProperty("NX_CLUSTER_PEERS_JSON", "{\"a\":{\"url\":\"https://127.0.0.1:1\",\"dc\":\"dc1\"},\"b\":{\"url\":\"https://127.0.0.1:" + port + "\",\"dc\":\"dc2\"}}");
    ClusterSettings sender = new ClusterSettings(config);
    config.setProperty("NX_CLUSTER_NODE_ID", "b");
    config.setProperty("NX_CLUSTER_ROOT", temp.newFolder("receiver").toString());
    ClusterSettings receiver = new ClusterSettings(config);
    SSLContext tls = tlsContext();
    DurableObjects local = new DurableObjects(sender.root), remote = new DurableObjects(receiver.root);
    ReplicaTransport transport = new ReplicaTransport(sender, tls);
    ReplicationEngine engine = new ReplicationEngine(receiver, journal, remote, new ReplicaTransport(receiver, tls));
    try (ReplicaServer server = new ReplicaServer(engine, tls); HttpClient direct = HttpClient.newBuilder().sslContext(tls).build()) {
      server.start();
      engine.catchUp();
      PeerAuthentication auth = new PeerAuthentication(sender.key, cluster);
      for (int i = 0; i < 100; i++) {
        // Status, retain, download and the next stage all share ReplicaTransport's one TLS client.
        assertEquals(engine.generation, transport.status("b").generation());
        byte[] bytes = new byte[91000]; bytes[0] = (byte) i;
        BlobVersion v = local.ingest(new ByteArrayInputStream(bytes));
        v.cluster = cluster; v.store = "default"; v.blob = UUID.randomUUID().toString();
        v.owner = owner; v.epoch = 1; v.state = "LIVE";
        journal.prepare(v);
        local.record(v); journal.acknowledge(v.sequence, "a", local.generation());
        // Every first retain is a real 404; stage then transfers a previously absent file over reused TLS.
        assertEquals(engine.generation, transport.stage("b", v, local.object(v.sha256)));
        assertTrue(remote.has(v)); assertTrue(journal.replicas(v.sequence).contains("b"));
        try (var fence = journal.beginWrite(cluster, owner, 1)) {
          fence.lockObject(v.sha256);
          fence.publish(v, "a", java.util.Map.of("a", local.generation(), "b", engine.generation), sender.domains);
        }
        try (var downloaded = transport.download("b", v)) { assertArrayEquals(bytes, downloaded.readAllBytes()); }
        assertEquals(engine.generation, transport.status("b").generation());
        // A tombstone uses an empty stage body too, including when bypassing the retain optimization.
        BlobVersion tombstone = v.copy(); tombstone.sequence = 0; tombstone.state = "HARD_DELETED";
        journal.prepare(tombstone);
        String path = "/v1/stage/" + tombstone.sequence;
        var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + path));
        auth.headers("a", "POST", path, tombstone.sha256).forEach(request::header);
        assertEquals(204, direct.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(engine.generation, transport.stage("b", v, local.object(v.sha256)));
      }

    }
    finally { engine.close(); }
  }

  private SSLContext tlsContext() throws Exception {
    Path identity = temp.getRoot().toPath().resolve("identity.p12");
    String password = "isolated-test-only";
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", "peer", "-keyalg", "RSA", "-keystore", identity.toString(), "-storetype", "PKCS12",
        "-storepass", password, "-keypass", password, "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "1")
        .redirectErrorStream(true).start();
    process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
    assertEquals(0, process.waitFor());
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(identity)) { store.load(in, password.toCharArray()); }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password.toCharArray());
    TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trust.init(store);
    SSLContext result = SSLContext.getInstance("TLS"); result.init(keys.getKeyManagers(), trust.getTrustManagers(), null); return result;
  }
}

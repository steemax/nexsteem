/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Authoritative PostgreSQL journal. A committed head is published only after durable replica acknowledgements. */
public final class ClusterJournal
{
  private final DataSource source;
  private final ObjectMapper json = new ObjectMapper();

  public ClusterJournal(final DataSource source) { this.source = source; }

  public void initialize() throws SQLException {
    try (Connection c = source.getConnection()) {
      if (!"PostgreSQL".equals(c.getMetaData().getDatabaseProductName())) {
        throw new SQLException("ClusterLocal requires authoritative PostgreSQL; H2 is unsupported");
      }
      try (java.sql.Statement s = c.createStatement(); ResultSet r = s.executeQuery(
          "SELECT to_regclass('public.nx_cluster_blob_events') IS NOT NULL AND " +
          "to_regclass('public.nx_cluster_blob_heads') IS NOT NULL AND to_regclass('public.nx_cluster_blob_acks') IS NOT NULL")) {
        r.next();
        if (r.getBoolean(1)) { return; }
      }
      // Bootstrap is an administrator operation, never implicit schema creation on a passive runtime member.
      if (!"postgres".equals(c.getMetaData().getUserName())) {
        throw new SQLException("Cluster storage schema must be installed by the PostgreSQL bootstrap administrator");
      }
      try (java.sql.Statement s = c.createStatement()) {
        s.execute("CREATE TABLE IF NOT EXISTS nx_cluster_blob_events (seq bigserial PRIMARY KEY," +
            "cluster_id text NOT NULL, store_name text NOT NULL, blob_id text NOT NULL," +
            "epoch bigint NOT NULL, owner_id text NOT NULL, committed boolean NOT NULL DEFAULT false," +
            "manifest jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp())");
        s.execute("CREATE INDEX IF NOT EXISTS nx_cluster_blob_events_scan ON " +
            "nx_cluster_blob_events(cluster_id,store_name,seq) WHERE committed");
        s.execute("CREATE TABLE IF NOT EXISTS nx_cluster_blob_heads (cluster_id text NOT NULL," +
            "store_name text NOT NULL, blob_id text NOT NULL, seq bigint NOT NULL REFERENCES nx_cluster_blob_events(seq)," +
            "PRIMARY KEY(cluster_id,store_name,blob_id))");
        s.execute("CREATE TABLE IF NOT EXISTS nx_cluster_blob_acks (seq bigint NOT NULL REFERENCES nx_cluster_blob_events(seq)," +
            "node_id text NOT NULL, generation text NOT NULL, acknowledged_at timestamptz NOT NULL DEFAULT clock_timestamp()," +
            "PRIMARY KEY(seq,node_id,generation))");
      }
    }
  }

  /** Shared transaction advisory fence, with a deferred database commit guard; heartbeat rows stay unlocked. */
  public WriteFence beginWrite(final String cluster, final String node, final long epoch) throws SQLException {
    Connection c = source.getConnection();
    c.setAutoCommit(false);
    try {
      try (PreparedStatement s = c.prepareStatement("SET LOCAL statement_timeout = '20s'")) { s.execute(); }
      try (PreparedStatement s = c.prepareStatement("SET LOCAL idle_in_transaction_session_timeout = '20s'")) { s.execute(); }
      if (epoch <= 0) { throw new SQLException("Missing fixed writer epoch"); }
      try (PreparedStatement s = c.prepareStatement("SELECT nx_cluster_begin_write(?,?,?)")) {
        s.setString(1, cluster); s.setString(2, node); s.setLong(3, epoch); s.execute();
      }
      return new WriteFence(c, cluster, node, epoch);
    }
    catch (SQLException e) { try { c.rollback(); } finally { c.close(); } throw e; }
  }

  public boolean validWriter(final String cluster, final String node, final long epoch) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM nx_cluster_lease WHERE cluster_id=? AND epoch=? AND leader_id=? " +
        "AND lease_until > clock_timestamp()) AND NOT pg_is_in_recovery()")) {
      s.setString(1, cluster); s.setLong(2, epoch); s.setString(3, node);
      try (ResultSet r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
    }
  }

  public DurabilityPolicy policy(final String cluster, final long epoch) throws SQLException {
    try (Connection c = source.getConnection()) { return policy(c, cluster, epoch); }
  }

  private DurabilityPolicy policy(final Connection c, final String cluster, final long epoch) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT required_copies,require_remote_dc,degraded,proof_revision " +
        "FROM nx_cluster_policy WHERE cluster_id=? AND epoch=?")) {
      s.setString(1, cluster); s.setLong(2, epoch);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) { throw new SQLException("No authoritative durability policy for writer epoch"); }
        int copies = r.getInt(1); boolean remote = r.getBoolean(2), degraded = r.getBoolean(3);
        String proof = r.getString(4);
        if (copies < 1 || (!degraded && (copies < 2 || !remote))
            || (degraded && (proof == null || proof.isBlank()))) {
          throw new SQLException("Invalid or unproven durability policy");
        }
        return new DurabilityPolicy(copies, remote, degraded);
      }
    }
  }

  public static final class DurabilityPolicy {
    public final int copies;
    public final boolean remote;
    public final boolean degraded;
    DurabilityPolicy(final int copies, final boolean remote, final boolean degraded) {
      this.copies = copies; this.remote = remote; this.degraded = degraded;
    }
  }

  public void assertCurrent(final String cluster, final String node, final long epoch) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement("SELECT nx_cluster_assert_current(?,?,?)")) {
      s.setString(1, cluster); s.setString(2, node); s.setLong(3, epoch); s.execute();
    }
  }

  public BlobVersion prepare(final BlobVersion v) throws SQLException, IOException {
    try (Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (java.sql.Statement timeouts = c.createStatement()) {
          timeouts.execute("SET LOCAL statement_timeout='20s'"); timeouts.execute("SET LOCAL idle_in_transaction_session_timeout='20s'");
        }
        try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock_shared(hashtextextended(?,1901))")) {
          s.setString(1, "blobgc:" + v.cluster + ":" + v.sha256); s.execute();
        }
        try (PreparedStatement s = c.prepareStatement("INSERT INTO nx_cluster_blob_events " +
            "(cluster_id,store_name,blob_id,epoch,owner_id,manifest) VALUES (?,?,?,?,?,?::jsonb) RETURNING seq")) {
          s.setString(1, v.cluster); s.setString(2, v.store); s.setString(3, v.blob);
          s.setLong(4, v.epoch); s.setString(5, v.owner); s.setString(6, json.writeValueAsString(v));
          try (ResultSet r = s.executeQuery()) { r.next(); v.sequence = r.getLong(1); }
        }
        try (PreparedStatement s = c.prepareStatement("UPDATE nx_cluster_blob_events SET manifest=?::jsonb WHERE seq=?")) {
          s.setString(1, json.writeValueAsString(v)); s.setLong(2, v.sequence); s.executeUpdate();
        }
        c.commit(); return v;
      }
      catch (SQLException | IOException e) { c.rollback(); throw e; }
    }
  }

  public void acknowledge(final long sequence, final String node, final String generation) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "INSERT INTO nx_cluster_blob_acks(seq,node_id,generation) VALUES (?,?,?) ON CONFLICT DO NOTHING")) {
      s.setLong(1, sequence); s.setString(2, node); s.setString(3, generation); s.executeUpdate();
    }
  }

  public BlobVersion event(final long sequence, final boolean committedOnly) throws SQLException, IOException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT manifest::text FROM nx_cluster_blob_events WHERE seq=?" + (committedOnly ? " AND committed" : ""))) {
      s.setLong(1, sequence);
      try (ResultSet r = s.executeQuery()) { return r.next() ? json.readValue(r.getString(1), BlobVersion.class) : null; }
    }
  }

  public BlobVersion head(final String cluster, final String store, final String blob) throws SQLException, IOException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT e.manifest::text FROM nx_cluster_blob_heads h JOIN nx_cluster_blob_events e ON e.seq=h.seq " +
        "WHERE h.cluster_id=? AND h.store_name=? AND h.blob_id=? AND e.committed")) {
      s.setString(1, cluster); s.setString(2, store); s.setString(3, blob);
      try (ResultSet r = s.executeQuery()) { return r.next() ? json.readValue(r.getString(1), BlobVersion.class) : null; }
    }
  }

  public List<BlobVersion> missing(final String cluster, final String node, final String generation, final int limit) throws SQLException, IOException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT e.manifest::text FROM nx_cluster_blob_events e JOIN nx_cluster_blob_heads h ON h.seq=e.seq " +
        "WHERE e.cluster_id=? AND e.committed " +
        "AND NOT EXISTS(SELECT 1 FROM nx_cluster_blob_acks a WHERE a.seq=e.seq AND a.node_id=? AND a.generation=?) ORDER BY e.seq LIMIT ?")) {
      s.setString(1, cluster); s.setString(2, node); s.setString(3, generation); s.setInt(4, limit);
      return versions(s);
    }
  }

  public List<BlobVersion> heads(final String cluster, final String store) throws SQLException, IOException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT e.manifest::text FROM nx_cluster_blob_heads h JOIN nx_cluster_blob_events e ON e.seq=h.seq " +
        "WHERE h.cluster_id=? AND h.store_name=? ORDER BY e.seq")) {
      s.setString(1, cluster); s.setString(2, store); return versions(s);
    }
  }

  public List<BlobVersion> allHeads(final String cluster) throws SQLException, IOException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT e.manifest::text FROM nx_cluster_blob_heads h JOIN nx_cluster_blob_events e ON e.seq=h.seq WHERE h.cluster_id=?")) {
      s.setString(1, cluster); return versions(s);
    }
  }

  public List<BlobVersion> headsAfter(final String cluster, final long after, final int limit) throws SQLException, IOException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT e.manifest::text FROM nx_cluster_blob_heads h JOIN nx_cluster_blob_events e ON e.seq=h.seq " +
        "WHERE h.cluster_id=? AND h.seq>? ORDER BY h.seq LIMIT ?")) {
      s.setString(1, cluster); s.setLong(2, after); s.setInt(3, limit); return versions(s);
    }
  }

  /** Lazy pages hold no JDBC connection between pages, even if a caller stops without closing the Stream. */
  public java.util.stream.Stream<BlobVersion> streamHeads(final String cluster, final String store) throws SQLException {
    long upper;
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT COALESCE(max(seq),0) FROM nx_cluster_blob_heads WHERE cluster_id=? AND store_name=?")) {
      s.setString(1, cluster); s.setString(2, store);
      try (ResultSet r = s.executeQuery()) { r.next(); upper = r.getLong(1); }
    }
    final long ceiling = upper;
    java.util.Iterator<BlobVersion> iterator = new java.util.Iterator<>() {
      private java.util.Iterator<BlobVersion> page = java.util.Collections.emptyIterator();
      private long after;
      private boolean done = ceiling == 0;
      public boolean hasNext() {
        if (page.hasNext()) { return true; }
        if (done) { return false; }
        try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
            "SELECT e.manifest::text FROM nx_cluster_blob_heads h JOIN nx_cluster_blob_events e ON e.seq=h.seq " +
            "WHERE h.cluster_id=? AND h.store_name=? AND h.seq>? AND h.seq<=? ORDER BY h.seq LIMIT 100")) {
          s.setString(1, cluster); s.setString(2, store); s.setLong(3, after); s.setLong(4, ceiling);
          List<BlobVersion> values = versions(s);
          done = values.size() < 100;
          if (!values.isEmpty()) { after = values.get(values.size()-1).sequence; }
          page = values.iterator(); return page.hasNext();
        }
        catch (SQLException | IOException e) { throw new IllegalStateException("Blob enumeration failed", e); }
      }
      public BlobVersion next() {
        if (!hasNext()) { throw new java.util.NoSuchElementException(); }
        return page.next();
      }
    };
    return java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(iterator,
        java.util.Spliterator.ORDERED | java.util.Spliterator.NONNULL), false);
  }

  public long[] metrics(final String cluster, final String store) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT count(*),COALESCE(sum((e.manifest->>'size')::bigint),0) FROM nx_cluster_blob_heads h " +
        "JOIN nx_cluster_blob_events e ON e.seq=h.seq WHERE h.cluster_id=? AND h.store_name=? AND e.manifest->>'state'='LIVE'")) {
      s.setString(1, cluster); s.setString(2, store);
      try (ResultSet r = s.executeQuery()) { r.next(); return new long[]{r.getLong(1),r.getLong(2)}; }
    }
  }

  public boolean hasPendingHeads(final String cluster, final String node, final String generation) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM nx_cluster_blob_heads h " +
        "WHERE h.cluster_id=? AND NOT EXISTS(SELECT 1 FROM nx_cluster_blob_acks a WHERE a.seq=h.seq AND a.node_id=? AND a.generation=?))")) {
      s.setString(1, cluster); s.setString(2, node); s.setString(3, generation);
      try (ResultSet r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
    }
  }

  public long currentEpoch(final String cluster) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement("SELECT epoch FROM nx_cluster_lease WHERE cluster_id=?")) {
      s.setString(1, cluster);
      try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
    }
  }

  public String currentOwner(final String cluster) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement("SELECT leader_id FROM nx_cluster_lease WHERE cluster_id=?")) {
      s.setString(1, cluster);
      try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : ""; }
    }
  }

  public boolean isReferenced(final BlobVersion version) throws SQLException {
    try (Connection c = source.getConnection()) { return isReferenced(c, version.store, version.blob); }
  }

  private boolean isReferenced(final Connection c, final String store, final String blob) throws SQLException {
    try (PreparedStatement s = c.prepareStatement("SELECT nx_cluster_blob_is_referenced(?,?)")) {
      s.setString(1, store); s.setString(2, blob);
      try (ResultSet r = s.executeQuery()) { r.next(); return r.getBoolean(1); }
    }
  }

  /** Returns true only after a primary-backed fenced transaction finds no current SQL blob head needing the bytes. */
  public boolean purgeUnreferenced(final String cluster, final String sha256, final java.nio.file.Path object,
      final long before) throws SQLException, IOException
  {
    try (Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (java.sql.Statement timeouts = c.createStatement()) {
          timeouts.execute("SET LOCAL statement_timeout='20s'"); timeouts.execute("SET LOCAL idle_in_transaction_session_timeout='20s'");
        }
        String owner; long epoch;
        try (PreparedStatement s = c.prepareStatement("SELECT leader_id,epoch FROM nx_cluster_lease WHERE cluster_id=?")) {
          s.setString(1, cluster);
          try (ResultSet r = s.executeQuery()) {
            if (!r.next()) { c.rollback(); return false; }
            owner = r.getString(1); epoch = r.getLong(2);
          }
        }
        try (PreparedStatement s = c.prepareStatement("SELECT nx_cluster_begin_write(?,?,?)")) {
          s.setString(1, cluster); s.setString(2, owner); s.setLong(3, epoch); s.execute();
        }
        try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,1901))")) {
          s.setString(1, "blobgc:" + cluster + ":" + sha256); s.execute();
        }
        try (PreparedStatement s = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM nx_cluster_blob_heads h " +
            "JOIN nx_cluster_blob_events e ON h.seq=e.seq WHERE h.cluster_id=? " +
            "AND e.manifest->>'sha256'=? AND e.manifest->>'state' <> 'HARD_DELETED')")) {
          s.setString(1, cluster); s.setString(2, sha256);
          try (ResultSet r = s.executeQuery()) {
            r.next(); if (r.getBoolean(1)) { c.rollback(); return false; }
          }
        }
        try (PreparedStatement s = c.prepareStatement("SELECT EXISTS(SELECT 1 FROM nx_cluster_blob_events " +
            "WHERE cluster_id=? AND NOT committed AND manifest->>'sha256'=? " +
            "AND created_at > clock_timestamp()-interval '24 hours')")) {
          s.setString(1, cluster); s.setString(2, sha256);
          try (ResultSet r = s.executeQuery()) { r.next(); if (r.getBoolean(1)) { c.rollback(); return false; } }
        }
        try (PreparedStatement s = c.prepareStatement("SELECT e.store_name,e.blob_id FROM nx_cluster_blob_heads h " +
            "JOIN nx_cluster_blob_events e ON h.seq=e.seq WHERE h.cluster_id=? AND e.manifest->>'sha256'=?")) {
          s.setString(1, cluster); s.setString(2, sha256);
          try (ResultSet r = s.executeQuery()) {
            while (r.next()) { if (isReferenced(c, r.getString(1), r.getString(2))) { c.rollback(); return false; } }
          }
        }
        // All current immutable head references have been tested under the same per-object lock as publication.
        if (!java.nio.file.Files.exists(object) || java.nio.file.Files.getLastModifiedTime(object).toMillis() > before) {
          c.rollback(); return false;
        }
        boolean removed = java.nio.file.Files.deleteIfExists(object);
        if (removed) { DurableObjects.forceDirectory(object.getParent()); }
        c.commit(); return removed;
      }
      catch (SQLException | IOException e) { c.rollback(); throw e; }
    }
  }

  /** Bounded retention: superseded/PREPARED versions keep 7 days; unreferenced hard tombstones keep 30 days. */
  public void pruneHistory(final String cluster) throws SQLException {
    try (Connection c = source.getConnection()) {
      c.setAutoCommit(false);
      try {
        try (java.sql.Statement timeouts = c.createStatement()) {
          timeouts.execute("SET LOCAL statement_timeout='20s'"); timeouts.execute("SET LOCAL idle_in_transaction_session_timeout='20s'");
        }
        String owner; long epoch;
        try (PreparedStatement s = c.prepareStatement("SELECT leader_id,epoch FROM nx_cluster_lease WHERE cluster_id=?")) {
          s.setString(1, cluster);
          try (ResultSet r = s.executeQuery()) { if (!r.next()) { c.rollback(); return; } owner = r.getString(1); epoch = r.getLong(2); }
        }
        try (PreparedStatement s = c.prepareStatement("SELECT nx_cluster_begin_write(?,?,?)")) {
          s.setString(1, cluster); s.setString(2, owner); s.setLong(3, epoch); s.execute();
        }
        java.util.List<Long> expired = new java.util.ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT e.seq,e.store_name,e.blob_id FROM nx_cluster_blob_heads h " +
            "JOIN nx_cluster_blob_events e ON h.seq=e.seq WHERE h.cluster_id=? AND e.manifest->>'state'='HARD_DELETED' " +
            "AND e.created_at<clock_timestamp()-interval '30 days' LIMIT 500")) {
          s.setString(1, cluster);
          try (ResultSet r = s.executeQuery()) { while (r.next()) {
            if (!isReferenced(c, r.getString(2), r.getString(3))) { expired.add(r.getLong(1)); }
          } }
        }
        try (PreparedStatement s = c.prepareStatement("DELETE FROM nx_cluster_blob_heads WHERE cluster_id=? AND seq=?")) {
          for (Long sequence : expired) { s.setString(1, cluster); s.setLong(2, sequence); s.addBatch(); } s.executeBatch();
        }
        java.util.List<Long> obsolete = new java.util.ArrayList<>();
        try (PreparedStatement s = c.prepareStatement("SELECT e.seq FROM nx_cluster_blob_events e WHERE e.cluster_id=? " +
            "AND e.created_at<clock_timestamp()-interval '7 days' " +
            "AND NOT EXISTS(SELECT 1 FROM nx_cluster_blob_heads h WHERE h.seq=e.seq) ORDER BY e.seq LIMIT 1000")) {
          s.setString(1, cluster);
          try (ResultSet r = s.executeQuery()) { while (r.next()) { obsolete.add(r.getLong(1)); } }
        }
        try (PreparedStatement a = c.prepareStatement("DELETE FROM nx_cluster_blob_acks WHERE seq=?");
             PreparedStatement e = c.prepareStatement("DELETE FROM nx_cluster_blob_events WHERE seq=? " +
                 "AND NOT EXISTS(SELECT 1 FROM nx_cluster_blob_heads h WHERE h.seq=?)")) {
          for (Long sequence : obsolete) {
            a.setLong(1, sequence); a.executeUpdate(); e.setLong(1, sequence); e.setLong(2, sequence); e.executeUpdate();
          }
        }
        c.commit();
      }
      catch (SQLException e) { c.rollback(); throw e; }
    }
  }

  public long pendingHeads(final String cluster, final String node, final String generation) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM nx_cluster_blob_heads h " +
        "WHERE h.cluster_id=? AND NOT EXISTS(SELECT 1 FROM nx_cluster_blob_acks a WHERE a.seq=h.seq AND a.node_id=? AND a.generation=?)")) {
      s.setString(1, cluster); s.setString(2, node); s.setString(3, generation);
      s.setQueryTimeout(2);
      try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); }
    }
  }

  public List<String> replicas(final long sequence) throws SQLException {
    try (Connection c = source.getConnection(); PreparedStatement s = c.prepareStatement(
        "SELECT DISTINCT node_id FROM nx_cluster_blob_acks WHERE seq=? ORDER BY node_id")) {
      s.setLong(1, sequence);
      List<String> values = new ArrayList<>();
      try (ResultSet r = s.executeQuery()) { while (r.next()) { values.add(r.getString(1)); } }
      return values;
    }
  }

  private List<BlobVersion> versions(final PreparedStatement s) throws SQLException, IOException {
    List<BlobVersion> values = new ArrayList<>();
    try (ResultSet r = s.executeQuery()) {
      while (r.next()) { values.add(json.readValue(r.getString(1), BlobVersion.class)); }
    }
    return values;
  }

  public final class WriteFence implements AutoCloseable
  {
    private final Connection c;
    private final String cluster, node;
    private final long epoch;

    private WriteFence(final Connection c, final String cluster, final String node, final long epoch) {
      this.c = c; this.cluster = cluster; this.node = node; this.epoch = epoch;
    }

    public void lockObject(final String sha256) throws SQLException {
      try (PreparedStatement s = c.prepareStatement("SELECT pg_advisory_xact_lock_shared(hashtextextended(?,1901))")) {
        s.setString(1, "blobgc:" + cluster + ":" + sha256); s.execute();
      }
    }

    public void publish(final BlobVersion v, final String writerNode, final java.util.Map<String, String> liveGenerations,
        final java.util.Map<String, String> domains)
        throws SQLException
    {
      if (!cluster.equals(v.cluster) || !node.equals(v.owner) || epoch != v.epoch) {
        throw new SQLException("Mutation fencing identity mismatch");
      }
      DurabilityPolicy policy = policy(c, cluster, epoch);
      // Count configured members only, then recheck DB clock immediately before the commit.
      long copies = 0;
      boolean remote = false;
      try (PreparedStatement s = c.prepareStatement("SELECT node_id,generation FROM nx_cluster_blob_acks WHERE seq=?")) {
        s.setLong(1, v.sequence);
        try (ResultSet r = s.executeQuery()) {
          while (r.next()) {
            String member = r.getString(1);
            if (r.getString(2).equals(liveGenerations.get(member))) {
              copies++;
              if (!java.util.Objects.equals(domains.get(writerNode), domains.get(member))) { remote = true; }
            }
          }
        }
      }
      if (copies < policy.copies) { throw new SQLException("Insufficient durable blob copies"); }
      if (policy.remote && !remote) { throw new SQLException("No durable copy in another failure domain"); }
      try (PreparedStatement s = c.prepareStatement("SELECT nx_cluster_assert_current(?,?,?)")) {
        s.setString(1, cluster); s.setString(2, node); s.setLong(3, epoch); s.execute();
      }
      try (PreparedStatement s = c.prepareStatement("UPDATE nx_cluster_blob_events SET committed=true WHERE seq=?")) {
        s.setLong(1, v.sequence); s.executeUpdate();
      }
      try (PreparedStatement s = c.prepareStatement("INSERT INTO nx_cluster_blob_heads(cluster_id,store_name,blob_id,seq) " +
          "VALUES (?,?,?,?) ON CONFLICT(cluster_id,store_name,blob_id) DO UPDATE SET seq=EXCLUDED.seq " +
          "WHERE nx_cluster_blob_heads.seq < EXCLUDED.seq")) {
        s.setString(1, v.cluster); s.setString(2, v.store); s.setString(3, v.blob); s.setLong(4, v.sequence);
        s.executeUpdate();
      }
      c.commit();
    }

    @Override public void close() throws SQLException { try { c.rollback(); } finally { c.close(); } }
  }
}

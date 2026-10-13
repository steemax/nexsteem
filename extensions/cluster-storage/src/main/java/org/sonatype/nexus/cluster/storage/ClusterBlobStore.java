/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.sonatype.nexus.blobstore.BlobAttributesSupport;
import org.sonatype.nexus.blobstore.MemoryBlobSession;
import org.sonatype.nexus.blobstore.api.*;
import org.sonatype.nexus.blobstore.api.metrics.BlobStoreMetricsService;
import org.sonatype.nexus.common.lifecycle.LifecycleSupport;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.google.common.hash.HashCode;
import org.joda.time.DateTime;

/** Optional format-independent local-PVC blob store, synchronously replicated before returning to Core. */
@Component
@Qualifier(ClusterBlobStore.TYPE)
@Scope("prototype")
@AvailabilityVersion(from = "1.0")
public final class ClusterBlobStore extends LifecycleSupport implements BlobStore
{
  public static final String TYPE = "ClusterLocal";
  private final DataStoreManager stores;
  private BlobStoreConfiguration configuration;
  private ReplicationEngine engine;
  private java.util.concurrent.ScheduledExecutorService maintenance;
  private final Map<OperationType, OperationMetrics> operations = new EnumMap<>(OperationType.class);

  public ClusterBlobStore(final DataStoreManager stores) {
    this.stores = stores;
    for (OperationType type : OperationType.values()) { operations.put(type, new OperationMetrics()); }
  }

  @Override public void init(final BlobStoreConfiguration config) { configuration = config; }

  @Override protected void doStart() throws Exception {
    ClusterSettings settings = new ClusterSettings();
    if (settings.epoch <= 0) { throw new IOException("Core writer requires fixed positive NX_CLUSTER_EPOCH"); }
    ClusterJournal journal = new ClusterJournal(stores.get(DataStoreManager.DEFAULT_DATASTORE_NAME)
        .orElseThrow(() -> new IllegalStateException("PostgreSQL datastore unavailable")).getDataSource());
    journal.initialize();
    engine = new ReplicationEngine(settings, journal, new DurableObjects(settings.root),
        new ReplicaTransport(settings, ReplicaTls.context(settings)));
    while (!engine.caughtUp()) { engine.catchUp(); }
    maintenance = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    maintenance.scheduleWithFixedDelay(() -> {
      try {
        long before = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        try (Stream<BlobVersion> versions = engine.journal.streamHeads(engine.settings.cluster, configuration.getName())) {
          java.util.Iterator<BlobVersion> records = versions.iterator();
          while (records.hasNext()) {
            BlobVersion v = records.next();
            if ("DELETED".equals(v.state) && v.changed < before && !engine.journal.isReferenced(v)) {
              deleteHard(new BlobId(v.blob));
            }
          }
        }
        engine.collectGarbage(Duration.ofHours(24));
      }
      catch (Exception unavailable) { log.debug("Replicated blob maintenance deferred until authority and replicas are available"); }
    }, 5, 5, java.util.concurrent.TimeUnit.MINUTES);
  }

  @Override protected void doStop() {
    if (maintenance != null) { maintenance.shutdownNow(); }
    if (engine != null) { engine.close(); }
  }

  @Override public boolean isStarted() { return super.isStarted(); }
  @Override public BlobSession<?> openSession() { return new MemoryBlobSession(this); }
  @Override public Blob create(final InputStream bytes, final Map<String, String> headers) { return create(bytes, headers, null); }

  @Override public Blob create(final InputStream bytes, final Map<String, String> headers, final BlobId requested) {
    ensureStarted();
    if (!headers.containsKey(BLOB_NAME_HEADER) || !headers.containsKey(CREATED_BY_HEADER)) {
      throw new IllegalArgumentException("Blob name and creator headers are required");
    }
    BlobVersion v = new BlobVersion();
    v.store = configuration.getName(); v.created = System.currentTimeMillis();
    v.blob = requested != null ? requested.asUniqueString() : "true".equals(headers.get(DIRECT_PATH_BLOB_HEADER))
        ? "direct/" + headers.get(BLOB_NAME_HEADER) : UUID.randomUUID().toString();
    if (v.blob.length() > 2000 || v.blob.indexOf('\u0000') >= 0) { throw new IllegalArgumentException("Invalid blob id"); }
    v.headers.putAll(headers);
    try { return new StoredBlob(engine.create(v, bytes)); }
    catch (Exception e) { throw failure(e, requested); }
  }

  @Override public Blob create(final Path file, final Map<String, String> headers, final long size, final HashCode sha1) {
    try (InputStream bytes = Files.newInputStream(file)) {
      Blob blob = create(bytes, headers);
      if (blob.getMetrics().getContentSize() != size || !blob.getMetrics().getSha1Hash().equals(sha1.toString())) {
        deleteHard(blob.getId()); throw new IOException("Imported blob checksum or size mismatch");
      }
      return blob;
    }
    catch (Exception e) { throw failure(e, null); }
  }

  @Override public Blob copy(final BlobId id, final Map<String, String> headers) {
    if (!headers.containsKey(BLOB_NAME_HEADER) || !headers.containsKey(CREATED_BY_HEADER)) {
      throw new IllegalArgumentException("Blob name and creator headers are required");
    }
    BlobVersion source = head(id);
    if (source == null || "HARD_DELETED".equals(source.state)) { throw new BlobStoreException("Source blob not found", id); }
    BlobVersion v = source.copy();
    v.blob = "true".equals(headers.get(DIRECT_PATH_BLOB_HEADER)) ? "direct/" + headers.get(BLOB_NAME_HEADER) : UUID.randomUUID().toString();
    v.headers = new java.util.LinkedHashMap<>(headers); v.created = System.currentTimeMillis(); v.state = "LIVE"; v.reason = null;
    try { return new StoredBlob(engine.mutate(v)); }
    catch (Exception e) { throw failure(e, id); }
  }

  @Override public boolean isInternalMoveSupported(final BlobStore target) { return false; }
  @Override public boolean isOwner(final Blob blob) { return blob instanceof StoredBlob && ((StoredBlob) blob).owner() == this; }
  @Override public Blob moveInternal(final BlobStore target, final BlobId id, final Map<String, String> headers) {
    throw new UnsupportedOperationException("Cross-store moves require Core re-ingestion");
  }

  @Override public Blob get(final BlobId id) { return get(id, false); }
  @Override public Blob get(final BlobId id, final boolean includeDeleted) {
    ensureStarted();
    BlobVersion v = head(id);
    if (v == null || "HARD_DELETED".equals(v.state) || (!includeDeleted && !"LIVE".equals(v.state))) { return null; }
    try { engine.repair(v); return new StoredBlob(v); }
    catch (Exception e) { throw failure(e, id); }
  }

  @Override public boolean exists(final BlobId id) { return head(id) != null; }
  @Override public boolean bytesExists(final BlobId id) {
    BlobVersion v = head(id);
    try { return v != null && engine.objects.has(v); }
    catch (IOException e) { throw failure(e, id); }
  }

  @Override public boolean delete(final BlobId id, final String reason) { return tombstone(id, "DELETED", reason); }
  @Override public boolean deleteHard(final BlobId id) { return tombstone(id, "HARD_DELETED", "Hard deletion"); }

  private boolean tombstone(final BlobId id, final String state, final String reason) {
    BlobVersion current = head(id);
    if (current == null || "HARD_DELETED".equals(current.state) || state.equals(current.state)) { return false; }
    BlobVersion v = current.copy(); v.state = state; v.reason = reason;
    try { engine.mutate(v); return true; }
    catch (Exception e) { throw failure(e, id); }
  }

  @Override public boolean undelete(final BlobStoreUsageChecker checker, final BlobId id,
      final BlobAttributes attributes, final boolean dryRun)
  {
    BlobVersion current = head(id);
    if (current == null || !"DELETED".equals(current.state)) { return false; }
    if (dryRun) { return true; }
    BlobVersion v = current.copy(); v.state = "LIVE"; v.reason = null;
    try { engine.mutate(v); return true; }
    catch (Exception e) { throw failure(e, id); }
  }

  @Override public BlobAttributes getBlobAttributes(final BlobId id) {
    BlobVersion v = head(id); return v == null ? null : new Attributes(v);
  }
  @Override public BlobAttributes getBlobAttributesWithException(final BlobId id) { return getBlobAttributes(id); }
  @Override public BlobAttributes createBlobAttributesInstance(final BlobId id, final Map<String, String> headers,
      final BlobMetrics metrics)
  {
    BlobVersion v = head(id);
    if (v == null) { throw new BlobStoreException("Cannot create attributes without durable blob content", id); }
    BlobVersion changed = v.copy(); changed.headers = new java.util.LinkedHashMap<>(headers);
    return new Attributes(changed);
  }
  @Override public void createBlobAttributes(final BlobId id, final Map<String, String> headers, final BlobMetrics metrics) {
    setBlobAttributes(id, createBlobAttributesInstance(id, headers, metrics));
  }
  @Override public void setBlobAttributes(final BlobId id, final BlobAttributes attributes) {
    BlobVersion v = head(id);
    if (v == null) { throw new BlobStoreException("Blob does not exist", id); }
    BlobVersion changed = v.copy(); changed.headers = new java.util.LinkedHashMap<>(attributes.getHeaders());
    changed.state = attributes.isDeleted() ? "DELETED" : "LIVE"; changed.reason = attributes.isDeleted() ? attributes.getDeletedReason() : null;
    try { engine.mutate(changed); }
    catch (Exception e) { throw failure(e, id); }
  }

  private BlobVersion head(final BlobId id) {
    ensureStarted();
    try { return engine.journal.head(engine.settings.cluster, configuration.getName(), id.asUniqueString()); }
    catch (Exception e) { throw failure(e, id); }
  }

  @Override public Stream<BlobId> getBlobIdStream() {
    try { return engine.journal.streamHeads(engine.settings.cluster, configuration.getName())
        .filter(v -> !"HARD_DELETED".equals(v.state)).map(v -> new BlobId(v.blob)); }
    catch (Exception e) { throw failure(e, null); }
  }
  @Override public Stream<BlobId> getDirectPathBlobIdStream(final String prefix) {
    return getBlobIdStream().filter(id -> id.asUniqueString().startsWith("direct/" + prefix));
  }
  @Override public Stream<BlobId> getBlobIdUpdatedSinceStream(final String prefix, final OffsetDateTime from,
      final OffsetDateTime to)
  {
    try { return engine.journal.streamHeads(engine.settings.cluster, configuration.getName())
        .filter(v -> v.blob.startsWith(prefix) && v.changed >= from.toInstant().toEpochMilli() && v.changed < to.toInstant().toEpochMilli())
        .map(v -> new BlobId(v.blob)); }
    catch (Exception e) { throw failure(e, null); }
  }

  @Override public boolean isStorageAvailable() {
    try { return isStarted() && engine.journal.validWriter(engine.settings.cluster, engine.settings.owner, engine.settings.epoch)
        && engine.caughtUp() && engine.quorumAvailable(); }
    catch (Exception e) { return false; }
  }
  @Override public BlobStoreConfiguration getBlobStoreConfiguration() { return configuration; }
  @Override public boolean isEmpty() { try (Stream<BlobId> ids = getBlobIdStream()) { return ids.findAny().isEmpty(); } }
  @Override public void shutdown() throws Exception { if (isStarted()) { stop(); } }
  @Override public void remove() {
    if (!isEmpty()) { throw new BlobStoreException("Remove requires an empty replicated store", null); }
    // No local recursive deletion: tombstones and immutable journal entries are retained across node recovery.
  }
  @Override public void compact(final BlobStoreUsageChecker checker, final Duration age) {
    try {
      if (checker != null) {
        long before = System.currentTimeMillis() - Math.max(Duration.ofHours(24).toMillis(), age.toMillis());
        try (Stream<BlobVersion> versions = engine.journal.streamHeads(engine.settings.cluster, configuration.getName())) {
          java.util.Iterator<BlobVersion> records = versions.iterator();
          while (records.hasNext()) {
            BlobVersion v = records.next();
            if ("DELETED".equals(v.state) && v.changed < before
                && !checker.test(this, new BlobId(v.blob), v.headers.get(BLOB_NAME_HEADER)) && !engine.journal.isReferenced(v)) {
              deleteHard(new BlobId(v.blob));
            }
          }
        }
      }
      engine.collectGarbage(age);
    }
    catch (Exception e) { throw failure(e, null); }
  }
  @Override public void deleteTempFiles(final Integer days) {
    long before = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
    try (Stream<BlobVersion> versions = engine.journal.streamHeads(engine.settings.cluster, configuration.getName())) {
      java.util.Iterator<BlobVersion> records = versions.iterator();
      while (records.hasNext()) {
        BlobVersion v = records.next();
        if ("LIVE".equals(v.state) && v.created < before && v.headers.containsKey(TEMPORARY_BLOB_HEADER)) {
          deleteHard(new BlobId(v.blob));
        }
      }
    }
    catch (Exception e) { throw failure(e, null); }
  }
  @Override public <B extends BlobStore> BlobStoreMetricsService<B> getMetricsService() {
    throw new UnsupportedOperationException("ClusterLocal exposes its journal-backed metrics via getMetrics");
  }
  @Override public BlobStoreMetrics getMetrics() {
    try {
      long[] totals = engine.journal.metrics(engine.settings.cluster, configuration.getName());
      long count = totals[0];
      long size = totals[1];
      long available = Files.getFileStore(engine.objects.root()).getUsableSpace();
      return new BlobStoreMetrics() {
        public long getBlobCount() { return count; } public long getTotalSize() { return size; }
        public long getAvailableSpace() { return available; } public boolean isUnlimited() { return false; }
        public Map<String, Long> getAvailableSpaceByFileStore() { return Map.of(engine.settings.node, available); }
        public boolean isUnavailable() { return false; }
      };
    }
    catch (Exception e) { throw failure(e, null); }
  }
  @Override public Map<OperationType, OperationMetrics> getOperationMetricsByType() { return operations; }
  @Override public Map<OperationType, OperationMetrics> getOperationMetricsDelta() { return operations; }
  @Override public void clearOperationMetrics() { operations.values().forEach(OperationMetrics::clear); }

  private BlobStoreException failure(final Exception e, final BlobId id) {
    return new BlobStoreException("Replicated blob operation failed closed", e, id);
  }

  private final class StoredBlob implements Blob
  {
    private final BlobVersion version;
    StoredBlob(final BlobVersion version) { this.version = version; }
    ClusterBlobStore owner() { return ClusterBlobStore.this; }
    public BlobId getId() { return new BlobId(version.blob); }
    public Map<String, String> getHeaders() { return Map.copyOf(version.headers); }
    public BlobMetrics getMetrics() { return new BlobMetrics(new DateTime(version.created), version.sha1, version.size); }
    public InputStream getInputStream() {
      try { engine.repair(version); return engine.objects.open(version.sha256); }
      catch (Exception e) { throw failure(e, getId()); }
    }
  }

  private final class Attributes extends BlobAttributesSupport<java.util.Properties>
  {
    private final BlobVersion version;
    Attributes(final BlobVersion version) {
      super(new java.util.Properties(), version.headers, new BlobMetrics(new DateTime(version.created), version.sha1, version.size));
      this.version = version;
      setDeleted(!"LIVE".equals(version.state)); setDeletedReason(version.reason);
      if (isDeleted()) { setDeletedDateTime(new DateTime(version.changed)); }
    }
    @Override public void store() throws IOException {
      try { setBlobAttributes(new BlobId(version.blob), this); }
      catch (BlobStoreException e) { throw new IOException("Attributes replication failed", e); }
    }
    @Override public void writeProperties() { writeTo(propertiesFile); }
  }
}

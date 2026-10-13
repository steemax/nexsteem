/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable journal entry; PostgreSQL sequence is the version, never a wall clock. */
public final class BlobVersion
{
  public long sequence;
  public String cluster;
  public String store;
  public String blob;
  public long epoch;
  public String owner;
  public String sha256;
  public String sha1;
  public long size;
  public long created;
  public long changed;
  public String state = "LIVE";
  public String reason;
  public Map<String, String> headers = new LinkedHashMap<>();

  public BlobVersion() {}

  public BlobVersion copy() {
    BlobVersion v = new BlobVersion();
    v.sequence = sequence; v.cluster = cluster; v.store = store; v.blob = blob;
    v.epoch = epoch; v.owner = owner; v.sha256 = sha256; v.sha1 = sha1;
    v.size = size; v.created = created; v.changed = changed; v.state = state; v.reason = reason;
    v.headers = new LinkedHashMap<>(headers);
    return v;
  }
}

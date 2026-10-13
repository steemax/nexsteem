/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.api;

/** Replication runs on every member, including members whose Core process is stopped. */
public interface ReplicaAgent extends AutoCloseable {
  void start() throws Exception;

  /** True only after all committed blob heads have their verified bytes locally. */
  boolean isCaughtUp() throws Exception;

  /**
   * Persist the new epoch and fence the peers required by the authoritative
   * coordination policy. Failure must prevent starting a writer Core.
   */
  boolean fencePeers(long epoch, String owner) throws Exception;

  ReplicaStatus status();

  @Override void close() throws Exception;
}

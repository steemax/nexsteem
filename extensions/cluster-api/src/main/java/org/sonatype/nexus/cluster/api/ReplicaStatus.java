/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.api;

/** Copy counts describe current durable-write capacity, not a promise about unverified old bytes. */
public record ReplicaStatus(
    boolean memberReady,
    boolean caughtUp,
    boolean quorumReady,
    long durableEpoch,
    int requiredCopies,
    int availableCopies,
    boolean degraded,
    String detail) { }

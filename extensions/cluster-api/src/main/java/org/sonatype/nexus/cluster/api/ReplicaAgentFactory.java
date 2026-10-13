/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.api;

import java.util.Properties;

/** Discovered by the Kubernetes supervisor through ServiceLoader. */
public interface ReplicaAgentFactory {
  ReplicaAgent create(Properties configuration) throws Exception;
}

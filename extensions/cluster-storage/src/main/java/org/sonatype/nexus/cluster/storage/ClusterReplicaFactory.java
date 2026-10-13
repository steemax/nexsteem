/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.sonatype.nexus.cluster.api.ReplicaAgent;
import org.sonatype.nexus.cluster.api.ReplicaAgentFactory;

public final class ClusterReplicaFactory implements ReplicaAgentFactory
{
  @Override public ReplicaAgent create(final Properties configuration) throws Exception {
    ClusterSettings settings = new ClusterSettings(configuration);
    String jdbc = settings.setting("NX_CLUSTER_SQL_URL", settings.setting("NX_CLUSTER_JDBC_URL", ""));
    String user = settings.setting("NX_CLUSTER_SQL_USER", settings.setting("NX_CLUSTER_JDBC_USER", ""));
    String file = settings.setting("NX_CLUSTER_SQL_PASSWORD_FILE", settings.setting("NX_CLUSTER_JDBC_PASSWORD_FILE", ""));
    if (jdbc.isBlank() || user.isBlank() || file.isBlank()) { throw new java.io.IOException("Replica requires PostgreSQL connection settings"); }
    String password = Files.readString(Path.of(file)).trim();
    Class.forName("org.postgresql.Driver");
    ClusterJournal journal = new ClusterJournal(new StandaloneReplicaMain.JdbcSource(jdbc, user, password));
    journal.initialize();
    javax.net.ssl.SSLContext tls = ReplicaTls.context(settings);
    ReplicationEngine engine = new ReplicationEngine(settings, journal, new DurableObjects(settings.root), new ReplicaTransport(settings, tls));
    return new ClusterReplicaAgent(engine, new ReplicaServer(engine, tls));
  }
}

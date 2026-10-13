/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import java.sql.PreparedStatement;
import java.sql.Timestamp;

/** Used by the coordinated upgrade runner inside an authenticated cluster Pod. */
public final class ClusterUpgradeCommand
{
  private ClusterUpgradeCommand() {}

  public static void main(final String[] args) throws Exception {
    if (args.length != 3 || !args[0].matches("[a-f0-9]{64}") || !args[1].matches("[a-f0-9]{64}")
        || !args[2].matches("[A-Za-z0-9._+:-]{1,100}")) {
      throw new IllegalArgumentException("Expected old application SHA256, new application SHA256, new version");
    }
    var settings = new Settings(System.getenv());
    var store = new ControlStore(settings);
    var authority = new AuthorityProbe(settings, store);
    var proof = authority.probe();
    try (var connection = store.connection(); PreparedStatement statement = connection.prepareStatement(
        "SELECT public.nx_cluster_release_set(?,?,?,?,?,?)")) {
      statement.setString(1, settings.cluster);
      statement.setString(2, args[0]);
      statement.setString(3, args[1]);
      statement.setString(4, args[2]);
      statement.setString(5, proof.revision());
      statement.setTimestamp(6, Timestamp.from(proof.until()));
      statement.execute();
    }
    // Unknown outcomes are not reported as success; an idempotent retry can recover them.
    authority.probe();
    try (var connection = store.connection(); var statement = connection.prepareStatement(
        "SELECT application_sha256,version FROM public.nx_cluster_release WHERE cluster_id=?")) {
      statement.setString(1, settings.cluster);
      try (var rows = statement.executeQuery()) {
        if (!rows.next() || !args[1].equals(rows.getString(1)) || !args[2].equals(rows.getString(2))) {
          throw new IllegalStateException("Release gate verification failed");
        }
      }
    }
    System.out.println("Release gate committed: " + args[1] + " " + args[2]);
  }
}

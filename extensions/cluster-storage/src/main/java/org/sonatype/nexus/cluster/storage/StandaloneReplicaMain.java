/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;

import javax.sql.DataSource;

public final class StandaloneReplicaMain
{
  private StandaloneReplicaMain() {}

  public static void main(final String[] args) throws Exception {
    org.sonatype.nexus.cluster.api.ReplicaAgent agent = new ClusterReplicaFactory().create(new java.util.Properties());
    agent.start();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { agent.close(); } catch (Exception ignored) {} }));
    new CountDownLatch(1).await();
  }

  public static final class JdbcSource implements DataSource
  {
    private final String url, user, password;
    public JdbcSource(final String url, final String user, final String password) { this.url = url; this.user = user; this.password = password; }
    public Connection getConnection() throws SQLException {
      java.util.Properties p = new java.util.Properties();
      p.setProperty("user", user); p.setProperty("password", password);
      p.setProperty("connectTimeout", "3"); p.setProperty("socketTimeout", "20"); p.setProperty("ApplicationName", "nx-replica");
      return DriverManager.getConnection(url, p);
    }
    public Connection getConnection(final String user, final String password) throws SQLException { return DriverManager.getConnection(url, user, password); }
    public java.io.PrintWriter getLogWriter() { return null; }
    public void setLogWriter(final java.io.PrintWriter writer) {}
    public void setLoginTimeout(final int seconds) { DriverManager.setLoginTimeout(seconds); }
    public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
    public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("nx-replica"); }
    public <T> T unwrap(final Class<T> type) throws SQLException { throw new SQLException("Not a wrapper"); }
    public boolean isWrapperFor(final Class<?> type) { return false; }
  }
}

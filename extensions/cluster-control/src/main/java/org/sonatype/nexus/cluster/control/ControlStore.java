/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import java.sql.*;
import java.time.Instant;
import java.util.Properties;

/** Every control operation uses the current primary; no long-lived connection follows a stale primary. */
final class ControlStore {
  record PgState(Instant clock,String node,String dc,boolean recovery) {}
  record Leader(long epoch,String owner,String endpoint,String phase,Instant until,int copies,boolean remote,boolean degraded) {}
  private final Settings settings;
  ControlStore(Settings settings){this.settings=settings;}
  Connection connection()throws Exception {
    Properties p=new Properties();p.setProperty("user",settings.controlUser);p.setProperty("password",Settings.password(settings.controlPasswordFile));
    p.setProperty("connectTimeout","4");p.setProperty("socketTimeout","6");p.setProperty("tcpKeepAlive","true");p.setProperty("targetServerType","primary");
    return DriverManager.getConnection(settings.sqlUrl,p);
  }
  private void assertProofConnection(Connection connection,AuthorityProbe.Proof proof)throws Exception {
    try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("SELECT current_setting('nx_cluster.node_id'),pg_is_in_recovery()")){
      result.next();if(result.getBoolean(2)||!proof.pgNode().equals(result.getString(1)))throw new IllegalStateException("SQL connection changed primary after the authority proof");
    }
  }
  PgState pgState()throws Exception {
    try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT clock_timestamp(),current_setting('nx_cluster.node_id'),current_setting('nx_cluster.dc'),pg_is_in_recovery()")){
      r.next();return new PgState(r.getTimestamp(1).toInstant(),r.getString(2),r.getString(3),r.getBoolean(4));
    }
  }
  String pgStatus()throws Exception {
    try(Connection c=connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT nx_cluster_pg_status()::text")){r.next();return r.getString(1);}
  }
  boolean releaseEligible()throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT NOT EXISTS(SELECT 1 FROM nx_cluster_release WHERE cluster_id=?) OR EXISTS(SELECT 1 FROM nx_cluster_release WHERE cluster_id=? AND application_sha256=? AND version=?)")){
      s.setString(1,settings.cluster);s.setString(2,settings.cluster);s.setString(3,settings.applicationHash);s.setString(4,settings.version);try(ResultSet r=s.executeQuery()){r.next();return r.getBoolean(1);}
    }
  }
  Leader leader()throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT l.epoch,l.leader_id,l.endpoint,l.phase,t.lease_until,p.required_copies,p.require_remote_dc,p.degraded FROM nx_cluster_leader l JOIN nx_cluster_lease t USING(cluster_id) JOIN nx_cluster_policy p USING(cluster_id) WHERE l.cluster_id=? AND l.epoch=t.epoch AND l.epoch=p.epoch AND t.lease_until>clock_timestamp()")){
      s.setString(1,settings.cluster);try(ResultSet r=s.executeQuery()){if(!r.next())return null;return new Leader(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant(),r.getInt(6),r.getBoolean(7),r.getBoolean(8));}
    }
  }
  long acquire(AuthorityProbe.Proof proof)throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_try_acquire(?,?,?,?,?,?,?,?)")){
      assertProofConnection(c,proof);
      s.setString(1,settings.cluster);s.setString(2,settings.owner);s.setString(3,settings.endpoint);s.setString(4,proof.revision());s.setTimestamp(5,Timestamp.from(proof.until()));s.setInt(6,settings.leaseSeconds);s.setString(7,settings.applicationHash);s.setString(8,settings.version);
      try(ResultSet r=s.executeQuery()){r.next();long v=r.getLong(1);return r.wasNull()?0:v;}
    }
  }
  boolean renew(long epoch,AuthorityProbe.Proof proof)throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_renew(?,?,?,?,?,?,?)")){
      assertProofConnection(c,proof);
      s.setString(1,settings.cluster);s.setString(2,settings.owner);s.setLong(3,epoch);s.setString(4,proof.revision());s.setTimestamp(5,Timestamp.from(proof.until()));s.setInt(6,settings.leaseSeconds);s.setString(7,settings.applicationHash);
      try(ResultSet r=s.executeQuery()){r.next();return r.getBoolean(1);}
    }
  }
  void assertCurrent(long epoch)throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_assert_current(?,?,?)")){s.setString(1,settings.cluster);s.setString(2,settings.owner);s.setLong(3,epoch);s.execute();}
  }
  void phase(long epoch,String phase)throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_phase(?,?,?,?)")){s.setString(1,settings.cluster);s.setString(2,settings.owner);s.setLong(3,epoch);s.setString(4,phase);s.execute();}
  }
  void policy(long epoch,boolean degraded,AuthorityProbe.Proof proof)throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_set_policy(?,?,?,?,?,?,?)")){
      assertProofConnection(c,proof);
      s.setString(1,settings.cluster);s.setString(2,settings.owner);s.setLong(3,epoch);s.setInt(4,degraded?1:2);s.setBoolean(5,!degraded);s.setBoolean(6,degraded);s.setString(7,proof.revision());s.execute();
    }
  }
  void fenceExpired()throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_fence_expired(?)")){s.setString(1,settings.cluster);s.execute();}
  }
  void release(long epoch)throws Exception {
    try(Connection c=connection();PreparedStatement s=c.prepareStatement("SELECT nx_cluster_release(?,?,?)")){s.setString(1,settings.cluster);s.setString(2,settings.owner);s.setLong(3,epoch);s.execute();}
  }
}

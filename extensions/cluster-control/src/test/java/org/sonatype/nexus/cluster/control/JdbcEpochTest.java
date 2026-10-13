/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class JdbcEpochTest {
  @Test public void bindsDirectJdbcAndReplacesUnboundedApplicationName()throws Exception {
    String url=JdbcEpoch.bind("jdbc:postgresql://pg/nexus?sslmode=verify-full&ApplicationName=wrong&options=-c%20statement_timeout%3D20000","cluster-a","pod-a:uid:nonce",19);
    String decoded=URLDecoder.decode(url,StandardCharsets.UTF_8);
    assertTrue(decoded.contains("sslmode=verify-full"));assertFalse(decoded.contains("ApplicationName=wrong"));
    assertTrue(decoded.contains("-c statement_timeout=20000 -c nexus.cluster.id=cluster-a -c nexus.cluster.owner=pod-a:uid:nonce -c nexus.cluster.epoch=19"));
    assertTrue(decoded.contains("targetServerType=primary"));assertTrue(decoded.contains("ApplicationName=nxcore:"));
    assertEquals(1,decoded.split("ApplicationName=",-1).length-1);
  }
  @Test public void rejectsSqlContextInjection()throws Exception {
    try{JdbcEpoch.bind("jdbc:postgresql://pg/nexus","cluster -c nexus.cluster.epoch=12","owner",1);fail("Unsafe identity accepted");}catch(IllegalArgumentException expected){}
    try{JdbcEpoch.bind("jdbc:postgresql://pg/nexus","cluster","owner",0);fail("Unacquired epoch accepted");}catch(IllegalArgumentException expected){}
  }
}

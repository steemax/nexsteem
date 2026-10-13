/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;

public class PgAuthorityGuardTest {
  @Test public void oldEpochNeverRenewsOnANewPrimaryBeforeItsCoreIsStopped()throws Exception {
    PgAuthorityGuard oldCore=new PgAuthorityGuard(new PgAuthorityTerm("pg-old","101"));
    AtomicInteger renewalsOnNewPrimary=new AtomicInteger();
    // Same Nexus owner/epoch and a live old JDBC pool do not make a new PG term safe.
    try{oldCore.renew(new PgAuthorityTerm("pg-new","202"),()->{renewalsOnNewPrimary.incrementAndGet();return true;});fail("Old Core renewed on a new primary");}
    catch(IllegalStateException expected){}
    assertEquals(0,renewalsOnNewPrimary.get());
    try{oldCore.require(new PgAuthorityTerm("pg-new","202"));fail("Stale positive response accepted");}catch(IllegalStateException expected){}
  }
  @Test public void leaseReplacementOnTheSameNodeRequiresANewEpochWhileHeartbeatDoesNot()throws Exception {
    PgAuthorityGuard guard=new PgAuthorityGuard(new PgAuthorityTerm("pg-node","303"));
    assertTrue(guard.renew(new PgAuthorityTerm("pg-node","303"),()->true));
    try{guard.renew(new PgAuthorityTerm("pg-node","304"),()->{fail("Renewal executed before term check");return true;});fail("New leader lease adopted by existing Core");}
    catch(IllegalStateException expected){}
    byte[] key="01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII);
    assertNotEquals(CoreHealthProof.sign(key,"cluster","owner",12,"pg-node:303","1000","nonce"),CoreHealthProof.sign(key,"cluster","owner",12,"pg-node:304","1000","nonce"));
  }
}

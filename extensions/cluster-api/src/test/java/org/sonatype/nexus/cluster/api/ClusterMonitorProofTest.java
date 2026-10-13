/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.api;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ClusterMonitorProofTest {
  private static final byte[] KEY = "a".repeat(64).getBytes(StandardCharsets.UTF_8);
  private static final long TIME = 1_700_000_000_000L;
  private static final String NONCE = "0123456789abcdef0123456789abcdef";
  private static final byte[] BODY = "{\"writerReady\":true}".getBytes(StandardCharsets.UTF_8);

  @Test public void matchesIndependentHmacVectorsAndAcceptsFreshReply() {
    assertEquals("74689e308385e44740799bf78756a6fe5d6fdb6f3e2ae37dd1ab67b66826bf0f",
        ClusterMonitorProof.signRequest(KEY, "cluster", "node1", "node2", TIME, NONCE));
    assertEquals("20c2e6f3d2c67524c31497989e8ad23cb6a757469678a066f2ef1788b142b35a",
        ClusterMonitorProof.signReply(KEY, "cluster", "node1", "node2", TIME, NONCE, BODY));
    Map<String, String> headers = reply();
    assertTrue(verify(BODY, headers, TIME + 10));
    assertTrue(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", TIME, NONCE,
        ClusterMonitorProof.signRequest(KEY, "cluster", "node1", "node2", TIME, NONCE), TIME));
  }

  @Test public void requestProofBindsClusterRequesterTargetTimeAndNonce() {
    String signature = ClusterMonitorProof.signRequest(KEY, "cluster", "node1", "node2", TIME, NONCE);
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "other", "node1", "node2", TIME, NONCE, signature, TIME));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node3", "node2", TIME, NONCE, signature, TIME));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node3", TIME, NONCE, signature, TIME));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", TIME + 1, NONCE, signature, TIME));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", TIME,
        "1123456789abcdef0123456789abcdef", signature, TIME));
    assertFalse(ClusterMonitorProof.verifyRequest("b".repeat(64).getBytes(StandardCharsets.UTF_8),
        "cluster", "node1", "node2", TIME, NONCE, signature, TIME));
  }

  @Test public void staleFutureAndOverflowSizedTimesFailClosed() {
    String signature = ClusterMonitorProof.signRequest(KEY, "cluster", "node1", "node2", TIME, NONCE);
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", TIME, NONCE,
        signature, TIME + ClusterMonitorProof.MAX_SKEW_MILLIS + 1));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", TIME, NONCE,
        signature, TIME - ClusterMonitorProof.MAX_SKEW_MILLIS - 1));
    String huge = ClusterMonitorProof.signRequest(KEY, "cluster", "node1", "node2", Long.MAX_VALUE, NONCE);
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", Long.MAX_VALUE, NONCE, huge, TIME));
    assertFalse(verify(BODY, reply(), TIME + ClusterMonitorProof.MAX_SKEW_MILLIS + 1));
  }

  @Test public void replyCannotBeTamperedOrReplayedForAnotherNodeOrRequest() {
    Map<String, String> headers = reply();
    assertFalse(verify("{\"writerReady\":false}".getBytes(StandardCharsets.UTF_8), headers, TIME));
    for (String name : new String[] {ClusterMonitorProof.NODE, ClusterMonitorProof.TIME,
        ClusterMonitorProof.NONCE, ClusterMonitorProof.SIGNATURE}) {
      Map<String, String> tampered = new HashMap<>(headers);
      tampered.put(name, "tampered"); assertFalse(name, verify(BODY, tampered, TIME));
    }
    assertFalse(ClusterMonitorProof.verifyReply(KEY, "cluster", "node1", "node3", TIME, NONCE, BODY, headers::get, TIME));
    assertFalse(ClusterMonitorProof.verifyReply(KEY, "cluster", "node3", "node2", TIME, NONCE, BODY, headers::get, TIME));
    assertFalse(ClusterMonitorProof.verifyReply(KEY, "cluster", "node1", "node2", TIME,
        "1123456789abcdef0123456789abcdef", BODY, headers::get, TIME));
    Map<String, String> requestAsReply = new HashMap<>(headers);
    requestAsReply.put(ClusterMonitorProof.SIGNATURE,
        ClusterMonitorProof.signRequest(KEY, "cluster", "node1", "node2", TIME, NONCE));
    assertFalse(verify(BODY, requestAsReply, TIME));
  }

  @Test public void missingMalformedAndOversizedProofDataCannotValidate() {
    assertFalse(verify(BODY, Map.of(), TIME));
    assertFalse(verify(new byte[ClusterMonitorProof.MAX_BODY_BYTES + 1], reply(), TIME));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster", "node1", "node2", TIME, NONCE, "not-hex", TIME));
    assertFalse(ClusterMonitorProof.verifyRequest(KEY, "cluster\nnode1", "node1", "node2", TIME, NONCE, "a".repeat(64), TIME));
    try {
      ClusterMonitorProof.signRequest(new byte[8], "cluster", "node1", "node2", TIME, NONCE);
      fail("Weak shared key accepted");
    } catch (IllegalArgumentException expected) { }
  }

  private static Map<String, String> reply() {
    return ClusterMonitorProof.replyHeaders(KEY, "cluster", "node1", "node2", TIME, NONCE, BODY);
  }
  private static boolean verify(byte[] body, Map<String, String> headers, long now) {
    return ClusterMonitorProof.verifyReply(KEY, "cluster", "node1", "node2", TIME, NONCE, body, headers::get, now);
  }
}

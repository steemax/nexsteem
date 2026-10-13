/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.util.Map;

import org.junit.Test;
import static org.junit.Assert.*;

public class PeerAuthenticationTest
{
  @Test public void methodPathDigestAndClusterCannotBeAlteredAndNonceCannotBeReplayed() {
    byte[] key = new byte[32]; java.util.Arrays.fill(key, (byte) 19);
    PeerAuthentication auth = new PeerAuthentication(key, "cluster-one");
    String digest = ReplicaTransport.emptyDigest();
    Map<String, String> headers = auth.headers("member-two", "POST", "/v1/stage/1", digest);
    assertRejected(auth, headers, "GET", "/v1/stage/1", digest);
    assertRejected(auth, headers, "POST", "/v1/stage/2", digest);
    assertRejected(auth, headers, "POST", "/v1/stage/1", "f".repeat(64));
    assertRejected(new PeerAuthentication(key, "other-cluster"), headers, "POST", "/v1/stage/1", digest);
    validate(auth, headers, "POST", "/v1/stage/1", digest);
    assertRejected(auth, headers, "POST", "/v1/stage/1", digest);
  }

  private void assertRejected(final PeerAuthentication auth, final Map<String, String> headers,
      final String method, final String path, final String digest)
  {
    try { validate(auth, headers, method, path, digest); fail("Invalid or replayed message accepted"); }
    catch (SecurityException expected) {}
  }

  private void validate(final PeerAuthentication auth, final Map<String, String> h,
      final String method, final String path, final String digest)
  {
    auth.validate(h.get("X-Cluster-Node"), method, path, h.get("X-Cluster-Time"), h.get("X-Cluster-Nonce"), digest, h.get("X-Cluster-Signature"));
  }
}

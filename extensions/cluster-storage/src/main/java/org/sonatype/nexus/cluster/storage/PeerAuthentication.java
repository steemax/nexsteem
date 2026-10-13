/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC binds origin identity, method, path, time, nonce and content digest. TLS supplies confidentiality. */
public final class PeerAuthentication
{
  private final byte[] key;
  private final String cluster;
  private final Map<String, Long> seen = new ConcurrentHashMap<>();

  public PeerAuthentication(final byte[] key, final String cluster) { this.key = key.clone(); this.cluster = cluster; }

  public Map<String, String> headers(final String node, final String method, final String path, final String digest) {
    String time = Long.toString(Instant.now().getEpochSecond()), nonce = UUID.randomUUID().toString();
    return Map.of("X-Cluster-Id", cluster, "X-Cluster-Node", node, "X-Cluster-Time", time,
        "X-Cluster-Nonce", nonce, "X-Cluster-Digest", digest,
        "X-Cluster-Signature", sign(node, method, path, time, nonce, digest));
  }

  public void validate(final String node, final String method, final String path, final String time,
      final String nonce, final String digest, final String signature)
  {
    long now = Instant.now().getEpochSecond();
    long supplied;
    try { supplied = Long.parseLong(time); }
    catch (RuntimeException e) { throw new SecurityException("Invalid replica request time"); }
    if (Math.abs(now - supplied) > 30 || nonce == null || !nonce.matches("[A-Za-z0-9-]{20,80}")
        || digest == null || !digest.matches("[a-f0-9]{64}")) {
      throw new SecurityException("Expired or invalid replica request");
    }
    byte[] expected = sign(node, method, path, time, nonce, digest).getBytes(StandardCharsets.US_ASCII);
    if (signature == null || !MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
      throw new SecurityException("Replica signature mismatch");
    }
    seen.entrySet().removeIf(e -> e.getValue() < now - 30);
    if (seen.size() > 100000 || seen.putIfAbsent(node + ":" + nonce, supplied) != null) {
      throw new SecurityException("Replayed replica request");
    }
  }

  public String sign(final String node, final String method, final String path, final String time,
      final String nonce, final String digest)
  {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
      String canonical = cluster + "\n" + node + "\n" + method + "\n" + path + "\n" + time + "\n" + nonce + "\n" + digest;
      return Base64.getEncoder().encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }
    catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
  }
}

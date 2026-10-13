/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Read-only node observations are bound to the caller's fresh request and expected node. */
public final class ClusterMonitorProof {
  public static final String PATH = "/_cluster/monitor";
  public static final int MAX_BODY_BYTES = 65_536;
  public static final long MAX_SKEW_MILLIS = 30_000;
  public static final String REQUESTER = "X-Nexus-Monitor-Requester";
  public static final String TARGET = "X-Nexus-Monitor-Target";
  public static final String TIME = "X-Nexus-Monitor-Time";
  public static final String NONCE = "X-Nexus-Monitor-Nonce";
  public static final String SIGNATURE = "X-Nexus-Monitor-Signature";
  public static final String NODE = "X-Nexus-Monitor-Node";

  private ClusterMonitorProof() {}

  public static String newNonce() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public static Map<String, String> requestHeaders(byte[] key, String cluster, String requester,
                                                  String target, long time, String nonce) {
    return Map.of(REQUESTER, requester, TARGET, target, TIME, Long.toString(time), NONCE, nonce,
        SIGNATURE, signRequest(key, cluster, requester, target, time, nonce));
  }

  public static Map<String, String> replyHeaders(byte[] key, String cluster, String requester,
                                                String target, long time, String nonce, byte[] body) {
    return Map.of(NODE, target, TIME, Long.toString(time), NONCE, nonce,
        SIGNATURE, signReply(key, cluster, requester, target, time, nonce, body));
  }

  public static String signRequest(byte[] key, String cluster, String requester, String target,
                                   long time, String nonce) {
    validate(key, cluster, requester, target, time, nonce);
    return mac(key, cluster + "\n" + requester + "\n" + target + "\nGET\n" + PATH
        + "\n" + time + "\n" + nonce);
  }

  public static String signReply(byte[] key, String cluster, String requester, String target,
                                 long time, String nonce, byte[] body) {
    validate(key, cluster, requester, target, time, nonce);
    if (body == null || body.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("Monitor reply is too large");
    }
    return mac(key, cluster + "\n" + target + "\n" + requester + "\nGET\n" + PATH
        + "\n" + time + "\n" + nonce + "\n" + digest(body));
  }

  public static boolean verifyRequest(byte[] key, String cluster, String requester, String target,
                                       long time, String nonce, String signature, long now) {
    try {
      return fresh(time, now) && equalSignature(signature,
          signRequest(key, cluster, requester, target, time, nonce));
    }
    catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  public static boolean verifyReply(byte[] key, String cluster, String requester, String target,
                                     long time, String nonce, byte[] body,
                                     Function<String, String> header, long now) {
    try {
      return header != null && fresh(time, now) && target.equals(header.apply(NODE))
          && Long.toString(time).equals(header.apply(TIME)) && nonce.equals(header.apply(NONCE))
          && equalSignature(header.apply(SIGNATURE),
              signReply(key, cluster, requester, target, time, nonce, body));
    }
    catch (IllegalArgumentException | NullPointerException invalid) {
      return false;
    }
  }

  private static boolean fresh(long time, long now) {
    return time >= 0 && now >= 0 && Math.abs(now - time) <= MAX_SKEW_MILLIS;
  }

  private static void validate(byte[] key, String cluster, String requester, String target,
                               long time, String nonce) {
    if (key == null || key.length < 32 || key.length > 4096 || !identity(cluster)
        || !identity(requester) || !identity(target) || time < 0
        || nonce == null || !nonce.matches("[a-f0-9]{32}")) {
      throw new IllegalArgumentException("Invalid monitor proof context");
    }
  }

  private static boolean identity(String value) {
    return value != null && value.matches("[A-Za-z0-9_.:-]{1,253}");
  }

  private static String mac(byte[] key, String text) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
    catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Monitor authentication unavailable", unavailable);
    }
  }

  private static String digest(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }
    catch (GeneralSecurityException unavailable) {
      throw new IllegalStateException("Monitor authentication unavailable", unavailable);
    }
  }

  private static boolean equalSignature(String supplied, String expected) {
    return supplied != null && supplied.matches("[a-f0-9]{64}")
        && MessageDigest.isEqual(HexFormat.of().parseHex(supplied), HexFormat.of().parseHex(expected));
  }
}

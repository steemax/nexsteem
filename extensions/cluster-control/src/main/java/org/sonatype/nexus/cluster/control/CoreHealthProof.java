/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Loopback health is authenticated independently of a user's login or anonymous-access setting. */
final class CoreHealthProof {
  static final String PATH="/service/rest/v1/cluster/core-ready";
  static String sign(byte[] key,String cluster,String owner,long epoch,String pgTerm,String time,String nonce)throws Exception {
    Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(key,"HmacSHA256"));
    return Base64.getEncoder().encodeToString(hmac.doFinal((cluster+"\n"+owner+"\n"+epoch+"\n"+pgTerm+"\n"+time+"\n"+nonce).getBytes(StandardCharsets.UTF_8)));
  }
}

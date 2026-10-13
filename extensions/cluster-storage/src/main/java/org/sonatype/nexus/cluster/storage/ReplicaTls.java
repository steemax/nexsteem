/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class ReplicaTls
{
  private ReplicaTls() {}

  public static SSLContext context(final ClusterSettings settings) throws Exception {
    if (settings.testHttp) { return null; }
    KeyStore identity = KeyStore.getInstance("PKCS12");
    char[] password = Files.readString(Path.of(settings.needed("NX_CLUSTER_TLS_PASSWORD_FILE"))).trim().toCharArray();
    try (java.io.InputStream bytes = Files.newInputStream(Path.of(settings.needed("NX_CLUSTER_TLS_KEYSTORE_FILE")))) {
      identity.load(bytes, password);
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(identity, password);
    java.util.Arrays.fill(password, '\u0000');
    KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null, null);
    try (java.io.InputStream bytes = Files.newInputStream(Path.of(settings.needed("NX_CLUSTER_TLS_CA_FILE")))) {
      int index = 0;
      for (java.security.cert.Certificate cert : CertificateFactory.getInstance("X.509").generateCertificates(bytes)) {
        trust.setCertificateEntry("ca-" + index++, cert);
      }
    }
    TrustManagerFactory trusted = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); trusted.init(trust);
    SSLContext tls = SSLContext.getInstance("TLS"); tls.init(keys.getKeyManagers(), trusted.getTrustManagers(), null);
    return tls;
  }
}

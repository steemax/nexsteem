/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import org.apache.shiro.authc.AuthenticationToken;

/** Never print credentials: Nexus logs token.toString() at debug level. */
public final class KeycloakBearerToken implements AuthenticationToken
{
  private final String value;
  private final String rateLimitKey;

  public KeycloakBearerToken(String value) {
    this.value = value;
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      rateLimitKey = "keycloak-token:" + java.util.HexFormat.of().formatHex(digest);
    }
    catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  @Override
  public Object getPrincipal() { return rateLimitKey; }

  @Override
  public Object getCredentials() { return value; }

  @Override
  public String toString() { return "KeycloakBearerToken[redacted]"; }
}

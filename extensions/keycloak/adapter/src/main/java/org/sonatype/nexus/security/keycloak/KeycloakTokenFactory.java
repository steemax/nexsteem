/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.apache.shiro.authc.AuthenticationToken;
import org.sonatype.nexus.security.authc.AuthenticationTokenFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
import org.sonatype.nexus.security.authc.apikey.ApiKeyExtractor;

@Component
@ConditionalOnProperty(name = "nexus.keycloak.enabled", havingValue = "true")
public final class KeycloakTokenFactory implements AuthenticationTokenFactory
{
  private final List<ApiKeyExtractor> nativeTokens;

  public KeycloakTokenFactory(List<ApiKeyExtractor> nativeTokens) { this.nativeTokens = nativeTokens; }

  @Override
  public AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
    String header = ((HttpServletRequest) request).getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return null;
    }
    // npm/Docker format credentials are handled by their native Nexus realms.
    if (nativeTokens.stream().anyMatch(extractor -> extractor.extract((HttpServletRequest) request) != null)) {
      return null;
    }
    // Invalid/empty bearer tokens still reach the verifier and fail authentication.
    // Do not silently downgrade a supplied invalid credential to anonymous access.
    return new KeycloakBearerToken(header.substring(7));
  }
}

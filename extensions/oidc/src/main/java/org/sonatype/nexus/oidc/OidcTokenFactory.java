/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.nimbusds.jwt.SignedJWT;
import org.apache.shiro.authc.AuthenticationToken;
import org.sonatype.nexus.security.authc.AuthenticationTokenFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.sonatype.nexus.security.authc.apikey.ApiKeyExtractor;
import java.util.List;

@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class OidcTokenFactory implements AuthenticationTokenFactory {
  private final OidcConfigurationStore configuration;
  private final List<ApiKeyExtractor> nativeTokens;
  private final String legacyIssuer;
  public OidcTokenFactory(OidcConfigurationStore configuration, List<ApiKeyExtractor> nativeTokens,
      @Value("${nexus.keycloak.issuer:}") String legacyIssuer) {
    this.configuration = configuration; this.nativeTokens = nativeTokens; this.legacyIssuer = legacyIssuer;
  }
  public AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
    String header = ((HttpServletRequest) request).getHeader("Authorization");
    if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) { return null; }
    OidcConfiguration config = configuration.get();
    if (!config.configured()) { return null; }
    if (nativeTokens.stream().anyMatch(extractor -> extractor.extract((HttpServletRequest) request) != null)) { return null; }
    String token = header.substring(7);
    // The unverified issuer is only a selector between independently trusted realms.
    // The selected realm still verifies signature, issuer, audience and expiry.
    try {
      if (token.length() <= 32768) {
        String issuer = SignedJWT.parse(token).getJWTClaimsSet().getIssuer();
        if (!config.issuer.equals(issuer) && !legacyIssuer.isBlank() && legacyIssuer.equals(issuer)) { return null; }
      }
    }
    catch (Exception invalid) { /* Other native API token factories may recognize this credential. */ }
    return new OidcAuthenticationToken(token, false);
  }
}

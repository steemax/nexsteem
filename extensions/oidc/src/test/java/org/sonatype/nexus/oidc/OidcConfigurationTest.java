/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import java.util.Base64;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
public class OidcConfigurationTest {
  private OidcConfiguration config;
  @Before public void setup() {
    config = new OidcConfiguration(); config.clientId = "nexus";
    config.idpAuthorizationUrl = "https://idp.example.org/realms/test/protocol/openid-connect/auth";
    config.idpLogoutUrl = "https://idp.example.org/realms/test/protocol/openid-connect/logout";
    config.idpTokenUrl = "https://idp.example.org/realms/test/protocol/openid-connect/token";
    config.idpJwksUrl = "https://idp.example.org/realms/test/protocol/openid-connect/certs";
  }
  @Test public void keycloakIssuerAndAudienceDefaults() {
    config.validate(false, "https://nexus.example.org/nexus/");
    assertEquals("https://idp.example.org/realms/test", config.issuer); assertEquals("nexus", config.audience);
    assertEquals("https://nexus.example.org/nexus/oidc/callback", config.callbackUrl());
  }
  @Test(expected = IllegalArgumentException.class) public void noHttpByDefault() { config.idpTokenUrl = "http://idp.example.org/token"; config.validate(false, "https://nexus.example.org"); }
  @Test(expected = IllegalArgumentException.class) public void noCredentialsInUrl() { config.idpTokenUrl = "https://user:password@idp.example.org/token"; config.validate(false, "https://nexus.example.org"); }
  @Test(expected = IllegalArgumentException.class) public void customParamsCannotReplaceNonce() { config.authorizationCustomParams = Map.of("nonce", "attacker"); config.validate(false, "https://nexus.example.org"); }
  @Test(expected = IllegalArgumentException.class) public void customParamsCannotReplacePkce() { config.tokenRequestCustomParams = Map.of("code_verifier", "attacker"); config.validate(false, "https://nexus.example.org"); }
  @Test(expected = IllegalArgumentException.class) public void forbidsHmacAlgorithm() { config.idpJwsAlgorithm = "HS256"; config.validate(false, "https://nexus.example.org"); }
  @Test public void returnToAllowsLocalHashOnly() {
    assertEquals("#admin/repository/repositories", OidcServlet.returnHash(Base64.getEncoder().encodeToString("#admin/repository/repositories".getBytes())));
  }
  @Test(expected = SecurityException.class) public void rejectsExternalReturnTo() { OidcServlet.returnHash(Base64.getEncoder().encodeToString("https://evil.example.org".getBytes())); }
  @Test(expected = SecurityException.class) public void rejectsCrLfReturnTo() { OidcServlet.returnHash(Base64.getEncoder().encodeToString("#browse\r\nLocation: https://evil.example.org".getBytes())); }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import org.apache.shiro.authc.AuthenticationToken;
public final class OidcAuthenticationToken implements AuthenticationToken {
  private final String credential;
  private final boolean browser;
  public OidcAuthenticationToken(String credential, boolean browser) { this.credential = credential; this.browser = browser; }
  public boolean browser() { return browser; }
  public Object getCredentials() { return credential; }
  public Object getPrincipal() { return "oidc:" + OidcPersistentStore.hash(credential); }
  public String toString() { return "OidcAuthenticationToken[redacted]"; }
}

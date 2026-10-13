/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import jakarta.servlet.http.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class OidcCsrfTest {
  @Test public void sameOriginMatchingTokenAllowed() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-origin");
    when(request.getHeader("NX-ANTI-CSRF-TOKEN")).thenReturn("test-token");
    when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("NX-ANTI-CSRF-TOKEN", "test-token")});
    assertTrue(OidcBrowserFilter.validCsrf(request));
  }
  @Test public void crossSiteEvenWithMatchingTokenRejected() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("Sec-Fetch-Site")).thenReturn("same-site");
    when(request.getHeader("NX-ANTI-CSRF-TOKEN")).thenReturn("test-token");
    when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("NX-ANTI-CSRF-TOKEN", "test-token")});
    assertFalse(OidcBrowserFilter.validCsrf(request));
  }
  @Test public void mismatchedAndMissingTokensRejected() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    assertFalse(OidcBrowserFilter.validCsrf(request));
    when(request.getHeader("NX-ANTI-CSRF-TOKEN")).thenReturn("wrong");
    when(request.getCookies()).thenReturn(new Cookie[]{new Cookie("NX-ANTI-CSRF-TOKEN", "test-token")});
    assertFalse(OidcBrowserFilter.validCsrf(request));
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.*;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.util.ThreadContext;
import org.apache.shiro.web.servlet.AdviceFilter;
import org.sonatype.nexus.security.authc.AntiCsrfHelper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs inside the Shiro subject before the existing authentication and CSRF filters. */
@Component @WebFilter(filterName = OidcBrowserFilter.NAME) @Order(-10)
public class OidcBrowserFilter extends AdviceFilter {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OidcBrowserFilter.class);
  public static final String NAME = "nx-open-oidc", COOKIE = "NX-OIDC-SESSION", BINDING = "NX-OIDC-LOGIN";
  private static final String ORIGINAL = OidcBrowserFilter.class.getName() + ".originalSubject";
  private final OidcClient client;
  private final OidcPersistentStore store;
  private final OidcStateContributor enabled;
  private final OidcConfigurationStore configuration;
  private final AntiCsrfHelper csrf;
  public OidcBrowserFilter(OidcClient client, OidcPersistentStore store, OidcStateContributor enabled,
      OidcConfigurationStore configuration, AntiCsrfHelper csrf) {
    this.client = client; this.store = store; this.enabled = enabled; this.configuration = configuration; this.csrf = csrf;
  }
  @Override protected boolean preHandle(ServletRequest request, ServletResponse response) throws Exception {
    HttpServletRequest http = (HttpServletRequest) request; HttpServletResponse out = (HttpServletResponse) response;
    String session = cookie(http, COOKIE);
    if (session == null || http.getHeader("Authorization") != null) { return true; }
    Subject subject = SecurityUtils.getSubject();
    boolean localLogin = http.getRequestURI().equals(http.getContextPath() + "/service/rapture/session") && "POST".equals(http.getMethod());
    if (localLogin) {
      store.revoke(session); clearCookie(out, http, COOKIE);
      if (oidc(subject)) { subject.logout(); }
      return true;
    }
    if (!enabled.isSsoEnabled()) { store.revoke(session); clearCookie(out, http, COOKIE); if (oidc(subject)) { subject.logout(); } return true; }
    try {
      if (!safeMethod(http) && !validCsrf(http)) {
        out.sendError(401, "Anti cross-site request forgery token mismatch"); return false;
      }
      if (http.getRequestURI().equals(http.getContextPath() + "/service/rapture/session") && "DELETE".equals(http.getMethod())) {
        String destination = client.logout(session); subject.logout(); clearCookie(out, http, COOKIE);
        out.setHeader("Location", destination); out.setStatus(204); return false;
      }
      // The opaque SQL session is authoritative. Do not repeatedly mutate the Shiro
      // browser session on parallel requests; that causes stale-session races on login.
      String revision = configuration.revision();
      var profile = client.session(session);
      if (!revision.equals(configuration.revision())) { throw new SecurityException("OIDC configuration changed"); }
      var principals = new SimplePrincipalCollection(profile.userId(), OidcRealm.NAME);
      principals.add(new OidcRealm.Identity(profile, revision), OidcRealm.NAME);
      Subject authenticated = new Subject.Builder(SecurityUtils.getSecurityManager()).principals(principals)
          .authenticated(true).sessionCreationEnabled(false).buildSubject();
      http.setAttribute(ORIGINAL, subject); ThreadContext.bind(authenticated);
      org.sonatype.nexus.security.UserIdMdcHelper.set(authenticated);
      http.setAttribute(org.sonatype.nexus.security.SecurityFilter.ATTR_USER_ID, profile.userId());
      http.setAttribute(org.sonatype.nexus.security.SecurityFilter.ATTR_USER_PRINCIPAL,
          (java.security.Principal) profile::userId);
      return true;
    }
    catch (Exception rejected) {
      Throwable reason = rejected;
      while (reason.getCause() != null && reason.getCause() != reason) { reason = reason.getCause(); }
      // Diagnostics exclude credentials, tokens, request query and provider response bodies.
      String message = reason instanceof SecurityException && reason.getMessage() != null && reason.getMessage().startsWith("OIDC ")
          ? reason.getMessage() : "details redacted";
      log.warn("OIDC browser request rejected at {}: {} ({})", http.getRequestURI(), reason.getClass().getSimpleName(), message);
      store.revoke(session); clearCookie(out, http, COOKIE); if (oidc(subject)) { subject.logout(); }
      out.sendError(401, "OIDC session expired or rejected"); return false;
    }
  }
  @Override public void afterCompletion(ServletRequest request, ServletResponse response, Exception failure) {
    Subject original = (Subject) request.getAttribute(ORIGINAL);
    if (original != null) { ThreadContext.bind(original); org.sonatype.nexus.security.UserIdMdcHelper.set(original); }
  }
  private static boolean safeMethod(HttpServletRequest request) {
    return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod());
  }
  static boolean validCsrf(HttpServletRequest request) {
    String site = request.getHeader("Sec-Fetch-Site");
    if (site != null && !"same-origin".equals(site) && !"none".equals(site)) { return false; }
    String header = request.getHeader(AntiCsrfHelper.ANTI_CSRF_TOKEN_NAME);
    String cookie = cookie(request, AntiCsrfHelper.ANTI_CSRF_TOKEN_NAME);
    return header != null && cookie != null && java.security.MessageDigest.isEqual(
        header.getBytes(java.nio.charset.StandardCharsets.UTF_8), cookie.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
  private static boolean oidc(Subject subject) { return subject.getPrincipals() != null && subject.getPrincipals().getRealmNames().contains(OidcRealm.NAME); }
  public static String cookie(HttpServletRequest request, String name) {
    Cookie[] cookies = request.getCookies();
    if (cookies != null) { for (Cookie cookie : cookies) { if (name.equals(cookie.getName())) { return cookie.getValue(); } } }
    return null;
  }
  public static void cookie(HttpServletResponse response, HttpServletRequest request, String name, String value, int age, boolean secure, boolean httpOnly) {
    String path = request.getContextPath().isEmpty() ? "/" : request.getContextPath() + "/";
    response.addHeader("Set-Cookie", name + "=" + value + "; Path=" + path + "; Max-Age=" + age + "; SameSite=Lax" +
        (secure ? "; Secure" : "") + (httpOnly ? "; HttpOnly" : ""));
  }
  public void clearCookie(HttpServletResponse response, HttpServletRequest request, String name) {
    cookie(response, request, name, "", 0, configuration.get().publicUrl.startsWith("https://"), true);
  }
}

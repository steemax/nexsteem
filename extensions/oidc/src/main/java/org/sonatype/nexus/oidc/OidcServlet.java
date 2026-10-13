/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import org.apache.shiro.SecurityUtils;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.authc.LoginEvent;
import org.springframework.stereotype.Component;

@Component @WebServlet("/oidc/*")
public class OidcServlet extends HttpServlet {
  private final OidcConfigurationStore configuration;
  private final OidcPersistentStore store;
  private final OidcClient client;
  private final OidcStateContributor enabled;
  private final EventManager events;
  public OidcServlet(OidcConfigurationStore configuration, OidcPersistentStore store, OidcClient client,
      OidcStateContributor enabled, EventManager events) {
    this.configuration = configuration; this.store = store; this.client = client; this.enabled = enabled; this.events = events;
  }
  @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
    response.setHeader("Referrer-Policy", "no-referrer");
    if (!enabled.isSsoEnabled()) { response.sendError(503, "Configure OAuth 2.0 and activate OAuth2Realm first"); return; }
    try {
      switch (request.getPathInfo()) {
        case "/login" -> login(request, response);
        case "/callback" -> callback(request, response);
        default -> response.sendError(404);
      }
    }
    catch (Exception failure) {
      // Provider response, tokens and code are deliberately excluded from logs and error pages.
      response.sendError(401, "OpenID Connect login was rejected. Retry signing in or contact the Nexus administrator.");
    }
  }
  private void login(HttpServletRequest request, HttpServletResponse response) throws Exception {
    OidcConfiguration config = configuration.get();
    String binding = store.random(), verifier = store.random();
    OidcPersistentStore.PendingLogin pending = new OidcPersistentStore.PendingLogin();
    pending.nonce = store.random(); pending.bindingHash = OidcPersistentStore.hash(binding);
    pending.returnHash = returnHash(request.getParameter("hash")); pending.revision = configuration.revision();
    pending.expiresAt = Instant.now().plusSeconds(300).getEpochSecond(); pending.verifierSecret = store.encrypt(verifier);
    String state = store.createLogin(pending);
    String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    var parameters = new LinkedHashMap<>(config.authorizationCustomParams);
    parameters.put("client_id", config.clientId); parameters.put("response_type", "code"); parameters.put("redirect_uri", config.callbackUrl());
    parameters.put("scope", "openid profile email"); parameters.put("state", state); parameters.put("nonce", pending.nonce);
    parameters.put("code_challenge", challenge); parameters.put("code_challenge_method", "S256");
    OidcBrowserFilter.cookie(response, request, OidcBrowserFilter.BINDING, binding, 300, config.publicUrl.startsWith("https://"), true);
    response.sendRedirect(config.idpAuthorizationUrl + "?" + OidcHttpClient.encode(parameters));
  }
  private void callback(HttpServletRequest request, HttpServletResponse response) throws Exception {
    OidcPersistentStore.PendingLogin pending = store.consumeLogin(request.getParameter("state"), OidcBrowserFilter.cookie(request, OidcBrowserFilter.BINDING));
    String session = null;
    try {
      String code = request.getParameter("code");
      if (request.getParameter("error") != null || code == null || code.isBlank() || code.length() > 4096) { throw new SecurityException("OIDC code missing"); }
      session = client.complete(pending, code);
      var subject = SecurityUtils.getSubject();
      subject.logout();
      events.post(new LoginEvent(store.session(session).identity.userId(), OidcRealm.NAME));
      OidcConfiguration config = configuration.get(); boolean secure = config.publicUrl.startsWith("https://");
      OidcBrowserFilter.cookie(response, request, OidcBrowserFilter.BINDING, "", 0, secure, true);
      OidcBrowserFilter.cookie(response, request, OidcBrowserFilter.COOKIE, session, 28800, secure, true);
      OidcBrowserFilter.cookie(response, request, "NX-ANTI-CSRF-TOKEN", store.random(), 28800, secure, false);
      response.sendRedirect(config.publicUrl + "/" + pending.returnHash);
    }
    catch (Exception rejected) { if (session != null) { store.revoke(session); } throw rejected; }
    finally { store.finishLogin(pending); }
  }
  static String returnHash(String encoded) {
    if (encoded == null || encoded.isBlank()) { return "#browse/welcome"; }
    if (encoded.length() > 2048) { throw new SecurityException("OIDC return location too long"); }
    String hash;
    try { hash = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
    catch (IllegalArgumentException failure) { throw new SecurityException("Invalid OIDC return location"); }
    if (!hash.matches("#[A-Za-z0-9/_?%=&:.~-]{0,1500}")) { throw new SecurityException("OIDC return location must be a local hash route"); }
    return hash;
  }
}

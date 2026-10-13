/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.extensions.http;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicAuthCache;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.httpclient.config.ProxyServerConfiguration;
import org.sonatype.nexus.httpclient.config.UsernameAuthenticationConfiguration;
import org.sonatype.nexus.httpclient.config.NtlmAuthenticationConfiguration;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.httpclient.OutboundRequestMetricRecorder;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;
import org.springframework.stereotype.Component;

/** Protect repository credentials when an index or registry redirects downloads to another origin. */
@Component
public final class ScopedProxyRequests {
  private final AntiSsrfService ssrf;
  private final HttpClientManager clients;
  public ScopedProxyRequests(AntiSsrfService ssrf, HttpClientManager clients) { this.ssrf = ssrf; this.clients = clients; }

  private BasicCredentialsProvider proxyCredentials() {
    var provider = new BasicCredentialsProvider() {
      @Override public org.apache.http.auth.Credentials getCredentials(AuthScope scope) {
        // Core's target preemptive interceptor asks for an unspecified scheme.
        // Only an explicit Basic/Digest/NTLM proxy challenge may request credentials.
        return scope.getScheme() == AuthScope.ANY_SCHEME ? null : super.getCredentials(scope);
      }
    };
    var configuration = clients.getConfiguration();
    var proxy = configuration == null ? null : configuration.getProxy();
    if (proxy != null) { addProxyCredentials(provider, proxy.getHttp()); addProxyCredentials(provider, proxy.getHttps()); }
    return provider;
  }
  private static void addProxyCredentials(BasicCredentialsProvider provider, ProxyServerConfiguration proxy) {
    if (proxy == null || !proxy.isEnabled() || proxy.getAuthentication() == null) { return; }
    var authentication = proxy.getAuthentication();
    if (authentication instanceof UsernameAuthenticationConfiguration auth) {
      String password = auth.getPassword() == null ? null : new String(auth.getPassword().decrypt());
      provider.setCredentials(new AuthScope(proxy.getHost(), proxy.getPort()), new UsernamePasswordCredentials(auth.getUsername(), password));
    }
    else if (authentication instanceof NtlmAuthenticationConfiguration auth) {
      String password = auth.getPassword() == null ? null : new String(auth.getPassword().decrypt());
      provider.setCredentials(new AuthScope(proxy.getHost(), proxy.getPort()), new NTCredentials(auth.getUsername(), password, auth.getHost(), auth.getDomain()));
    }
  }
  public static String origin(URI uri) {
    if (uri.getHost() == null || uri.getScheme() == null) { return ""; }
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":"
        + (uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
  }
  private void validate(URI uri, URI registry) throws IOException {
    if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())
             && "http".equalsIgnoreCase(registry.getScheme()))) { throw new IOException("Rejected proxy download URL"); }
    try { ssrf.validateHost(uri.getHost()); }
    catch (RuntimeException blocked) { throw new IOException("Rejected proxy download host", blocked); }
  }
  public HttpResponse execute(Context context, HttpClient client, HttpRequestBase initial, URI registry, String bearer) throws IOException {
    HttpRequestBase request = initial;
    for (int redirects = 0; ; redirects++) {
      validate(request.getURI(), registry);
      boolean trusted = origin(registry).equals(origin(request.getURI()));
      RequestConfig base = request.getConfig();
      if (base == null && client instanceof Configurable configured) { base = configured.getConfig(); }
      if (base == null) { base = RequestConfig.DEFAULT; }
      var settings = RequestConfig.copy(base).setRedirectsEnabled(false).setNormalizeUri(false);
      HttpClientContext http = HttpClientContext.create();
      var repository = context.getRepository();
      if (repository != null && repository.getFormat() != null && repository.getType() != null) {
        http.setAttribute(OutboundRequestMetricRecorder.CONTEXT_FORMAT, repository.getFormat().getValue());
        http.setAttribute(OutboundRequestMetricRecorder.CONTEXT_REPOSITORY_TYPE, repository.getType().getValue());
      }
      if (trusted && bearer != null && !bearer.isBlank()) { request.setHeader("Authorization", "Bearer " + bearer); }
      if (!trusted) {
        request.removeHeaders("Authorization"); request.removeHeaders("Cookie");
        request.removeHeaders("Proxy-Authorization");
        settings.setCookieSpec(CookieSpecs.IGNORE_COOKIES);
        // AuthenticationEnabled controls both target and proxy. Keep 407 negotiation
        // active, but supply only explicitly configured forward-proxy credentials.
        settings.setAuthenticationEnabled(true);
        // No target authentication scheme is registered with this name. Proxy
        // preferred schemes remain inherited from the normal Core configuration.
        settings.setTargetPreferredAuthSchemes(java.util.List.of("nexus-disabled-target-auth"));
        http.setCredentialsProvider(proxyCredentials());
        http.setAuthCache(new BasicAuthCache());
      }
      request.setConfig(settings.build());
      HttpResponse response = client.execute(request, http);
      context.setAttribute("request.http_context", http);
      if (!Set.of(301, 302, 303, 307, 308).contains(response.getStatusLine().getStatusCode())) { return response; }
      Header location = response.getFirstHeader("Location"); URI next;
      try {
        if (redirects >= 5 || location == null) { throw new IOException("Proxy redirect limit exceeded"); }
        next = request.getURI().resolve(location.getValue()); validate(next, registry);
        if ("https".equalsIgnoreCase(request.getURI().getScheme()) && !"https".equalsIgnoreCase(next.getScheme())) {
          throw new IOException("Rejected HTTPS proxy downgrade");
        }
      } catch (IOException | RuntimeException unsafe) {
        HttpClientUtils.closeQuietly(response); throw new IOException("Rejected proxy redirect", unsafe);
      }
      HttpClientUtils.closeQuietly(response);
      HttpRequestBase redirected = request instanceof HttpHead ? new HttpHead(next) : new HttpGet(next);
      for (String key : new String[]{"Accept", "Accept-Encoding", "Range", "If-Range", "If-None-Match", "If-Modified-Since"}) {
        for (Header header : request.getHeaders(key)) { redirected.addHeader(header); }
      }
      if (trusted && origin(next).equals(origin(registry))) {
        Header authorization = request.getFirstHeader("Authorization"); if (authorization != null) { redirected.setHeader(authorization); }
      }
      request = redirected;
    }
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.extensions.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.http.HttpResponse;
import org.apache.http.HttpVersion;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.httpclient.PreemptiveAuthHttpRequestInterceptor;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyServerConfiguration;
import org.sonatype.nexus.httpclient.config.UsernameAuthenticationConfiguration;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.apache.http.HttpHost;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Apache authentication/cookie behavior across two port origins, plus URL guard failures. */
public class ScopedProxyRequestsTest {
  private HttpServer origin, foreign;
  private URI remote;
  private AntiSsrfService ssrf;
  private ScopedProxyRequests requests;
  private CloseableHttpClient client;
  private Context context;
  private HttpClientManager clients;
  private String expectedAuth = "Basic Zml4dHVyZTpwYXNzd29yZA==";
  private final AtomicInteger authorized = new AtomicInteger();
  private final AtomicInteger foreignCount = new AtomicInteger();
  private final List<Boolean> leakedAuth = new ArrayList<>(), leakedCookies = new ArrayList<>();
  private volatile boolean originCookieSeen;
  private volatile String forwardedRange, forwardedIfRange;

  @Before public void setup() throws Exception {
    ssrf = mock(AntiSsrfService.class); clients = mock(HttpClientManager.class); requests = new ScopedProxyRequests(ssrf, clients);
    context = mock(Context.class);
    origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    origin.createContext("/", this::origin);
    foreign.createContext("/", exchange -> {
      foreignCount.incrementAndGet();
      leakedAuth.add(exchange.getRequestHeaders().containsKey("Authorization"));
      leakedCookies.add(exchange.getRequestHeaders().containsKey("Cookie"));
      forwardedRange = exchange.getRequestHeaders().getFirst("Range");
      forwardedIfRange = exchange.getRequestHeaders().getFirst("If-Range");
      if(exchange.getRequestURI().getPath().equals("/trap")) {
        exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"foreign\""); respond(exchange, 401);
      } else { respond(exchange, 200); }
    });
    origin.start(); foreign.start(); remote = URI.create("http://127.0.0.1:" + origin.getAddress().getPort() + "/");
    var credentials = new BasicCredentialsProvider();
    credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("fixture", "password"));
    client = HttpClients.custom().setDefaultCredentialsProvider(credentials).build();
  }
  @After public void teardown() throws Exception {
    if(client != null) client.close();
    if(origin != null) origin.stop(0);
    if(foreign != null) foreign.stop(0);
  }
  private void origin(HttpExchange exchange) throws IOException {
    originCookieSeen |= exchange.getRequestHeaders().containsKey("Cookie");
    exchange.getResponseHeaders().add("Set-Cookie", "repository-session=origin-only; Path=/");
    if(!expectedAuth.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"origin\""); respond(exchange, 401); return;
    }
    authorized.incrementAndGet();
    String path = exchange.getRequestURI().getPath();
    if(path.equals("/same-final")) { respond(exchange, 200); return; }
    String location = path.equals("/same-start") ? "/same-final" : path.equals("/loop") ? "/loop"
        : "http://127.0.0.1:" + foreign.getAddress().getPort() + path;
    exchange.getResponseHeaders().add("Location", location); respond(exchange, 302);
  }
  private static void respond(HttpExchange exchange, int status) throws IOException {
    byte[] body = "proxy-proof".getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, body.length);
    try(var output = exchange.getResponseBody()) { output.write(body); }
    exchange.close();
  }
  private int get(String path, String bearer) throws Exception {
    HttpResponse response = requests.execute(context, client, new HttpGet(remote.resolve(path)), remote, bearer);
    try { EntityUtils.consume(response.getEntity()); return response.getStatusLine().getStatusCode(); }
    finally { org.apache.http.client.utils.HttpClientUtils.closeQuietly(response); }
  }
  @Test public void basicAuthAndCookiesRemainAtOriginIncludingForeignBasicChallenge() throws Exception {
    assertEquals(200, get("same-start", null)); assertTrue(originCookieSeen);
    assertEquals(200, get("good", null)); assertEquals(401, get("trap", null));
    assertEquals(2, foreignCount.get()); assertEquals(List.of(false, false), leakedAuth);
    assertEquals(List.of(false, false), leakedCookies); assertTrue(authorized.get() >= 3);
    verify(ssrf, atLeastOnce()).validateHost("127.0.0.1");
  }
  @Test public void bearerAndConditionalRangeHeadersAreScopedToOrigin() throws Exception {
    expectedAuth = "Bearer fixture-bearer";
    assertEquals(200, get("same-start", "fixture-bearer"));
    var request = new HttpGet(remote.resolve("good")); request.setHeader("Range", "bytes=10-"); request.setHeader("If-Range", "\"revision\"");
    HttpResponse response = requests.execute(context, client, request, remote, "fixture-bearer");
    try { assertEquals(200, response.getStatusLine().getStatusCode()); EntityUtils.consume(response.getEntity()); }
    finally { org.apache.http.client.utils.HttpClientUtils.closeQuietly(response); }
    assertEquals(List.of(false), leakedAuth); assertEquals(List.of(false), leakedCookies);
    assertEquals("bytes=10-", forwardedRange); assertEquals("\"revision\"", forwardedIfRange);
  }
  @Test public void aRedirectLoopStopsAfterFiveHops() throws Exception {
    expectedAuth = "Bearer fixture-bearer";
    try { get("loop", "fixture-bearer"); fail("Redirect loop accepted"); } catch(IOException expected) { }
    assertEquals(6, authorized.get()); assertEquals(0, foreignCount.get());
  }
  @Test public void userInfoIsRejectedBeforeAnyHttpRequest() throws Exception {
    HttpClient mockClient = mock(HttpClient.class);
    try { requests.execute(context, mockClient, new HttpGet("http://user:secret@127.0.0.1/file"), remote, null); fail("User info accepted"); }
    catch(IOException expected) { }
    verifyNoInteractions(mockClient);
  }
  @Test public void httpsDowngradeIsRejectedBeforeFollowingLocation() throws Exception {
    HttpClient mockClient = mock(HttpClient.class);
    var redirected = new BasicHttpResponse(HttpVersion.HTTP_1_1, 302, "Found"); redirected.setHeader("Location", remote.resolve("good").toString());
    when(mockClient.execute(any(HttpUriRequest.class), any(HttpContext.class))).thenReturn(redirected);
    try { requests.execute(context, mockClient, new HttpGet("https://127.0.0.1/file"), remote, null); fail("HTTPS downgrade accepted"); }
    catch(IOException expected) { }
    verify(mockClient, times(1)).execute(any(HttpUriRequest.class), any(HttpContext.class));
  }
  @Test public void firstForeignRequestSupportsProxy407WithoutTargetAuthEvenWithPreemptiveInterceptor() throws Exception {
    try (var fixture = new ForwardProxyFixture()) {
      fixture.configure(clients);
      client.close(); client = fixture.client();
      HttpResponse response = requests.execute(context, client, new HttpGet("http://foreign.invalid/trap"), remote, "repository-secret");
      try { assertEquals(401, response.getStatusLine().getStatusCode()); EntityUtils.consume(response.getEntity()); }
      finally { org.apache.http.client.utils.HttpClientUtils.closeQuietly(response); }
      assertTrue(fixture.proxyAuthenticated.get() > 0); assertFalse(fixture.targetSecretSeen);
      assertEquals(2, fixture.attempts.get());
    }
  }
  @Test public void proxyCredentialsNeverBecomeTargetCredentialsWhenTargetEqualsProxyAddress() throws Exception {
    try (var fixture = new ForwardProxyFixture()) {
      fixture.configure(clients); client.close(); client = fixture.client();
      URI target = URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort() + "/trap");
      HttpResponse response = requests.execute(context, client, new HttpGet(target), remote, null);
      try { assertEquals(401, response.getStatusLine().getStatusCode()); EntityUtils.consume(response.getEntity()); }
      finally { org.apache.http.client.utils.HttpClientUtils.closeQuietly(response); }
      assertTrue(fixture.proxyAuthenticated.get() > 0); assertFalse(fixture.targetSecretSeen);
    }
  }
  private static final class ForwardProxyFixture implements AutoCloseable {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    final AtomicInteger attempts = new AtomicInteger(), proxyAuthenticated = new AtomicInteger();
    volatile boolean targetSecretSeen;
    ForwardProxyFixture() throws IOException {
      server.createContext("/", exchange -> {
        attempts.incrementAndGet(); targetSecretSeen |= exchange.getRequestHeaders().containsKey("Authorization");
        if (!"Basic cHJveHk6cHJveHktcGFzcw==".equals(exchange.getRequestHeaders().getFirst("Proxy-Authorization"))) {
          exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"transport\""); respond(exchange, 407);
        } else {
          proxyAuthenticated.incrementAndGet(); exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"target\""); respond(exchange, 401);
        }
      }); server.start();
    }
    void configure(HttpClientManager clients) {
      var secret = mock(Secret.class); when(secret.decrypt()).thenReturn("proxy-pass".toCharArray());
      var authentication = new UsernameAuthenticationConfiguration(); authentication.setUsername("proxy"); authentication.setPassword(secret);
      var proxy = new ProxyServerConfiguration(); proxy.setEnabled(true); proxy.setHost("127.0.0.1"); proxy.setPort(server.getAddress().getPort()); proxy.setAuthentication(authentication);
      var proxies = new ProxyConfiguration(); proxies.setHttp(proxy);
      var configuration = mock(HttpClientConfiguration.class); when(configuration.getProxy()).thenReturn(proxies); when(clients.getConfiguration()).thenReturn(configuration);
    }
    CloseableHttpClient client() {
      var credentials = new BasicCredentialsProvider(); credentials.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("repository", "repository-secret"));
      return HttpClients.custom().setDefaultCredentialsProvider(credentials)
          .setProxy(new HttpHost("127.0.0.1", server.getAddress().getPort()))
          .addInterceptorFirst(new PreemptiveAuthHttpRequestInterceptor()).build();
    }
    public void close() { server.stop(0); }
  }
}

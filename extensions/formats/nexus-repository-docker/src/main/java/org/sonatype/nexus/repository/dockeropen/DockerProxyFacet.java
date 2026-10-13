/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.HttpClientUtils;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.validation.ssrf.AntiSsrfService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.cache.*;
import org.sonatype.nexus.repository.cache.CacheControllerHolder.CacheType;
import org.sonatype.nexus.repository.view.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
@Component @Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class DockerProxyFacet extends ContentProxyFacetSupport {
  private final ObjectMapper json; private final AntiSsrfService ssrf;
  @org.springframework.beans.factory.annotation.Autowired private org.sonatype.nexus.extensions.http.ScopedProxyRequests scopedRequests;
  private final ConcurrentHashMap<String, Token> tokens = new ConcurrentHashMap<>();
  private record Token(String value, long until) {}
  public DockerProxyFacet(ObjectMapper json, AntiSsrfService ssrf) { this.json = json; this.ssrf = ssrf; }
  private DockerContentFacet content() { return getRepository().facet(DockerContentFacet.class); }
  private String cachePath(Context context) {
    String path = context.getRequest().getPath();
    if ((path.equals("/v2/_catalog") || path.endsWith("/tags/list"))
        && (context.getRequest().getParameters().get("n") != null || context.getRequest().getParameters().get("last") != null)) {
      try {
        return path + "/.page-" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(getUrl(context).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    return path;
  }
  protected Content getCachedContent(Context context) { return content().read(cachePath(context)); }
  protected CacheController getCacheController(Context context) {
    return cacheControllerHolder.require(context.getRequest().getPath().contains("/blobs/") ? CacheControllerHolder.CONTENT : CacheControllerHolder.METADATA);
  }
  private boolean dockerHub() {
    String host = getConfiguration().getRemoteURL().getHost();
    return host != null && Set.of("registry-1.docker.io", "registry.hub.docker.com", "registry.hub.docker.io").contains(host.toLowerCase(Locale.ROOT));
  }
  private String remotePath(Context context) {
    String path = context.getRequest().getPath();
    // Docker Hub's official unqualified images live in the library namespace.
    if (dockerHub() && path.matches("/v2/[^/]+/(manifests|blobs|tags|referrers)/.*")) { path = "/v2/library/" + path.substring(4); }
    return path;
  }
  protected String getUrl(Context context) {
    String path = remotePath(context).substring(1);
    try {
      URIBuilder uri = new URIBuilder(path);
      // Only the Registry API's supported pagination/filter parameters are forwarded.
      for (String key : List.of("n", "last", "artifactType")) {
        String value = context.getRequest().getParameters().get(key);
        if (value != null) { uri.addParameter(key, value); }
      }
      return uri.toString();
    } catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException(invalid); }
  }
  protected Content store(Context context, Content payload) throws IOException {
    String path = cachePath(context);
    var parsed = Pattern.compile("^/v2/(.+)/(manifests|blobs)/([^/]+)$").matcher(path);
    boolean artifact = parsed.matches() && parsed.group(2).equals("manifests");
    Content stored = content().put(path, payload, "proxied", artifact ? parsed.group(1) : null, artifact ? parsed.group(3) : null);
    return stored;
  }
  @Override protected HttpRequestBase buildFetchHttpRequest(URI uri, Context context) {
    HttpRequestBase request = super.buildFetchHttpRequest(uri, context);
    request.setHeader("Accept", "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json");
    String bearer = facet(HttpClientFacet.class).getBearerToken();
    if (origin(uri).equals(origin(getConfiguration().getRemoteURL())) && bearer != null && !bearer.isBlank()) { request.setHeader("Authorization", "Bearer " + bearer); }
    return request;
  }
  @Override protected HttpResponse execute(Context context, HttpClient client, HttpRequestBase request) throws IOException {
    HttpResponse response = scopedExecute(context, client, request);
    Header challenge = response.getFirstHeader("WWW-Authenticate");
    if (response.getStatusLine().getStatusCode() != 401 || challenge == null || !challenge.getValue().regionMatches(true, 0, "Bearer ", 0, 7)) { return response; }
    Map<String,String> values = new HashMap<>();
    var parser = Pattern.compile("([a-zA-Z_]+)=\"([^\"\\r\\n]*)\"").matcher(challenge.getValue().substring(7));
    while (parser.find()) { if (values.putIfAbsent(parser.group(1).toLowerCase(Locale.ROOT), parser.group(2)) != null) { return response; } }
    URI realm;
    try { realm = URI.create(values.getOrDefault("realm", "")); } catch (IllegalArgumentException invalid) { return response; }
    URI registry = getConfiguration().getRemoteURL();
    String configured = getRepository().getConfiguration().attributes("dockerProxy").get("tokenRealm", String.class);
    boolean same = origin(realm).equals(origin(registry));
    boolean dockerHub = dockerHub()
        && realm.toString().equals("https://auth.docker.io/token");
    boolean pinned = configured != null && configured.equals(realm.toString());
    if (!(same || dockerHub || pinned) || realm.getHost() == null || realm.getScheme() == null || realm.getUserInfo() != null || realm.getQuery() != null || realm.getFragment() != null
        || !(realm.getScheme().equalsIgnoreCase("https") || same && realm.getScheme().equalsIgnoreCase("http"))) { return response; }
    ssrf.validateHost(realm.getHost());
    String path = remotePath(context);
    var resource = Pattern.compile("^/v2/(.+)/(manifests|blobs|tags|referrers)/.*$").matcher(path);
    String scope;
    if (path.equals("/v2/_catalog")) { scope = "registry:catalog:*"; }
    else if (resource.matches()) { scope = "repository:" + resource.group(1) + ":pull"; }
    else { return response; }
    String service = values.getOrDefault("service", "");
    if (service.length() > 500 || service.chars().anyMatch(Character::isISOControl)) { return response; }
    HttpClientUtils.closeQuietly(response);
    String key = realm + "|" + service + "|" + scope; Token cached = tokens.get(key);
    String token = cached != null && cached.until > System.nanoTime() ? cached.value : null;
    if (token == null) {
      HttpGet auth;
      try { auth = new HttpGet(new URIBuilder(realm).addParameter("service", service).addParameter("scope", scope).build()); }
      catch (java.net.URISyntaxException invalid) { throw new IOException("Invalid Docker token endpoint", invalid); }
      auth.setConfig(RequestConfig.custom().setRedirectsEnabled(false).setConnectTimeout(5000).setSocketTimeout(5000).setConnectionRequestTimeout(5000).build());
      Header basic = facet(HttpClientFacet.class).createBasicAuthHeader(); if (basic != null) { auth.setHeader(basic); }
      HttpResponse issued = client.execute(auth);
      try {
        if (issued.getStatusLine().getStatusCode() != 200 || issued.getEntity() == null) { throw new IOException("Docker token endpoint rejected authentication"); }
        byte[] bytes; try (InputStream stream = issued.getEntity().getContent()) { bytes = stream.readNBytes(1024*1024 + 1); }
        if (bytes.length > 1024*1024) { throw new IOException("Docker token response exceeds maximum size"); }
        var body = json.readTree(bytes); token = body.path("token").asText(body.path("access_token").asText(""));
        if (token.isBlank() || token.length() > 65536 || token.chars().anyMatch(Character::isISOControl)) { throw new IOException("Invalid Docker registry token"); }
        long seconds = Math.max(0, Math.min(300, body.path("expires_in").asLong(60)) - 5);
        if (tokens.size() >= 1000) { tokens.clear(); }
        tokens.put(key, new Token(token, System.nanoTime() + seconds*1_000_000_000L));
      } finally { HttpClientUtils.closeQuietly(issued); }
    }
    request.setHeader("Authorization", "Bearer " + token);
    HttpResponse authenticated = scopedExecute(context, client, request);
    if (authenticated.getStatusLine().getStatusCode() == 401) { tokens.remove(key); }
    return authenticated;
  }
  private HttpResponse scopedExecute(Context context, HttpClient client, HttpRequestBase initial) throws IOException {
    // Preserve registry-issued Authorization; configured origin Bearer is set by buildFetchHttpRequest.
    return scopedRequests.execute(context, client, initial, getConfiguration().getRemoteURL(), null);
  }
  private static String origin(URI uri) {
    if (uri.getHost() == null || uri.getScheme() == null) { return ""; }
    return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" +
        (uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80);
  }
}

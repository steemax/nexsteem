/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.io.IOException;
import java.net.URI;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.sonatype.nexus.extensions.http.ScopedProxyRequests;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component @Scope("prototype")
public class RubyYumProxyFacet extends ContentProxyFacetSupport {
  private final ScopedProxyRequests requests;

  public RubyYumProxyFacet(ScopedProxyRequests requests) { this.requests = requests; }

  private RubyYumContentFacet store() { return getRepository().facet(RubyYumContentFacet.class); }
  @Override protected Content getCachedContent(Context context) { return store().get(key(context)).orElse(null); }
  @Override protected Content store(Context context, Content content) {
    String path = ProtocolSupport.path(context);
    boolean packageFile = path.endsWith(".rpm") || path.endsWith(".gem");
    Content result = store().put(key(context), content, packageFile ? "PACKAGE" : "METADATA", packageFile ? path.substring(path.lastIndexOf('/')+1) : null, "");
    store().assets().path(key(context)).find().ifPresent(asset -> asset.markAsCached(content));
    return store().get(key(context)).orElse(result);
  }
  @Override protected String getUrl(Context context) { return ProtocolSupport.path(context).substring(1); }
  @Override protected HttpRequestBase buildFetchHttpRequest(URI uri, Context context, Content stale) {
    try {
      URIBuilder builder = new URIBuilder(uri);
      var parameters = context.getRequest().getParameters();
      for (String name : parameters.names()) for (String value : parameters.getAll(name)) builder.addParameter(name, value);
      return super.buildFetchHttpRequest(builder.build(), context, stale);
    } catch (Exception error) { throw new IllegalArgumentException("Invalid proxy query", error); }
  }

  @Override protected HttpResponse execute(Context context, HttpClient client, HttpRequestBase request) throws IOException {
    return requests.execute(context, client, request, getRemoteUrl(), facet(HttpClientFacet.class).getBearerToken());
  }
  private String key(Context context) {
    String path = ProtocolSupport.path(context);
    var query = new java.util.TreeMap<String, java.util.List<String>>();
    for (String name : context.getRequest().getParameters().names()) query.put(name, context.getRequest().getParameters().getAll(name));
    return query.isEmpty() ? path : path + "?" + ProtocolSupport.sha256(query.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}

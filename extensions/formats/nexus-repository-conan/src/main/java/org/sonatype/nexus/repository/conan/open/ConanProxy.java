/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.sonatype.nexus.repository.content.facet.ContentProxyFacetSupport;
import org.sonatype.nexus.repository.view.*;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class ConanProxy extends ContentProxyFacetSupport {
 private static String path(Context c){return c.getRequest().getPath();}
 private static String query(Context c){return c.getRequest().getParameters().entries().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
 private static String key(Context c){String q=query(c);return path(c)+(q.isEmpty()?"":"?"+q);}
 @Override protected Content getCachedContent(Context c){return facet(ConanContent.class).get(key(c));}
 @Override protected String getUrl(Context c){String q=query(c);return path(c).substring(1)+(q.isEmpty()?"":"?"+q);}
 @Override protected Content store(Context c,Content content)throws IOException{String name=null,version=null;if(ConanProtocol.files(path(c))){String[] parts=path(c).split("/");name=parts[3]+"@"+parts[5]+"/"+parts[6];version=parts[4];}return facet(ConanContent.class).put(key(c),content,name,version);}
 @Override protected org.sonatype.nexus.repository.cache.CacheController getCacheController(Context c){return path(c).matches(".*/files/.+")?cacheControllerHolder.getContentCacheController():cacheControllerHolder.getMetadataCacheController();}

  @org.springframework.beans.factory.annotation.Autowired
  private org.sonatype.nexus.extensions.http.ScopedProxyRequests scopedRequests;

  @Override
  protected org.apache.http.HttpResponse execute(
      org.sonatype.nexus.repository.view.Context context,
      org.apache.http.client.HttpClient client,
      org.apache.http.client.methods.HttpRequestBase request) throws java.io.IOException
  {
    return scopedRequests.execute(context, client, request, getConfiguration().getRemoteURL(),
        facet(org.sonatype.nexus.repository.httpclient.HttpClientFacet.class).getBearerToken());
  }
}

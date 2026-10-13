/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.cache.*;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.handlers.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
public abstract class DockerRecipeSupport extends RecipeSupport {
  @Autowired protected Provider<DockerContentFacet> content;
  @Autowired protected Provider<DockerSecurityFacet> security;
  @Autowired protected Provider<DockerConnectorFacet> connectors;
  @Autowired protected Provider<ConfigurableViewFacet> views;
  @Autowired protected Provider<HttpClientFacet> http;
  @Autowired protected Provider<DockerProxyFacet> proxies;
  @Autowired protected Provider<NegativeCacheFacet> negatives;
  @Autowired @Qualifier("default") protected Provider<GroupFacet> groups;
  @Autowired protected Provider<BrowseFacet> browse;
  @Autowired protected Provider<SearchFacet> search;
  @Autowired protected Provider<LastAssetMaintenanceFacet> maintenance;
  @Autowired protected DockerHandler handler;
  @Autowired protected SecurityHandler permissions;
  @Autowired protected ExceptionHandler exceptions;
  @Autowired protected ContentHeadersHandler headers;
  @Autowired protected PartialFetchHandler partial;
  protected DockerRecipeSupport(Type type, Format format) { super(type, format); }
  @Override public void apply(Repository repository) throws Exception {
    repository.attach(security.get()); repository.attach(content.get());
    repository.attach(maintenance.get()); repository.attach(search.get()); repository.attach(browse.get());
    if (repository.getType().getValue().equals("proxy")) {
      repository.attach(http.get()); repository.attach(negatives.get()); repository.attach(proxies.get());
    } else if (repository.getType().getValue().equals("group")) { repository.attach(groups.get()); }
    ConfigurableViewFacet view = views.get(); Router.Builder routes = new Router.Builder();
    routes.route(new Route.Builder().matcher(context -> context.getRequest().getPath().startsWith("/v2/"))
        .handler(permissions).handler(exceptions).handler(partial).handler(headers).handler(handler).create());
    routes.defaultHandlers(org.sonatype.nexus.repository.http.HttpHandlers.notFound());
    view.configure(routes.create()); repository.attach(view); repository.attach(connectors.get());
  }
}

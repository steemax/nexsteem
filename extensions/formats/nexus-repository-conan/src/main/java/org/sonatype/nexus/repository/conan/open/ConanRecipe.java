/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.conan.internal.*;
import org.sonatype.nexus.repository.conan.internal.security.ConanSecurityFacet;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.handlers.*;
import org.sonatype.nexus.repository.http.*;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.cache.*;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.springframework.beans.factory.annotation.*;
public abstract class ConanRecipe extends RecipeSupport {
 @Autowired private Provider<ConanSecurityFacet> security;@Autowired private Provider<ConanContent> content;@Autowired private Provider<ConanProxy> proxy;
 @Autowired private Provider<ConfigurableViewFacet> views;@Autowired private Provider<HttpClientFacet> http;@Autowired private Provider<NegativeCacheFacet> negative;
 @Autowired @Qualifier("default") private Provider<GroupFacet> groups;
 @Autowired private Provider<BrowseFacet> browse;@Autowired private Provider<SearchFacet> search;@Autowired private Provider<LastAssetMaintenanceFacet> maintenance;
 @Autowired private SecurityHandler permissions;@Autowired private ExceptionHandler errors;@Autowired private ContentHeadersHandler headers;@Autowired private PartialFetchHandler partial;@Autowired private ConditionalRequestHandler conditional;@Autowired private ConanProtocol protocol;
 protected ConanRecipe(Type type,Format format){super(type,format);}
 @Override public void apply(Repository repository)throws Exception {
  var view=views.get();var router=new Router.Builder();
  router.route(new Route.Builder().matcher(c->true).handler(permissions).handler(errors).handler(conditional).handler(partial).handler(headers).handler(protocol).create());
  addBrowseUnsupportedRoute(router);router.defaultHandlers(HttpHandlers.notFound());view.configure(router.create());
  repository.attach(security.get());repository.attach(view);repository.attach(content.get());
  if("proxy".equals(repository.getType().getValue())){repository.attach(http.get());repository.attach(negative.get());repository.attach(proxy.get());}
  if("group".equals(repository.getType().getValue()))repository.attach(groups.get());
  repository.attach(browse.get());repository.attach(search.get());repository.attach(maintenance.get());
 }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.*;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.cache.*;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.handlers.*;
import org.springframework.beans.factory.annotation.*;
public abstract class RubyYumRecipe extends RecipeSupport {
 private final Provider<RubyYumContentFacet> content;
 private final Provider<RubyYumSecurityFacet> security;
 @Autowired private Provider<ConfigurableViewFacet> views;
 @Autowired private Provider<HttpClientFacet> http;
 @Autowired private Provider<RubyYumProxyFacet> proxy;
 @Autowired private Provider<NegativeCacheFacet> negative;
 @Autowired @Qualifier("default") private Provider<GroupFacet> groups;
 @Autowired private Provider<BrowseFacet> browse;
 @Autowired private Provider<SearchFacet> search;
 @Autowired private Provider<LastAssetMaintenanceFacet> maintenance;
 @Autowired private SecurityHandler permissions;
 @Autowired private ExceptionHandler errors;
 @Autowired private TimingHandler timing;
 @Autowired private ContentHeadersHandler headers;
 @Autowired private PartialFetchHandler partial;
 @Autowired private ConditionalRequestHandler conditional;
 @Autowired private RubyYumProtocolHandler protocol;
 @Autowired private RoutingRuleHandler routing;
 @Autowired private NegativeCacheHandler negativeHandler;
 public RubyYumRecipe(Type type,Format format,Provider<RubyYumContentFacet> content,Provider<RubyYumSecurityFacet> security) { super(type,format); this.content=content;this.security=security; }
 @Override public void apply(Repository repository) throws Exception {
  var view=views.get(); var router=new Router.Builder(); addBrowseUnsupportedRoute(router);
  router.route(new Route.Builder().matcher(context->true).handler(timing).handler(permissions).handler(errors).handler(routing)
   .handler(context -> "proxy".equals(repository.getType().getValue())?negativeHandler.handle(context):context.proceed()).handler(conditional).handler(partial).handler(headers).handler(protocol).create());
  router.defaultHandlers(HttpHandlers.notFound()); view.configure(router.create());
  repository.attach(security.get()); repository.attach(view); repository.attach(content.get());
  if("proxy".equals(repository.getType().getValue())) { repository.attach(http.get());repository.attach(negative.get());repository.attach(proxy.get()); }
  if("group".equals(repository.getType().getValue())) repository.attach(groups.get());
  repository.attach(browse.get()); repository.attach(search.get()); repository.attach(maintenance.get());
 }
}

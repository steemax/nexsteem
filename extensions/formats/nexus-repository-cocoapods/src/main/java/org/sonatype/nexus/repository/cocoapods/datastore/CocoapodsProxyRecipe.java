/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.cocoapods.datastore;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.cocoapods.internal.*;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.handlers.*;
import org.sonatype.nexus.repository.http.*;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.cache.*;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.springframework.beans.factory.annotation.*;
@org.springframework.stereotype.Component @Qualifier("cocoapods-proxy")
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class CocoapodsProxyRecipe extends RecipeSupport {
 @Autowired private Provider<CocoapodsSecurityFacet> security;@Autowired private Provider<CocoapodsContent> content;@Autowired private Provider<CocoapodsProxy> proxy;
 @Autowired private Provider<ConfigurableViewFacet> views;@Autowired private Provider<HttpClientFacet> http;@Autowired private Provider<NegativeCacheFacet> negative;
 @Autowired private Provider<BrowseFacet> browse;@Autowired private Provider<SearchFacet> search;@Autowired private Provider<LastAssetMaintenanceFacet> maintenance;
 @Autowired private SecurityHandler permissions;@Autowired private ExceptionHandler errors;@Autowired private ContentHeadersHandler headers;@Autowired private PartialFetchHandler partial;@Autowired private ConditionalRequestHandler conditional;@Autowired private NegativeCacheHandler notFound;@Autowired private CocoapodsDownloads downloads;@Autowired private RoutingRuleHandler routing;
 @Autowired public CocoapodsProxyRecipe(@Qualifier(ProxyType.NAME)Type type,@Qualifier(CocoapodsFormat.NAME)Format format){super(type,format);}
 @Override public void apply(Repository repository)throws Exception {
  var view=views.get();var router=new Router.Builder();
  router.route(new Route.Builder().matcher(c->SetPath.valid(c)).handler(permissions).handler(errors).handler(routing).handler(notFound).handler(conditional).handler(partial).handler(headers).handler(downloads).create());
  addBrowseUnsupportedRoute(router);router.defaultHandlers(HttpHandlers.notFound());view.configure(router.create());
  repository.attach(security.get());repository.attach(view);repository.attach(content.get());repository.attach(http.get());repository.attach(negative.get());repository.attach(proxy.get());repository.attach(browse.get());repository.attach(search.get());repository.attach(maintenance.get());
 }
 private static class SetPath {static boolean valid(Context c){String p=c.getRequest().getPath();if(!java.util.Set.of("GET","HEAD").contains(c.getRequest().getAction())||p.startsWith("/_source-map/")||p.contains("..")||p.contains("\\"))return false;c.getAttributes().set(AssetKind.class,p.startsWith("/_source/")?AssetKind.POD:p.endsWith(".podspec.json")?AssetKind.SPEC:AssetKind.CDN_METADATA);return true;}}
}

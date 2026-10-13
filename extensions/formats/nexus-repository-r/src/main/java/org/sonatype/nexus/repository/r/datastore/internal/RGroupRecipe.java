/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.r.datastore.internal;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.r.RFormat;
import org.sonatype.nexus.repository.r.internal.AssetKind;
import org.sonatype.nexus.repository.r.internal.group.PackagesGroupHandler;
import org.sonatype.nexus.repository.group.*;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.springframework.beans.factory.annotation.*;
@org.springframework.stereotype.Component
@Qualifier("r-group")
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class RGroupRecipe extends RRecipeSupport {
 @Autowired @Qualifier("default")private Provider<GroupFacet> groups;
 @Autowired private GroupHandler downloads;
 @Autowired private PackagesGroupHandler packages;
 @Autowired public RGroupRecipe(@Qualifier(GroupType.NAME)Type type,@Qualifier(RFormat.NAME)Format format){super(type,format);}
 @Override public void apply(Repository repository)throws Exception {
  var view=viewFacet.get();var router=new Router.Builder();
  router.route(packagesMatcher().handler(timingHandler).handler(kindHandler(AssetKind.PACKAGES)).handler(securityHandler).handler(exceptionHandler).handler(contentHeadersHandler).handler(packages).create());
  router.route(archiveMatcher().handler(timingHandler).handler(kindHandler(AssetKind.ARCHIVE)).handler(securityHandler).handler(exceptionHandler).handler(conditionalRequestHandler).handler(partialFetchHandler).handler(contentHeadersHandler).handler(downloads).create());
  addBrowseUnsupportedRoute(router);router.defaultHandlers(HttpHandlers.notFound());view.configure(router.create());
  repository.attach(securityFacet.get());repository.attach(view);repository.attach(contentFacet.get());repository.attach(groups.get());repository.attach(browseFacet.get());repository.attach(searchFacet.get());repository.attach(maintenanceFacet.get());
 }
}

/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.conda.datastore.internal.proxy;
import javax.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.conda.CondaFormat;
import org.sonatype.nexus.repository.conda.datastore.internal.CondaContentFacet;
import org.sonatype.nexus.repository.conda.datastore.internal.CondaRecipeSupport;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Route;
import org.sonatype.nexus.repository.view.Router.Builder;
import org.sonatype.nexus.repository.view.ViewFacet;
@org.springframework.stereotype.Component
@Qualifier(CondaProxyRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class CondaProxyRecipe extends CondaRecipeSupport {
 public static final String NAME="conda-proxy";
@Autowired private Provider<CondaContentFacet> contentFacet;
@Autowired private Provider<CondaProxyFacet> proxyFacet;
@Autowired private ProxyHandler proxyHandler;
 @Autowired public CondaProxyRecipe(@Qualifier(ProxyType.NAME)Type type,@Qualifier(CondaFormat.NAME)Format format){super(type,format);}
 @Override public void apply(Repository repository)throws Exception {
repository.attach(contentFacet.get());
repository.attach(browseFacet.get());
repository.attach(proxyFacet.get());
repository.attach(securityFacet.get());
repository.attach(configure(viewFacet.get()));
repository.attach(httpClientFacet.get());
repository.attach(negativeCacheFacet.get());
repository.attach(maintenanceFacet.get());
repository.attach(searchFacet.get());
repository.attach(purgeUnusedFacet.get());
 }
 private ViewFacet configure(ConfigurableViewFacet facet){Builder builder=new Builder();addBrowseUnsupportedRoute(builder);
 for(org.sonatype.nexus.repository.view.Matcher matcher: java.util.List.of(rootChannelIndexHtmlMatcher(),
     rootChannelDataJsonMatcher(),
     rootChannelRssXmlMatcher(),
     archIndexHtmlMatcher(),
     archRepodataJsonMatcher(),
     archRepodataJsonBz2Matcher(),
     archRepodata2JsonMatcher(),
     archTarPackageMatcher(),
     archCondaPackageMatcher())){builder.route(new Route.Builder().matcher(matcher)
              .handler(timingHandler)
              .handler(securityHandler)
              .handler(routingHandler)
              .handler(exceptionHandler)
              .handler(handlerContributor)
              .handler(negativeCacheHandler)
              .handler(conditionalRequestHandler)
              .handler(partialFetchHandler)
              .handler(contentHeadersHandler)
              .handler(lastDownloadedHandler)
              .handler(proxyHandler)
              .create());}
 builder.defaultHandlers(HttpHandlers.notFound());facet.configure(builder.create());return facet;
 }
}

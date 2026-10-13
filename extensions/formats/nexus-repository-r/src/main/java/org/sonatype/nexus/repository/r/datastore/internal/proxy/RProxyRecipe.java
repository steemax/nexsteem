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
package org.sonatype.nexus.repository.r.datastore.internal.proxy;
import javax.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.r.RFormat;
import org.sonatype.nexus.repository.r.datastore.internal.RRecipeSupport;
import org.sonatype.nexus.repository.r.internal.AssetKind;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Router.Builder;
import org.sonatype.nexus.repository.view.ViewFacet;
@org.springframework.stereotype.Component
@Qualifier(RProxyRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class RProxyRecipe extends RRecipeSupport {
 public static final String NAME="r-proxy";
@Autowired protected Provider<NegativeCacheFacet> negativeCacheFacet;
@Autowired protected Provider<PurgeUnusedFacet> purgeUnusedFacet;
@Autowired protected Provider<RProxyFacet> proxyFacet;
@Autowired protected NegativeCacheHandler negativeCacheHandler;
@Autowired protected ProxyHandler proxyHandler;
 @Autowired public RProxyRecipe(@Qualifier(ProxyType.NAME)Type type,@Qualifier(RFormat.NAME)Format format){super(type,format);}
 @Override public void apply(Repository repository)throws Exception {
repository.attach(contentFacet.get());
repository.attach(browseFacet.get());
repository.attach(proxyFacet.get());
repository.attach(securityFacet.get());
repository.attach(configure(viewFacet.get()));
repository.attach(httpClientFacet.get());
repository.attach(negativeCacheFacet.get());
repository.attach(searchFacet.get());
repository.attach(purgeUnusedFacet.get());
repository.attach(maintenanceFacet.get());
 }
 private ViewFacet configure(ConfigurableViewFacet facet){Builder builder=new Builder();
builder.route(packagesMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.PACKAGES))
        .handler(securityHandler)
        .handler(routingRuleHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(proxyHandler)
        .create());
builder.route(metadataRdsMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.RDS_METADATA))
        .handler(securityHandler)
        .handler(routingRuleHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(proxyHandler)
        .create());
builder.route(archiveMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.ARCHIVE))
        .handler(securityHandler)
        .handler(routingRuleHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(proxyHandler)
        .create());
 addBrowseUnsupportedRoute(builder);builder.defaultHandlers(HttpHandlers.notFound());facet.configure(builder.create());return facet;
 }
}

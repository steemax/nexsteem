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
package org.sonatype.nexus.repository.pypi.datastore.internal;
import javax.annotation.Nonnull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.pypi.datastore.PypiContentFacet;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.pypi.internal.AssetKind;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;
import org.sonatype.nexus.repository.pypi.internal.PyPiProxyHandler;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Router.Builder;
import org.sonatype.nexus.repository.view.ViewFacet;
@org.springframework.stereotype.Component
@Qualifier(PyPiProxyRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class PyPiProxyRecipe extends PyPiRecipeSupport {
 public static final String NAME="pypi-proxy";
 @Autowired private Provider<PyPiProxyFacet> proxyFacet;
@Autowired private Provider<PypiContentFacet> contentFacet;
@Autowired private Provider<HttpClientFacet> httpClientFacet;
@Autowired private Provider<NegativeCacheFacet> negativeCacheFacet;
@Autowired private NegativeCacheHandler negativeCacheHandler;
@Autowired private Provider<PurgeUnusedFacet> purgeUnusedFacet;
@Autowired private PyPiProxyHandler proxyHandler;
 @Autowired public PyPiProxyRecipe(@Qualifier(ProxyType.NAME) Type type, @Qualifier(PyPiFormat.NAME) Format format){super(type,format);}
 @Override public void apply(Repository repository) throws Exception {
repository.attach(contentFacet.get());
repository.attach(securityFacet.get());
repository.attach(configure(viewFacet.get()));
repository.attach(httpClientFacet.get());
repository.attach(negativeCacheFacet.get());
repository.attach(proxyFacet.get());
repository.attach(searchFacet.get());
repository.attach(purgeUnusedFacet.get());
repository.attach(browseFacet.get());
repository.attach(lastAssetMaintenanceFacet.get());
repository.attach(indexFacet.get());
 }
 private ViewFacet configure(ConfigurableViewFacet facet){
 org.sonatype.nexus.repository.view.Router.Builder builder=new org.sonatype.nexus.repository.view.Router.Builder();
builder.route(indexMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.INDEX))
        .handler(securityHandler)
        .handler(routingHandler)
        .handler(exceptionHandler)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(proxyHandler)
        .create());
builder.route(rootIndexMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.ROOT_INDEX))
        .handler(securityHandler)
        .handler(routingHandler)
        .handler(exceptionHandler)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(proxyHandler)
        .create());
builder.route(packagesMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.PACKAGE))
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
        .create());
builder.route(searchMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.SEARCH))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(proxyHandler)
        .create());
 addBrowseUnsupportedRoute(builder); builder.defaultHandlers(HttpHandlers.notFound()); facet.configure(builder.create());return facet;
 }
}

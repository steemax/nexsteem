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
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.pypi.internal.AssetKind;
import org.sonatype.nexus.repository.pypi.internal.PyPiFormat;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Router;
import org.sonatype.nexus.repository.view.ViewFacet;
@org.springframework.stereotype.Component
@Qualifier(PyPiHostedRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class PyPiHostedRecipe extends PyPiRecipeSupport {
 public static final String NAME="pypi-hosted";
 @Autowired private Provider<PyPiHostedFacet> hostedFacet;
@Autowired private PyPiHostedHandlers hostedHandlers;
 @Autowired public PyPiHostedRecipe(@Qualifier(HostedType.NAME) Type type, @Qualifier(PyPiFormat.NAME) Format format){super(type,format);}
 @Override public void apply(Repository repository) throws Exception {
repository.attach(contentFacet.get());
repository.attach(securityFacet.get());
repository.attach(configure(viewFacet.get()));
repository.attach(hostedFacet.get());
repository.attach(searchFacet.get());
repository.attach(browseFacet.get());
repository.attach(indexFacet.get());
repository.attach(lastAssetMaintenanceFacet.get());
 }
 private ViewFacet configure(ConfigurableViewFacet facet){
 org.sonatype.nexus.repository.view.Router.Builder builder=new org.sonatype.nexus.repository.view.Router.Builder();
builder.route(rootIndexMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.ROOT_INDEX))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(hostedHandlers.getRootIndex)
        .create());
builder.route(indexMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.INDEX))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(hostedHandlers.getIndex)
        .create());
builder.route(searchMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.SEARCH))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(conditionalRequestHandler)
        .handler(contentHeadersHandler)
        .handler(hostedHandlers.search())
        .create());
builder.route(packagesMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.PACKAGE))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(lastDownloadedHandler)
        .handler(hostedHandlers.getPackage)
        .create());
builder.route(baseMatcher()
        .handler(timingHandler)
        .handler(kindHandler(AssetKind.PACKAGE))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(conditionalRequestHandler)
        .handler(contentHeadersHandler)
        .handler(hostedHandlers.postContent)
        .create());
 addBrowseUnsupportedRoute(builder); builder.defaultHandlers(HttpHandlers.notFound()); facet.configure(builder.create());return facet;
 }
}

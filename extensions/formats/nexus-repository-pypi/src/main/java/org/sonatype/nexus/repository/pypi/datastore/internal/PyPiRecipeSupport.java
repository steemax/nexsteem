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
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.pypi.datastore.PypiContentFacet;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.pypi.internal.AssetKind;
import org.sonatype.nexus.repository.pypi.internal.PyPiIndexFacet;
import org.sonatype.nexus.repository.pypi.internal.PyPiSecurityFacet;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Route.Builder;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;
import org.sonatype.nexus.repository.view.matchers.ActionMatcher;
import org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
import static org.sonatype.nexus.repository.http.HttpMethods.POST;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.ViewFacet;
public abstract class PyPiRecipeSupport extends RecipeSupport {
@Autowired protected Provider<BrowseFacet> browseFacet;
@Autowired protected Provider<PypiContentFacet> contentFacet;
@Autowired protected Provider<PyPiSecurityFacet> securityFacet;
@Autowired protected Provider<ConfigurableViewFacet> viewFacet;
@Autowired protected Provider<SearchFacet> searchFacet;
@Autowired protected Provider<PyPiLastAssetMaintenanceFacet> lastAssetMaintenanceFacet;
@Autowired protected Provider<PyPiIndexFacet> indexFacet;
@Autowired protected ExceptionHandler exceptionHandler;
@Autowired protected TimingHandler timingHandler;
@Autowired protected RoutingRuleHandler routingHandler;
@Autowired protected SecurityHandler securityHandler;
@Autowired protected PartialFetchHandler partialFetchHandler;
@Autowired protected ConditionalRequestHandler conditionalRequestHandler;
@Autowired protected ContentHeadersHandler contentHeadersHandler;
@Autowired protected HandlerContributor handlerContributor;
@Autowired protected LastDownloadedHandler lastDownloadedHandler;
 protected PyPiRecipeSupport(Type type, Format format){super(type,format);}
 protected static Handler kindHandler(AssetKind kind) {return context->{context.getAttributes().set(AssetKind.class,kind);return context.proceed();};}
 static Builder indexMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(GET,HEAD), LogicMatchers.or(new TokenMatcher("/simple/{name:[0-9a-zA-Z._-]+}"),new TokenMatcher("/simple/{name:[0-9a-zA-Z._-]+}/"))));}
 static Builder rootIndexMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(GET,HEAD), new TokenMatcher("/simple/")));}
 static Builder searchMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(POST), new TokenMatcher("/pypi")));}
 static Builder packagesMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(GET,HEAD), new TokenMatcher("{path:/packages/[^/].+/[^/]+/[^/]+}")));}
 static Builder baseMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(POST), new TokenMatcher("/")));}
}

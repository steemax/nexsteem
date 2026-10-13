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
package org.sonatype.nexus.repository.r.datastore.internal;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.r.internal.AssetKind;
import org.sonatype.nexus.repository.r.internal.security.RSecurityFacet;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Matcher;
import org.sonatype.nexus.repository.view.Route.Builder;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;
import org.sonatype.nexus.repository.view.matchers.ActionMatcher;
import org.sonatype.nexus.repository.view.matchers.SuffixMatcher;
import org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.view.Handler;
public abstract class RRecipeSupport extends RecipeSupport {
@Autowired protected Provider<BrowseFacet> browseFacet;
@Autowired protected Provider<RSecurityFacet> securityFacet;
@Autowired protected Provider<ConfigurableViewFacet> viewFacet;
@Autowired protected Provider<RContentFacet> contentFacet;
@Autowired protected Provider<SearchFacet> searchFacet;
@Autowired protected Provider<LastAssetMaintenanceFacet> maintenanceFacet;
@Autowired protected ExceptionHandler exceptionHandler;
@Autowired protected TimingHandler timingHandler;
@Autowired protected SecurityHandler securityHandler;
@Autowired protected RoutingRuleHandler routingRuleHandler;
@Autowired protected PartialFetchHandler partialFetchHandler;
@Autowired protected ConditionalRequestHandler conditionalRequestHandler;
@Autowired protected ContentHeadersHandler contentHeadersHandler;
@Autowired protected HandlerContributor handlerContributor;
@Autowired protected Provider<HttpClientFacet> httpClientFacet;
 protected RRecipeSupport(Type type,Format format){super(type,format);}
 protected static Handler kindHandler(AssetKind kind){return c->{c.getAttributes().set(AssetKind.class,kind);return c.proceed();};}
 protected static TokenMatcher allFilesTokenMatcher(){return new TokenMatcher("/{pathAndFilename:.+}");}
 protected static TokenMatcher packagesTokenMatcher(){return new TokenMatcher("/{path:.+}/PACKAGES{extension:.*}");}
 protected static Matcher archivePathMatcher(){return LogicMatchers.and(allFilesTokenMatcher(),LogicMatchers.or(new SuffixMatcher(".zip"),new SuffixMatcher(".tgz"),new SuffixMatcher(".tar.gz")));}
 protected static Matcher metadataRdsPathMatcher(){return LogicMatchers.and(allFilesTokenMatcher(),new SuffixMatcher(".rds"));}
 protected static Builder archiveMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(HttpMethods.GET,HttpMethods.HEAD),archivePathMatcher()));}
 protected static Builder packagesMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(HttpMethods.GET,HttpMethods.HEAD),packagesTokenMatcher()));}
 protected static Builder metadataRdsMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(HttpMethods.GET,HttpMethods.HEAD),metadataRdsPathMatcher()));}
 protected static Builder uploadMatcher(){return new Builder().matcher(LogicMatchers.and(new ActionMatcher(HttpMethods.PUT),allFilesTokenMatcher()));}
}

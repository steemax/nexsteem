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
package org.sonatype.nexus.repository.helm.internal.content.recipe;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.handlers.*;
import org.sonatype.nexus.repository.view.matchers.*;
import org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.helm.internal.AssetKind;
import org.sonatype.nexus.repository.helm.internal.content.HelmContentFacet;
import org.sonatype.nexus.repository.helm.internal.security.HelmSecurityFacet;
import org.springframework.beans.factory.annotation.Autowired;
import static org.sonatype.nexus.repository.http.HttpMethods.*;

/** Native SQL recipes translated from the published EPL Helm Groovy recipes. */
public abstract class HelmOpenRecipeSupport extends RecipeSupport {
  @Autowired protected Provider<HelmSecurityFacet> security;
  @Autowired protected Provider<HelmContentFacet> content;
  @Autowired protected Provider<ConfigurableViewFacet> views;
  @Autowired protected Provider<HttpClientFacet> http;
  @Autowired protected Provider<LastAssetMaintenanceFacet> maintenance;
  @Autowired protected Provider<SearchFacet> search;
  @Autowired protected Provider<BrowseFacet> browse;
  @Autowired protected TimingHandler timing;
  @Autowired protected SecurityHandler permissions;
  @Autowired protected ExceptionHandler exceptions;
  @Autowired protected HandlerContributor contributors;
  @Autowired protected ConditionalRequestHandler conditional;
  @Autowired protected PartialFetchHandler partial;
  @Autowired protected ContentHeadersHandler headers;
  protected HelmOpenRecipeSupport(Type type, Format format) { super(type, format); }
  protected static Matcher index() { return kind(LogicMatchers.and(new ActionMatcher(GET, HEAD), new LiteralMatcher("/index.yaml")), AssetKind.HELM_INDEX); }
  protected static Matcher chartGet() { return kind(LogicMatchers.and(new ActionMatcher(GET, HEAD), new TokenMatcher("/{filename:.+}")), AssetKind.HELM_PACKAGE); }
  protected static Matcher chart(String extension, AssetKind kind, String... methods) {
    return kind(LogicMatchers.and(new ActionMatcher(methods), new TokenMatcher("/{filename:.+}.{extension:" + extension + "}")), kind);
  }
  private static Matcher kind(Matcher matcher, AssetKind kind) {
    return context -> { if (!matcher.matches(context)) { return false; } context.getAttributes().set(AssetKind.class, kind); return true; };
  }
  protected Route.Builder route(Matcher matcher) {
    return new Route.Builder().matcher(matcher).handler(timing).handler(permissions).handler(exceptions).handler(contributors);
  }
  protected void attachCommon(Repository repository, ConfigurableViewFacet view) throws Exception {
    repository.attach(security.get()); repository.attach(view); repository.attach(http.get());
    repository.attach(content.get()); repository.attach(maintenance.get()); repository.attach(search.get()); repository.attach(browse.get());
  }
}

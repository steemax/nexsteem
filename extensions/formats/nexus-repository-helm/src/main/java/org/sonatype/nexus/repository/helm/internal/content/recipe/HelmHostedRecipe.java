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
import org.sonatype.nexus.repository.helm.internal.HelmFormat;
import org.sonatype.nexus.repository.helm.internal.AssetKind;
import org.sonatype.nexus.repository.helm.internal.content.createindex.CreateIndexFacetImpl;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.matchers.*;
import org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import static org.sonatype.nexus.repository.http.HttpMethods.*;

@Component @Qualifier(HelmHostedRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from = "1.0")
public class HelmHostedRecipe extends HelmOpenRecipeSupport {
  public static final String NAME = "helm-hosted";
  @Autowired private HostedHandlers hosted;
  @Autowired private Provider<HelmHostedFacetImpl> hosting;
  @Autowired private Provider<CreateIndexFacetImpl> indexes;
  public HelmHostedRecipe(@Qualifier(HostedType.NAME) Type type, @Qualifier(HelmFormat.NAME) Format format) { super(type, format); }
  public void apply(Repository repository) throws Exception {
    ConfigurableViewFacet view = views.get(); Router.Builder router = new Router.Builder(); addBrowseUnsupportedRoute(router);
    for (Matcher matcher : new Matcher[]{index(), chartGet()}) {
      router.route(route(matcher).handler(partial).handler(headers).handler(hosted.get).create());
    }
    for (Matcher matcher : new Matcher[]{chart("tgz", AssetKind.HELM_PACKAGE, PUT), chart("tgz.prov", AssetKind.HELM_PROVENANCE, PUT)}) {
      router.route(route(matcher).handler(conditional).handler(partial).handler(headers).handler(hosted.upload).create());
    }
    router.route(route(LogicMatchers.and(new ActionMatcher(POST), new LiteralMatcher("/api/charts")))
        .handler(conditional).handler(partial).handler(headers).handler(hosted.push).create());
    router.route(route(chart("tgz", AssetKind.HELM_PACKAGE, DELETE)).handler(conditional).handler(partial).handler(headers).handler(hosted.delete).create());
    router.defaultHandlers(HttpHandlers.notFound()); view.configure(router.create()); attachCommon(repository, view);
    repository.attach(hosting.get()); repository.attach(indexes.get());
  }
}

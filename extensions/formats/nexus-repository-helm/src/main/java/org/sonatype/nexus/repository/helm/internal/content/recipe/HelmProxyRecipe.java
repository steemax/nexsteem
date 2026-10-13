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
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component @Qualifier(HelmProxyRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from = "1.0")
public class HelmProxyRecipe extends HelmOpenRecipeSupport {
  public static final String NAME = "helm-proxy";
  @Autowired private Provider<NegativeCacheFacet> negatives;
  @Autowired private Provider<HelmProxyFacet> proxy;
  @Autowired private NegativeCacheHandler negative;
  @Autowired private ProxyHandler forwarding;
  @Autowired private RoutingRuleHandler rules;
  public HelmProxyRecipe(@Qualifier(ProxyType.NAME) Type type, @Qualifier(HelmFormat.NAME) Format format) { super(type, format); }
  public void apply(Repository repository) throws Exception {
    ConfigurableViewFacet view = views.get(); Router.Builder router = new Router.Builder(); addBrowseUnsupportedRoute(router);
    for (Matcher matcher : new Matcher[]{index(), chartGet()}) {
      router.route(route(matcher).handler(rules).handler(negative).handler(conditional).handler(partial).handler(headers).handler(forwarding).create());
    }
    router.defaultHandlers(HttpHandlers.notFound()); view.configure(router.create()); attachCommon(repository, view);
    repository.attach(negatives.get()); repository.attach(proxy.get());
  }
}

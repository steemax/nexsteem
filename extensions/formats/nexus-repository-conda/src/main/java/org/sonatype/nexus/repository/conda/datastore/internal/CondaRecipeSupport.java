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
package org.sonatype.nexus.repository.conda.datastore.internal;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.inject.Provider;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.cache.NegativeCacheFacet;
import org.sonatype.nexus.repository.cache.NegativeCacheHandler;
import org.sonatype.nexus.repository.conda.AssetKind;
import org.sonatype.nexus.repository.conda.internal.security.CondaSecurityFacet;
import org.sonatype.nexus.repository.content.browse.BrowseFacet;
import org.sonatype.nexus.repository.content.maintenance.LastAssetMaintenanceFacet;
import org.sonatype.nexus.repository.content.search.SearchFacet;
import org.sonatype.nexus.repository.http.PartialFetchHandler;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet;
import org.sonatype.nexus.repository.routing.RoutingRuleHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.ConfigurableViewFacet;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Matcher;
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler;
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.view.handlers.HandlerContributor;
import org.sonatype.nexus.repository.view.handlers.LastDownloadedHandler;
import org.sonatype.nexus.repository.view.handlers.TimingHandler;
import org.sonatype.nexus.repository.view.matchers.ActionMatcher;
import org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import static org.sonatype.nexus.repository.conda.AssetKind.ARCH_CONDA_PACKAGE;
import static org.sonatype.nexus.repository.conda.AssetKind.ARCH_INDEX_HTML;
import static org.sonatype.nexus.repository.conda.AssetKind.ARCH_REPODATA2_JSON;
import static org.sonatype.nexus.repository.conda.AssetKind.ARCH_REPODATA_JSON;
import static org.sonatype.nexus.repository.conda.AssetKind.ARCH_REPODATA_JSON_BZ2;
import static org.sonatype.nexus.repository.conda.AssetKind.ARCH_TAR_PACKAGE;
import static org.sonatype.nexus.repository.conda.AssetKind.CHANNEL_DATA_JSON;
import static org.sonatype.nexus.repository.conda.AssetKind.CHANNEL_INDEX_HTML;
import static org.sonatype.nexus.repository.conda.AssetKind.CHANNEL_RSS_XML;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.CHANNELDATA_JSON;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.CONDA_EXT;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.INDEX_HTML;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.REPODATA2_JSON;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.REPODATA_JSON;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.REPODATA_JSON_BZ2;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.RSS_XML;
import static org.sonatype.nexus.repository.conda.util.CondaPathUtils.TAR_BZ2_EXT;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD;
public abstract class CondaRecipeSupport extends RecipeSupport {
@Autowired protected Provider<BrowseFacet> browseFacet;
@Autowired protected Provider<CondaSecurityFacet> securityFacet;
@Autowired protected Provider<ConfigurableViewFacet> viewFacet;
@Autowired protected Provider<SearchFacet> searchFacet;
@Autowired protected ExceptionHandler exceptionHandler;
@Autowired protected TimingHandler timingHandler;
@Autowired protected SecurityHandler securityHandler;
@Autowired protected PartialFetchHandler partialFetchHandler;
@Autowired protected ConditionalRequestHandler conditionalRequestHandler;
@Autowired protected ContentHeadersHandler contentHeadersHandler;
@Autowired protected HandlerContributor handlerContributor;
@Autowired protected Provider<LastAssetMaintenanceFacet> maintenanceFacet;
@Autowired protected Provider<HttpClientFacet> httpClientFacet;
@Autowired protected Provider<PurgeUnusedFacet> purgeUnusedFacet;
@Autowired protected Provider<NegativeCacheFacet> negativeCacheFacet;
@Autowired protected NegativeCacheHandler negativeCacheHandler;
@Autowired protected RoutingRuleHandler routingHandler;
@Autowired protected LastDownloadedHandler lastDownloadedHandler;
protected CondaRecipeSupport(Type type,Format format){super(type,format);}
protected static Matcher rootChannelIndexHtmlMatcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/" + INDEX_HTML + "", CHANNEL_INDEX_HTML, GET, HEAD);}
protected static Matcher rootChannelDataJsonMatcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/" + CHANNELDATA_JSON + "", CHANNEL_DATA_JSON, GET, HEAD);}
protected static Matcher rootChannelRssXmlMatcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/" + RSS_XML + "", CHANNEL_RSS_XML, GET, HEAD);}
protected static Matcher archIndexHtmlMatcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/{arch:.+}/" + INDEX_HTML + "", ARCH_INDEX_HTML, GET, HEAD);}
protected static Matcher archRepodataJsonMatcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/{arch:.+}/" + REPODATA_JSON + "", ARCH_REPODATA_JSON, GET, HEAD);}
protected static Matcher archRepodataJsonBz2Matcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/{arch:.+}/" + REPODATA_JSON_BZ2 + "", ARCH_REPODATA_JSON_BZ2, GET,
        HEAD);}
protected static Matcher archRepodata2JsonMatcher(){return buildTokenMatcherForPatternAndAssetKind("{path:.*}/{arch:.+}/" + REPODATA2_JSON + "", ARCH_REPODATA2_JSON, GET, HEAD);}
protected static Matcher archTarPackageMatcher(){return buildTokenMatcherForPatternAndAssetKind(
        "{path:.*}/{arch:.+}/{name:.+}-{version:.+}-{build:.+}{format:" + TAR_BZ2_EXT + "}",
        ARCH_TAR_PACKAGE, GET, HEAD);}
protected static Matcher archCondaPackageMatcher(){return buildTokenMatcherForPatternAndAssetKind(
        "{path:.*}/{arch:.+}/{name:.+}-{version:.+}-{build:.+}{format:" + CONDA_EXT + "}",
        ARCH_CONDA_PACKAGE, GET, HEAD);}
 private static Matcher buildTokenMatcherForPatternAndAssetKind(String pattern,AssetKind kind,String... actions){
  Matcher match=LogicMatchers.and(new ActionMatcher(actions),new TokenMatcher(pattern));
  return context->{if(!match.matches(context))return false;context.getAttributes().set(AssetKind.class,kind);return true;};
 }
}

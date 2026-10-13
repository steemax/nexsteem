/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.repository.content.npm.internal;

import jakarta.inject.Provider;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.RecipeSupport;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.group.GroupHandler;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.npm.internal.*;
import org.sonatype.nexus.repository.npm.internal.search.v1.NpmSearchGroupHandler;
import org.sonatype.nexus.repository.security.SecurityHandler;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler;
import org.sonatype.nexus.repository.http.HttpHandlers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import static org.sonatype.nexus.repository.http.HttpMethods.*;
import static org.sonatype.nexus.repository.npm.internal.NpmPaths.*;

@Component
@Qualifier(NpmGroupRecipe.NAME)
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from = "1.0")
public class NpmGroupRecipe extends RecipeSupport {
  public static final String NAME = "npm-group";
  @Autowired private Provider<NpmSecurityFacet> security;
  @Autowired private Provider<NpmTokenFacet> tokens;
  @Autowired private Provider<ConfigurableViewFacet> views;
  @Autowired @Qualifier("default") private Provider<GroupFacet> groups;
  @Autowired @Qualifier("default") private GroupHandler first;
  @Autowired private NpmGroupPackageHandler packages;
  @Autowired private NpmSearchGroupHandler search;
  @Autowired private SecurityHandler permissions;
  @Autowired private ExceptionHandler exceptions;
  @Autowired private NpmPingHandler ping;
  @Autowired private NpmWhoamiHandler whoami;

  public NpmGroupRecipe(@Qualifier(GroupType.NAME) Type type, @Qualifier(NpmFormat.NAME) Format format) { super(type, format); }

  @Override
  public void apply(Repository repository) throws Exception {
    repository.attach(groups.get()); repository.attach(security.get()); repository.attach(tokens.get());
    ConfigurableViewFacet view = views.get();
    Router.Builder routes = new Router.Builder();
    routes.route(packageMatcher(GET, HEAD).handler(permissions).handler(exceptions).handler(packages).create());
    routes.route(tarballMatcher(GET, HEAD).handler(permissions).handler(exceptions).handler(first).create());
    routes.route(searchV1Matcher().handler(permissions).handler(exceptions).handler(search).create());
    routes.route(distTagsMatcher(GET).handler(permissions).handler(exceptions).handler(first).create());
    routes.route(pingMatcher().handler(ping).create());
    routes.route(whoamiMatcher().handler(whoami).create());
    routes.route(userMatcher(PUT).handler(NpmHandlers.npmErrorHandler).handler(NpmHandlers.createToken).create());
    routes.route(tokenMatcher(DELETE).handler(NpmHandlers.npmErrorHandler).handler(NpmHandlers.deleteToken).create());
    routes.defaultHandlers(HttpHandlers.badRequest());
    view.configure(routes.create()); repository.attach(view);
  }
}

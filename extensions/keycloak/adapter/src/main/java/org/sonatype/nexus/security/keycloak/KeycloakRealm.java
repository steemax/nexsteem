/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.AllowAllCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.sonatype.nexus.common.Description;
import org.sonatype.nexus.security.authz.PermissionCachingAuthorizingRealm;
import org.sonatype.nexus.security.authz.PrincipalPermissionsCacheFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stateless API authentication. Enable this realm through Nexus Security -> Realms. */
@Component
@Qualifier(KeycloakRealm.NAME)
@Description("Keycloak Access Token Realm")
@ConditionalOnProperty(name = "nexus.keycloak.enabled", havingValue = "true")
public final class KeycloakRealm extends PermissionCachingAuthorizingRealm
{
  public static final String NAME = "KeycloakRealm";
  private final KeycloakTokenVerifier verifier;

  @Autowired
  public KeycloakRealm(PrincipalPermissionsCacheFactory cacheFactory,
      @Value("${nexus.keycloak.issuer}") String issuer,
      @Value("${nexus.keycloak.audience}") String audience,
      @Value("${nexus.keycloak.client-id}") String client,
      @Value("${nexus.keycloak.role-mapping}") String mapping,
      @Value("${nexus.keycloak.allow-http:false}") boolean allowHttp)
  {
    super(NAME, cacheFactory);
    Clock clock = Clock.systemUTC();
    verifier = new KeycloakTokenVerifier(issuer, audience, client, parseMapping(mapping),
        new KeycloakJwks(URI.create(issuer), allowHttp, clock), clock);
    setAuthenticationCachingEnabled(false);
    setAuthorizationCachingEnabled(false);
    // Signature/claim validation occurs in doGetAuthenticationInfo on every request.
    setCredentialsMatcher(new AllowAllCredentialsMatcher());
  }

  private static Map<String, String> parseMapping(String text) {
    Map<String, String> result = new HashMap<>();
    for (String pair : text.split(";", -1)) {
      String[] entry = pair.trim().split("=", -1);
      if (entry.length != 2 || !entry[0].matches("[A-Za-z0-9_.:-]+")
          || !entry[1].matches("[A-Za-z0-9_.:-]+") || result.put(entry[0], entry[1]) != null) {
        throw new IllegalArgumentException("Invalid Keycloak role mapping");
      }
    }
    return Map.copyOf(result);
  }

  @Override
  public boolean supports(AuthenticationToken token) {
    return token instanceof KeycloakBearerToken;
  }

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
    try {
      KeycloakIdentity identity = verifier.verify((String) token.getCredentials());
      SimplePrincipalCollection principals = new SimplePrincipalCollection(identity.subject(), NAME);
      principals.add(identity, NAME);
      return new SimpleAuthenticationInfo(principals, token.getCredentials());
    }
    catch (Exception failure) {
      // Do not include parser exceptions which may echo the supplied token.
      throw new AuthenticationException("Keycloak access token rejected");
    }
  }

  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    for (Object principal : principals.fromRealm(NAME)) {
      if (principal instanceof KeycloakIdentity identity && Instant.now().isBefore(identity.expiresAt())) {
        return new SimpleAuthorizationInfo(identity.nexusRoles());
      }
    }
    return null;
  }
}

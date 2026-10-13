/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
@org.springframework.stereotype.Component
public class ConanTokenManager extends org.sonatype.nexus.security.token.BearerTokenManager {
 @org.springframework.beans.factory.annotation.Autowired public ConanTokenManager(org.sonatype.nexus.security.authc.apikey.ApiKeyService keys,org.sonatype.nexus.security.SecurityHelper helper){super(keys,helper,ConanToken.NAME);}
 public String token(){var subject=securityHelper.subject();if(!subject.isAuthenticated())throw new org.apache.shiro.authc.AuthenticationException("Authentication required");return createToken(subject.getPrincipals());}
 public String username(){var subject=securityHelper.subject();if(!subject.isAuthenticated())throw new org.apache.shiro.authc.AuthenticationException("Authentication required");return subject.getPrincipal().toString();}
}

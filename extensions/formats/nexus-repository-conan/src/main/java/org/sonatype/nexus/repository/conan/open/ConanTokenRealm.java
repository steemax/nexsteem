/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier(ConanToken.NAME)
@org.sonatype.nexus.common.Description("Conan Bearer Token Realm")
public class ConanTokenRealm extends org.sonatype.nexus.security.token.BearerTokenRealm {
 @org.springframework.beans.factory.annotation.Autowired public ConanTokenRealm(org.sonatype.nexus.security.authc.apikey.ApiKeyService keys,org.sonatype.nexus.security.UserPrincipalsHelper helper){super(keys,helper,ConanToken.NAME);}
}

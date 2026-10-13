/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import org.sonatype.nexus.security.UserPrincipalsHelper;
import org.sonatype.nexus.security.authc.apikey.ApiKeyService;
import org.sonatype.nexus.security.token.BearerTokenRealm;
import org.sonatype.nexus.common.Description;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
@Component @Qualifier(RubygemsTokenExtractor.NAME) @Description("RubyGems API Key Realm")
public class RubygemsTokenRealm extends BearerTokenRealm {
  public RubygemsTokenRealm(ApiKeyService keys,UserPrincipalsHelper helper){super(keys,helper,RubygemsTokenExtractor.NAME);setAuthenticationCachingEnabled(false);}
}

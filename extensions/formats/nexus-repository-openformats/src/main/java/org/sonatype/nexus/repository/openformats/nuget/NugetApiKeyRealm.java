/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier(NugetApiKey.NAME)
@org.sonatype.nexus.common.Description("NuGet API-Key Realm")
public final class NugetApiKeyRealm extends org.sonatype.nexus.security.token.BearerTokenRealm {
 public NugetApiKeyRealm(org.sonatype.nexus.security.authc.apikey.ApiKeyService keys,org.sonatype.nexus.security.UserPrincipalsHelper helper){super(keys,helper,NugetApiKey.NAME);}
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
import org.apache.shiro.SecurityUtils;
import org.sonatype.nexus.security.authc.apikey.*;
@org.springframework.stereotype.Component
public class NugetKeyManager {
 private final ApiKeyService keys;
 public NugetKeyManager(ApiKeyService keys){this.keys=keys;}
 public String get(boolean reset){var subject=SecurityUtils.getSubject();if(!subject.isAuthenticated())throw new org.apache.shiro.authc.AuthenticationException("Authentication required");if(reset)keys.deleteApiKey(NugetApiKey.NAME,subject.getPrincipals());return new String(keys.getApiKey(NugetApiKey.NAME,subject.getPrincipals()).map(ApiKey::getApiKey).orElseGet(()->keys.createApiKey(NugetApiKey.NAME,subject.getPrincipals())));}
 public boolean valid(String value){if(value==null||value.isBlank()||value.length()>1024)return false;var subject=SecurityUtils.getSubject();if(!subject.isAuthenticated())return false;return keys.getApiKeyByToken(NugetApiKey.NAME,value.toCharArray()).map(key->key.getPrincipals().getPrimaryPrincipal().equals(subject.getPrincipal())).orElse(false);}
}

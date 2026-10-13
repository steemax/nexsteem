/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier(NugetApiKey.NAME)
public final class NugetApiKey implements org.sonatype.nexus.security.authc.apikey.ApiKeyExtractor {
 public static final String NAME="NuGetApiKey";
 @Override public String extract(jakarta.servlet.http.HttpServletRequest request){String key=request.getHeader("X-NuGet-ApiKey");return key==null||key.isBlank()||key.length()>1024||key.chars().anyMatch(Character::isWhitespace)?null:key;}
}

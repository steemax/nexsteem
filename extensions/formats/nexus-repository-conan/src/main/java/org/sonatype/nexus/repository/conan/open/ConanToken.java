/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.conan.open;
import jakarta.servlet.http.HttpServletRequest;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier(ConanToken.NAME)
public final class ConanToken implements org.sonatype.nexus.security.authc.apikey.ApiKeyExtractor {
 public static final String NAME="ConanToken";
 @Override public String extract(HttpServletRequest request){
  String header=request.getHeader("Authorization"),prefix="Bearer "+NAME+".";
  if(header==null||!header.startsWith(prefix))return null;
  String key=header.substring(prefix.length());
  return key.isEmpty()||key.length()>1024||key.chars().anyMatch(Character::isWhitespace)?null:key;
 }
}

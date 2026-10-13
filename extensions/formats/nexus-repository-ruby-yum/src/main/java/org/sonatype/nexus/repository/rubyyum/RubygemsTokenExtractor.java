/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import jakarta.servlet.http.HttpServletRequest;
import org.sonatype.nexus.security.authc.apikey.ApiKeyExtractor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
@Component @Qualifier(RubygemsTokenExtractor.NAME)
public class RubygemsTokenExtractor implements ApiKeyExtractor {
  static final String NAME="RubygemsToken";
  public String extract(HttpServletRequest request) {
    String value=request.getHeader("Authorization");if(value==null)return null;
    if(value.startsWith("Bearer "))value=value.substring(7);
    return value.startsWith(NAME+".") && value.length()<8192 ? value.substring(NAME.length()+1) : null;
  }
}

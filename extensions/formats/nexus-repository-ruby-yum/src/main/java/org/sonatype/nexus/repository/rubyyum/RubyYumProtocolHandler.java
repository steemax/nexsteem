/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.util.List;
import java.util.Map;
import org.sonatype.nexus.repository.proxy.ProxyHandler;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Handler;
import org.sonatype.nexus.repository.view.Response;
import org.springframework.stereotype.Component;
import static org.sonatype.nexus.repository.rubyyum.ProtocolSupport.*;

@Component
public class RubyYumProtocolHandler implements Handler {
  private final RubyYumGroupHandler group;
  private final ProxyHandler proxy;
  private final RubygemsTokenManager tokens;
  public RubyYumProtocolHandler(RubyYumGroupHandler group, ProxyHandler proxy, RubygemsTokenManager tokens) { this.group=group; this.proxy=proxy;this.tokens=tokens; }
  @Override public Response handle(Context context) throws Exception {
    try {
      String type=context.getRepository().getType().getValue();
      String format=context.getRepository().getFormat().getValue();
      String path=path(context), action=context.getRequest().getAction();
      if (!valid(format,path)) return response(404,null);
      if(format.equals("rubygems")&&path.equals("/api/v1/api_key")) {
        if(!tokens.authenticated())return response(401,null);
        if(action.equals("GET"))return new Response.Builder().copy(text(200,tokens.issue(context.getRequest().getHeaders().get("Authorization")),"text/plain")).header("Cache-Control","no-store").build();
        if(action.equals("DELETE"))return response(tokens.deleteToken()?204:404,null);
        return response(405,null);
      }
      if ("group".equals(type)) return group.handle(context);
      if ("proxy".equals(type)) {
        if (!List.of("GET","HEAD").contains(action)) return response(405,null);
        return proxy.handle(context);
      }
      RubyYumContentFacet store=context.getRepository().facet(RubyYumContentFacet.class);
      return "rubygems".equals(format) ? RubygemsProtocol.handle(context,store) : YumProtocol.handle(context,store);
    } catch (IllegalArgumentException error) {
      return json(400,Map.of("error",error.getMessage()==null?"Invalid package metadata":error.getMessage()));
    }
  }
  static boolean valid(String format,String path) {
    if ("rubygems".equals(format)) return path.equals("/") || path.matches("/(?:specs\\.4\\.8\\.gz|latest_specs\\.4\\.8\\.gz|prerelease_specs\\.4\\.8\\.gz|names|versions|info/[A-Za-z0-9_.-]+|quick/Marshal\\.4\\.8/[A-Za-z0-9_.-]+\\.gemspec\\.rz|gems/[A-Za-z0-9_.-]+\\.gem|api/v1/(?:gems(?:/yank)?|dependencies|api_key))");
    return "yum".equals(format) && (path.equals("/") || path.matches("/(?:[A-Za-z0-9_+.,@=-]+/)*[A-Za-z0-9_+.,@=-]+\\.(?:rpm|xml|gz|bz2|xz|zst|zck|asc|key)"));
  }
}

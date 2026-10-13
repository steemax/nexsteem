/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import jakarta.servlet.*;
import org.apache.shiro.web.servlet.OncePerRequestFilter;
import org.apache.shiro.web.util.WebUtils;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
/** A dedicated registry connector exposes only Registry API paths. */
@Component @Qualifier(DockerPortFilter.NAME) @jakarta.servlet.annotation.WebFilter(filterName = DockerPortFilter.NAME)
public class DockerPortFilter extends OncePerRequestFilter {
  public static final String NAME = "openDockerPort";
  private final DockerConnectors ports;
  public DockerPortFilter(DockerConnectors ports) { this.ports = ports; }
  @Override protected void doFilterInternal(ServletRequest request, ServletResponse response, FilterChain chain) throws java.io.IOException, ServletException {
    var incoming = WebUtils.toHttp(request);
    if (ports.repository(incoming.getLocalPort()) != null && !incoming.getRequestURI().substring(incoming.getContextPath().length()).startsWith("/v2/")) {
      WebUtils.toHttp(response).setStatus(404); return;
    }
    chain.doFilter(request, response);
  }
}

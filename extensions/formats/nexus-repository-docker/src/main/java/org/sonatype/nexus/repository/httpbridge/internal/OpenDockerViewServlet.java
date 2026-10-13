/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.httpbridge.internal;
import jakarta.servlet.http.*;
import jakarta.servlet.annotation.WebServlet;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpbridge.internal.describe.*;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.dockeropen.DockerConnectors;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Component;
@Component @WebServlet(urlPatterns="/v2/*")
public class OpenDockerViewServlet extends ViewServlet {
  private final RepositoryManager manager; private final DockerConnectors ports;
  private final HttpResponseSenderSelector senders;
  public OpenDockerViewServlet(RepositoryManager manager, HttpResponseSenderSelector senders, DescriptionHelper helper,
                               DescriptionRenderer renderer, DockerConnectors ports) {
    super(manager, senders, helper, renderer); this.manager = manager; this.ports = ports; this.senders = senders;
  }
  @Override protected void doService(HttpServletRequest incoming, HttpServletResponse outgoing) throws Exception {
    outgoing.setHeader("Docker-Distribution-Api-Version", "registry/2.0");
    String path = incoming.getRequestURI().substring(incoming.getContextPath().length());
    String name = ports.repository(incoming.getLocalPort()); Repository repository;
    if (name == null) {
      if (path.equals("/v2/")) {
        if (!SecurityUtils.getSubject().isAuthenticated()) {
          outgoing.setHeader("WWW-Authenticate", "Basic realm=\"Nexus Docker\""); outgoing.setStatus(401);
        } else { outgoing.setStatus(200); outgoing.getWriter().write("{}"); }
        return;
      }
      String remaining = path.substring(4); int slash = remaining.indexOf('/');
      if (slash <= 0) { outgoing.setStatus(404); return; }
      name = remaining.substring(0, slash); path = "/v2/" + remaining.substring(slash + 1);
      repository = manager.get(name);
      if (repository == null || !Boolean.parseBoolean(String.valueOf(repository.getConfiguration().attributes("docker").get("pathEnabled", true)))) {
        outgoing.setStatus(404); return;
      }
      incoming.setAttribute("open.docker.route-prefix", "/v2/" + name + "/");
    } else { repository = manager.get(name); incoming.setAttribute("open.docker.route-prefix", "/v2/"); }
    if (repository == null || !repository.getFormat().getValue().equals("docker")) { outgoing.setStatus(404); return; }
    if (!repository.getConfiguration().isOnline()) { outgoing.setStatus(503); return; }
    if (!SecurityUtils.getSubject().isAuthenticated() && !Boolean.FALSE.equals(repository.getConfiguration().attributes("docker").get("forceBasicAuth", Boolean.class))) {
      outgoing.setHeader("WWW-Authenticate", "Basic realm=\"Nexus Docker\""); outgoing.setStatus(401); return;
    }
    Request.Builder builder = new Request.Builder().action(incoming.getMethod()).path(path)
        .headers(new HttpHeadersAdapter(incoming)).parameters(new HttpParametersAdapter(incoming)).payload(new HttpRequestPayloadAdapter(incoming));
    builder.attribute("open.docker.route-prefix", incoming.getAttribute("open.docker.route-prefix"));
    dispatchAndSend(builder.build(), repository.facet(ViewFacet.class), senders.sender(repository), outgoing);
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.util.*;
import org.sonatype.nexus.bootstrap.jetty.*;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.config.Configuration;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.server.HttpConfiguration;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
@Component @Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class DockerConnectorFacet extends FacetSupport {
  private final ConnectorRegistrar registrar; private final DockerConnectors ports;
  private final List<ConnectorConfiguration> registered = new ArrayList<>();
  public DockerConnectorFacet(ConnectorRegistrar registrar, DockerConnectors ports) { this.registrar = registrar; this.ports = ports; }
  @Override protected void doValidate(Configuration config) {
    for (String key : List.of("httpPort", "httpsPort")) {
      Object value = config.attributes("docker").get(key);
      if (value == null || value.toString().isBlank()) { continue; }
      int port = Integer.parseInt(value.toString());
      if (port < 1024 || port > 65535) { throw new IllegalArgumentException("Docker connector port must be between 1024 and 65535"); }
      if (registrar.unavailablePorts().contains(port) && !Objects.equals(ports.repository(port), config.getRepositoryName())) {
        throw new jakarta.ws.rs.BadRequestException("Docker connector port is occupied");
      }
      HttpScheme scheme = key.equals("httpPort") ? HttpScheme.HTTP : HttpScheme.HTTPS;
      if (!registrar.availableSchemes().contains(scheme)) { throw new IllegalArgumentException("Jetty scheme is not configured: " + scheme); }
    }
    if (Boolean.TRUE.equals(config.attributes("docker").get("v1Enabled", Boolean.class))) { throw new IllegalArgumentException("Docker Registry v1 is obsolete; use Registry v2"); }
  }
  @Override protected void doStart() {
    try {
      for (String key : List.of("httpPort", "httpsPort")) {
        Object value = getRepository().getConfiguration().attributes("docker").get(key);
        if (value == null || value.toString().isBlank()) { continue; }
        final int port = Integer.parseInt(value.toString()); final HttpScheme scheme = key.equals("httpPort") ? HttpScheme.HTTP : HttpScheme.HTTPS;
        ConnectorConfiguration connector = new ConnectorConfiguration() {
          public HttpScheme getScheme() { return scheme; } public int getPort() { return port; }
          public HttpConfiguration customize(HttpConfiguration config) { return config; }
        };
        ports.add(port, getRepository().getName());
        try { registrar.addConnector(connector); registered.add(connector); }
        catch (RuntimeException failure) { ports.remove(port, getRepository().getName()); throw failure; }
      }
    } catch (RuntimeException failure) { doStop(); throw failure; }
  }
  @Override protected void doStop() {
    for (ConnectorConfiguration connector : registered) { registrar.removeConnector(connector); ports.remove(connector.getPort(), getRepository().getName()); }
    registered.clear();
  }
}

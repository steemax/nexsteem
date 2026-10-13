/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.util.*;
import java.net.*;
import java.io.IOException;
import org.sonatype.nexus.bootstrap.jetty.*;
import org.sonatype.nexus.bootstrap.entrypoint.jetty.JettyServer;
import org.eclipse.jetty.http.HttpScheme;
import org.springframework.stereotype.Component;
/** Adapter for the public Jetty API; Core defines the interface without registering a bean. */
@Component
public class DockerConnectorRegistrar implements ConnectorRegistrar {
  private final JettyServer server;
  private final Map<Integer,ConnectorConfiguration> added = new HashMap<>();
  public DockerConnectorRegistrar(JettyServer server) { this.server = server; }
  public synchronized List<HttpScheme> availableSchemes() {
    return server.defaultConnectors().stream().map(ConnectorConfiguration::getScheme).distinct().toList();
  }
  public synchronized List<Integer> unavailablePorts() {
    Set<Integer> values = new HashSet<>(added.keySet()); server.defaultConnectors().forEach(connector -> values.add(connector.getPort()));
    return List.copyOf(values);
  }
  public synchronized void addConnector(ConnectorConfiguration connector) {
    if (unavailablePorts().contains(connector.getPort())) { throw new IllegalArgumentException("Docker connector port is occupied"); }
    // Detect OS port conflicts before Core's legacy addCustomConnector, which logs bind IO failures.
    try (ServerSocket probe = new ServerSocket()) {
      // Jetty uses SO_REUSEADDR; a recently closed repository connector may have TIME_WAIT sockets.
      probe.setReuseAddress(true); probe.bind(new InetSocketAddress(connector.getPort()));
    } catch (IOException occupied) { throw new IllegalArgumentException("Docker connector port is occupied", occupied); }
    server.addCustomConnector(connector); added.put(connector.getPort(), connector);
  }
  public synchronized void removeConnector(ConnectorConfiguration connector) {
    try { server.removeCustomConnector(connector); }
    finally { added.remove(connector.getPort(), connector); }
  }
}

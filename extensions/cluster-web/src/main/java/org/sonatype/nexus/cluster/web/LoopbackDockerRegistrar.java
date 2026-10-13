/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http.HttpScheme;
import org.sonatype.nexus.bootstrap.entrypoint.jetty.JettyServer;
import org.sonatype.nexus.bootstrap.jetty.ConnectorConfiguration;
import org.sonatype.nexus.bootstrap.jetty.ConnectorRegistrar;

/**
 * Core custom connectors inherit application-host=127.0.0.1 from Jetty's
 * default connector. The supervisor listens on PodIP on the same public port;
 * repository configuration keeps the operator's ordinary Docker port.
 */
public final class LoopbackDockerRegistrar implements ConnectorRegistrar
{
  private final JettyServer server;
  private final Map<Integer, ConnectorConfiguration> added = new HashMap<>();

  public LoopbackDockerRegistrar(final JettyServer server) { this.server = server; }

  @Override public synchronized List<HttpScheme> availableSchemes() {
    return server.defaultConnectors().stream().map(ConnectorConfiguration::getScheme).distinct().toList();
  }

  @Override public synchronized List<Integer> unavailablePorts() {
    var ports = new HashSet<>(added.keySet());
    server.defaultConnectors().forEach(connector -> ports.add(connector.getPort()));
    return List.copyOf(ports);
  }

  @Override public synchronized void addConnector(final ConnectorConfiguration connector) {
    if (unavailablePorts().contains(connector.getPort())) {
      throw new IllegalArgumentException("Docker connector port is occupied");
    }
    try (var probe = new ServerSocket()) {
      probe.setReuseAddress(true);
      probe.bind(new InetSocketAddress("127.0.0.1", connector.getPort()));
    }
    catch (IOException occupied) {
      throw new IllegalArgumentException("Docker connector port is occupied", occupied);
    }
    server.addCustomConnector(connector);
    added.put(connector.getPort(), connector);
  }

  @Override public synchronized void removeConnector(final ConnectorConfiguration connector) {
    try { server.removeCustomConnector(connector); }
    finally { added.remove(connector.getPort(), connector); }
  }
}

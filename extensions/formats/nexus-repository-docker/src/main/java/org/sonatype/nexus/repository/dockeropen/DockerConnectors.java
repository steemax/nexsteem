/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
@Component
public class DockerConnectors {
  private final ConcurrentHashMap<Integer, String> repositories = new ConcurrentHashMap<>();
  public synchronized void add(int port, String repository) {
    if (repositories.putIfAbsent(port, repository) != null) { throw new IllegalArgumentException("Docker port is already assigned"); }
  }
  public void remove(int port, String repository) { repositories.remove(port, repository); }
  public String repository(int port) { return repositories.get(port); }
}

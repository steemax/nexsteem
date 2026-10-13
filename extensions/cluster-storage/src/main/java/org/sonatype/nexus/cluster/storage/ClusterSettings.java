/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ClusterSettings
{
  public final String cluster;
  public final String node;
  public final String owner;
  public final long epoch;
  public final Path root;
  public final int copies;
  public final int port;
  public final int transferTimeoutSeconds;
  public final Map<String, URI> members;
  public final Map<String, String> domains;
  public final byte[] key;
  public final boolean testHttp;

  private final java.util.Properties configuration;

  public ClusterSettings() throws IOException { this(new java.util.Properties()); }

  public ClusterSettings(final java.util.Properties configuration) throws IOException {
    this.configuration = configuration;
    cluster = needed("NX_CLUSTER_ID"); node = needed("NX_CLUSTER_NODE_ID");
    owner = setting("NX_CLUSTER_OWNER_ID", node);
    if (!cluster.matches("[A-Za-z0-9_.-]{1,80}") || !node.matches("[A-Za-z0-9_.-]{1,120}")) {
      throw new IOException("Invalid cluster or member identity");
    }
    if (!owner.matches("[A-Za-z0-9_.:-]{1,250}")) { throw new IOException("Invalid supervisor fencing owner identity"); }
    epoch = Long.parseLong(setting("NX_CLUSTER_EPOCH", "0"));
    root = Path.of(needed("NX_CLUSTER_ROOT")).toAbsolutePath().normalize();
    copies = Integer.parseInt(setting("NX_CLUSTER_REQUIRED_COPIES", "2"));
    port = Integer.parseInt(setting("NX_CLUSTER_PEER_PORT", "8095"));
    transferTimeoutSeconds = Integer.parseInt(setting("NX_CLUSTER_TRANSFER_TIMEOUT_SECONDS", "1800"));
    if (transferTimeoutSeconds < 30 || transferTimeoutSeconds > 3600) { throw new IOException("Transfer timeout must be 30..3600 seconds"); }
    testHttp = "true".equals(setting("NX_CLUSTER_ALLOW_HTTP_TEST", "false"));
    Map<String, Object> configured = new ObjectMapper().readValue(needed("NX_CLUSTER_PEERS_JSON"),
        new TypeReference<Map<String, Object>>() {});
    members = new LinkedHashMap<>();
    domains = new LinkedHashMap<>();
    for (Map.Entry<String, Object> member : configured.entrySet()) {
      if (!member.getKey().matches("[A-Za-z0-9_.-]{1,120}")) { throw new IOException("Invalid member name"); }
      String endpoint;
      if (member.getValue() instanceof Map) {
        Map<?, ?> spec = (Map<?, ?>) member.getValue();
        endpoint = String.valueOf(spec.get("url"));
        domains.put(member.getKey(), String.valueOf(spec.get("dc")));
      }
      else {
        endpoint = String.valueOf(member.getValue());
        if (!testHttp) { throw new IOException("Production membership must specify url and dc"); }
        domains.put(member.getKey(), member.getKey());
      }
      if (!domains.get(member.getKey()).matches("[A-Za-z0-9_.-]{1,80}")
          || domains.get(member.getKey()).equals("null")) { throw new IOException("Invalid member failure domain"); }
      URI uri = URI.create(endpoint);
      if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
          || !List.of("", "/").contains(uri.getPath())
          || !("https".equals(uri.getScheme()) || (testHttp && "http".equals(uri.getScheme())))) {
        throw new IOException("Replica endpoints require HTTPS origin URLs without credentials");
      }
      members.put(member.getKey(), URI.create(uri.toString().replaceAll("/$", "")));
    }
    if (!members.containsKey(node) || copies < 2 || copies > members.size() || members.size() < 2
        || domains.values().stream().distinct().count() < 2) {
      throw new IOException("ClusterLocal requires at least two members in two failure domains and at least two normal-mode durable copies");
    }
    key = Files.readAllBytes(Path.of(needed("NX_CLUSTER_SECRET_FILE")));
    if (key.length < 32 || key.length > 4096) { throw new IOException("Replica authentication key must contain 32..4096 bytes"); }
  }

  String setting(final String name, final String fallback) { return configuration.getProperty(name, value(name, fallback)); }
  String needed(final String name) throws IOException {
    String v = setting(name, "");
    if (v.isBlank()) { throw new IOException("Required cluster setting: " + name); }
    return v;
  }

  static String value(final String name, final String fallback) {
    String property = System.getProperty(name);
    return property != null ? property : System.getenv().getOrDefault(name, fallback);
  }

  static String required(final String name) throws IOException {
    String value = value(name, "");
    if (value.isBlank()) { throw new IOException("Required cluster setting: " + name); }
    return value;
  }
}

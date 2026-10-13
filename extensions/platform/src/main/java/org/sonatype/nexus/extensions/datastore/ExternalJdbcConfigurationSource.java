/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.extensions.datastore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.sonatype.nexus.datastore.DataStoreConfigurationSource;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** External PostgreSQL configuration; database implementation remains upstream MyBatis. */
@Component
@Qualifier("open-jdbc")
@Order(DataStoreConfigurationSource.ORDER_ENVIRONMENT_VALUES)
public final class ExternalJdbcConfigurationSource implements DataStoreConfigurationSource
{
  private final String url;
  private final String username;
  private final String password;
  private final int maximumPoolSize;
  private final int minimumIdle;

  public ExternalJdbcConfigurationSource(
      @Value("${nexus.datastore.nexus.jdbcUrl:}") String url,
      @Value("${nexus.datastore.nexus.username:}") String username,
      @Value("${nexus.datastore.nexus.password:}") String password,
      @Value("${nexus.datastore.nexus.password-file:}") String passwordFile,
      @Value("${nexus.datastore.nexus.maximumPoolSize:40}") int maximumPoolSize,
      @Value("${nexus.datastore.nexus.minimumIdle:5}") int minimumIdle) throws IOException
  {
    this.url = url;
    this.username = username;
    this.password = passwordFile.isBlank() ? password : Files.readString(Path.of(passwordFile)).stripTrailing();
    if (maximumPoolSize < 1 || minimumIdle < 0 || minimumIdle > maximumPoolSize) {
      throw new IllegalArgumentException("Invalid JDBC pool size");
    }
    this.maximumPoolSize = maximumPoolSize;
    this.minimumIdle = minimumIdle;
    if (!url.isBlank() && (!url.startsWith("jdbc:postgresql://") || username.isBlank() || this.password.isBlank())) {
      throw new IllegalArgumentException("External datastore requires a PostgreSQL URL, username and password");
    }
  }

  @Override public String getName() { return "Open PostgreSQL"; }
  @Override public boolean isEnabled() { return !url.isBlank(); }
  @Override public Iterable<String> browseStoreNames() { return List.of("nexus"); }

  @Override
  public DataStoreConfiguration load(String storeName) {
    if (!"nexus".equals(storeName)) {
      throw new IllegalArgumentException("Only the nexus datastore is configured");
    }
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName(storeName);
    config.setType("jdbc");
    config.setSource("open-jdbc");
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("jdbcUrl", url);
    attributes.put("username", username);
    attributes.put("password", password);
    attributes.put("maximumPoolSize", Integer.toString(maximumPoolSize));
    attributes.put("minimumIdle", Integer.toString(minimumIdle));
    config.setAttributes(attributes);
    return config;
  }
}

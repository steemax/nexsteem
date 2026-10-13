/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.sonatype.nexus.datastore.DataStoreConfigurationSource;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Supplies fixed incarnation and epoch on every physical Hikari connection, including Flyway/Quartz. */
@Component @Qualifier("cluster-jdbc") @Order(DataStoreConfigurationSource.ORDER_ENVIRONMENT_VALUES)
@ConditionalOnProperty(name="nexus.cluster.enabled",havingValue="true")
public final class ClusterDataStoreConfigurationSource implements DataStoreConfigurationSource {
  private final String url,user,password,cluster,owner;
  private final long epoch;
  public ClusterDataStoreConfigurationSource(
      @Value("${nexus.cluster.sql-url}") String url,@Value("${nexus.cluster.sql-user}") String user,
      @Value("${nexus.cluster.sql-password-file}") String passwordFile,
      @Value("${nexus.cluster.id}") String cluster,@Value("${nexus.cluster.owner}") String owner,
      @Value("${nexus.cluster.epoch}") long epoch) throws Exception {
    if(!url.startsWith("jdbc:postgresql://")||epoch<1||cluster.isBlank()||owner.isBlank())throw new IllegalArgumentException("Cluster requires PostgreSQL and a fixed acquired incarnation/epoch");
    this.url=JdbcEpoch.bind(url,cluster,owner,epoch);this.user=user;this.password=Files.readString(Path.of(passwordFile)).stripTrailing();this.cluster=cluster;this.owner=owner;this.epoch=epoch;
  }
  @Override public String getName(){return "Cluster PostgreSQL (fenced epoch)";}
  @Override public Iterable<String> browseStoreNames(){return List.of("nexus");}
  static String literal(String value){return "'"+value.replace("'","''")+"'";}
  @Override public DataStoreConfiguration load(String name){
    if(!"nexus".equals(name))throw new IllegalArgumentException("Only Nexus store is supported");
    var config=new DataStoreConfiguration();config.setName(name);config.setType("jdbc");config.setSource("cluster-jdbc");
    var attributes=new LinkedHashMap<String,String>();attributes.put("jdbcUrl",url);attributes.put("username",user);attributes.put("password",password);
    attributes.put("maximumPoolSize","40");attributes.put("minimumIdle","2");
    attributes.put("connectionInitSql","SELECT public.nx_cluster_bind("+literal(cluster)+","+literal(owner)+","+epoch+")");
    config.setAttributes(attributes);return config;
  }
}

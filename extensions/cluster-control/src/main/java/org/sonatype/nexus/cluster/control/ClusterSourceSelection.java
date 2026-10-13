/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import org.sonatype.nexus.datastore.DataStoreConfigurationSource;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Cluster failures must never silently fall through to a local H2 or unfenced source. */
@Configuration @ConditionalOnProperty(name="nexus.cluster.enabled",havingValue="true")
public class ClusterSourceSelection {
  @Bean public static BeanPostProcessor clusterDataStoreSources(){return new BeanPostProcessor(){
    @Override public Object postProcessAfterInitialization(Object bean,String name){
      if(bean instanceof DataStoreConfigurationSource source && !(bean instanceof ClusterDataStoreConfigurationSource))
        return new DataStoreConfigurationSource(){
          @Override public String getName(){return source.getName();}
          @Override public boolean isEnabled(){return false;}
          @Override public Iterable<String> browseStoreNames(){return source.browseStoreNames();}
          @Override public DataStoreConfiguration load(String store){return source.load(store);}
        };
      return bean;
    }
  };}
}

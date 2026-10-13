/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.web;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional cluster image only: public registry ports belong to the fenced supervisor. */
@Configuration
public class ClusterWebConfiguration
{
  @Bean public static BeanFactoryPostProcessor clusterDockerRegistrar() {
    return factory -> {
      if (System.getenv("NX_CLUSTER_ID") == null || System.getenv("NX_CLUSTER_ID").isBlank()) { return; }
      for (String name : factory.getBeanDefinitionNames()) {
        var definition = factory.getBeanDefinition(name);
        if ("org.sonatype.nexus.repository.dockeropen.DockerConnectorRegistrar".equals(definition.getBeanClassName())) {
          definition.setBeanClassName(LoopbackDockerRegistrar.class.getName());
        }
      }
    };
  }
}

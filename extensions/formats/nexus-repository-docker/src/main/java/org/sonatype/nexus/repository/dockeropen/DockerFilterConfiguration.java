/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import org.sonatype.nexus.security.FilterChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanPostProcessor;
@Configuration
public class DockerFilterConfiguration {
  @Bean public FilterChain openDockerChain() { return new FilterChain("/v2/**", "noSessionCreation",
      org.sonatype.nexus.security.authc.NexusAuthenticationFilter.NAME,
      org.sonatype.nexus.security.authc.apikey.ApiKeyAuthenticationFilter.NAME,
      org.sonatype.nexus.security.anonymous.AnonymousFilter.NAME,
      org.sonatype.nexus.security.authc.AntiCsrfFilter.NAME); }
  @Bean public static BeanPostProcessor dockerPortChains() {
    return new BeanPostProcessor() {
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof FilterChain chain && !chain.getFilterExpression().contains(DockerPortFilter.NAME)) {
          return new FilterChain(chain.getPathPattern(), DockerPortFilter.NAME, chain.getFilterExpression());
        }
        return bean;
      }
    };
  }
}

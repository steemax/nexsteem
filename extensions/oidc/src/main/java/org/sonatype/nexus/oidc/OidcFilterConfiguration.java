/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.oidc;
import org.sonatype.nexus.security.FilterChain;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/** Add the OIDC cookie authenticator to existing Shiro chains without replacing their protections. */
@Configuration
public class OidcFilterConfiguration {
  @Bean public FilterChain oidcEndpointChain() { return new FilterChain("/oidc/**", OidcBrowserFilter.NAME); }
  @Bean public FilterChain oidcRootChain() { return new FilterChain("/", OidcBrowserFilter.NAME); }
  @Bean public static BeanPostProcessor oidcChains() {
    return new BeanPostProcessor() {
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof FilterChain chain && !chain.getFilterExpression().contains(OidcBrowserFilter.NAME)) {
          return new FilterChain(chain.getPathPattern(), OidcBrowserFilter.NAME, chain.getFilterExpression());
        }
        return bean;
      }
    };
  }
}

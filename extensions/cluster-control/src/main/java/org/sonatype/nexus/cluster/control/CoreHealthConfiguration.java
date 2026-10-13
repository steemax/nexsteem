/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import org.sonatype.nexus.security.FilterChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** Health authenticates the fixed incarnation with HMAC even when anonymous user access is disabled. */
@Configuration @ConditionalOnProperty(name="nexus.cluster.enabled",havingValue="true")
public class CoreHealthConfiguration {
  @Bean public FilterChain clusterSignedCoreHealth(){return new FilterChain(CoreHealthProof.PATH,"anon");}
}

/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.extensions.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/** Use the upstream default mapper's filter policy for Siesta too. */
@Component
public final class SiestaFilterCompatibility implements BeanPostProcessor
{
  private final ObjectProvider<JsonMapper> defaults;

  public SiestaFilterCompatibility(@Qualifier("default") ObjectProvider<JsonMapper> defaults) {
    this.defaults = defaults;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String name) {
    if (bean instanceof ObjectMapper mapper && !(bean instanceof JsonMapper)) {
      JsonMapper configured = defaults.getIfAvailable();
      if (configured != null && configured.getSerializationConfig().getFilterProvider() != null) {
        mapper.setFilterProvider(configured.getSerializationConfig().getFilterProvider());
      }
    }
    return bean;
  }
}

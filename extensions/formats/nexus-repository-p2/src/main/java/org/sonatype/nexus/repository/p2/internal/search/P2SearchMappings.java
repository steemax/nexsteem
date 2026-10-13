/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.p2.internal.search;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;


import org.sonatype.nexus.extensions.compat.ComponentSupport;
import org.sonatype.nexus.repository.p2.internal.P2Format;
import org.sonatype.nexus.repository.rest.SearchMapping;
import org.sonatype.nexus.repository.rest.SearchMappings;

import com.google.common.collect.ImmutableList;
/**
 * @since 3.28
 *
 */
@org.springframework.stereotype.Component
@Qualifier(P2Format.NAME)

public class P2SearchMappings
    extends ComponentSupport
    implements SearchMappings
{
  private static final List<SearchMapping> MAPPINGS = ImmutableList.of(
      new SearchMapping("p2.pluginName", "attributes.p2.pluginName", "p2 plugin name",org.sonatype.nexus.repository.rest.sql.SearchField.NAMESPACE)
  );

  @Override
  public Iterable<SearchMapping> get() {
    return MAPPINGS;
  }
}

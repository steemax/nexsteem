/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.extensions.compat;

/** Logging base used by the published EPL protocol sources. */
public abstract class ComponentSupport {
  protected final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(getClass());
}

/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.security.keycloak;

import java.io.Serializable;
import java.time.Instant;
import java.util.Set;

/** Verified identity; no bearer token or user-controllable Nexus role IDs are retained. */
public record KeycloakIdentity(String subject, Instant expiresAt, Set<String> nexusRoles)
    implements Serializable
{
  public KeycloakIdentity {
    nexusRoles = Set.copyOf(nexusRoles);
  }
}

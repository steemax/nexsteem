/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

/** Immutable for a Nexus epoch; an existing writer may never adopt a different primary term. */
final class PgAuthorityGuard {
  @FunctionalInterface interface Renewal { boolean renew()throws Exception; }
  private final PgAuthorityTerm bound;
  PgAuthorityGuard(PgAuthorityTerm bound){this.bound=java.util.Objects.requireNonNull(bound);}
  PgAuthorityTerm term(){return bound;}
  void require(PgAuthorityTerm current){
    if(!bound.equals(current))throw new IllegalStateException("PostgreSQL authority term changed; old Core must stop before renewal or acknowledgement");
  }
  boolean renew(PgAuthorityTerm current,Renewal operation)throws Exception {
    require(current);return operation.renew();
  }
}

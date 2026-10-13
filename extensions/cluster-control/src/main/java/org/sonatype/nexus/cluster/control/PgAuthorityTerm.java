/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

/** The DCS leader lease identifies a primary incarnation; KV revision changes on unrelated heartbeats. */
record PgAuthorityTerm(String node,String leaseId) {
  PgAuthorityTerm {
    if(node==null||!node.matches("[A-Za-z0-9_.-]{1,120}")||leaseId==null||!leaseId.matches("-?[0-9]{1,20}")||leaseId.equals("0"))throw new IllegalArgumentException("Invalid PostgreSQL authority term");
  }
  @Override public String toString(){return node+":"+leaseId;}
}

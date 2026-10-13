/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import org.sonatype.nexus.repository.security.*;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.security.BreadActions;
public abstract class RubyYumSecurityFacet extends SecurityFacetSupport {
 public RubyYumSecurityFacet(RepositoryFormatSecurityContributor contributor,VariableResolverAdapter resolver,ContentPermissionChecker checker) { super(contributor,resolver,checker); }
 @Override protected String action(Request request) {
  // Git LFS uses POST even to query download actions. Upload authorization is checked separately after parsing.
  if("gitlfs".equals(getRepository().getFormat().getValue()) && "POST".equals(request.getAction()) && request.getPath().endsWith("/objects/batch")) return BreadActions.READ;
  return super.action(request);
 }
}

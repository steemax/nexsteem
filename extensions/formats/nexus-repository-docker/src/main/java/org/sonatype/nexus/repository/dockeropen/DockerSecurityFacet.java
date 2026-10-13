/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import org.sonatype.nexus.repository.security.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.annotation.Qualifier;
@Component @Scope("prototype")
public class DockerSecurityFacet extends SecurityFacetSupport {
  public DockerSecurityFacet(DockerSecurityContributor contributor, @Qualifier("simple") VariableResolverAdapter resolver,
                             ContentPermissionChecker checker) { super(contributor, resolver, checker); }
}

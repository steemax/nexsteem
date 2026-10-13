/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
@Component
public class DockerSecurityContributor extends RepositoryFormatSecurityContributor {
  public DockerSecurityContributor(@Qualifier("docker") Format format) { super(format); }
}

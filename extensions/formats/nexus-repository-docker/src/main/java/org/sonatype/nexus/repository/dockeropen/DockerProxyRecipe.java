/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import org.sonatype.nexus.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
@Component @Qualifier("docker-proxy")
@org.sonatype.nexus.common.upgrade.AvailabilityVersion(from="1.0")
public class DockerProxyRecipe extends DockerRecipeSupport {
  public DockerProxyRecipe(@Qualifier("proxy") Type type, @Qualifier("docker") Format format) { super(type, format); }
}

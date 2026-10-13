/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import org.sonatype.nexus.repository.content.store.FormatStoreSupport;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
@Component @Qualifier("docker")
public class DockerStore extends FormatStoreSupport<DockerContentRepositoryDAO, DockerComponentDAO, DockerAssetDAO, DockerAssetBlobDAO, DockerBrowseNodeDAO> {}

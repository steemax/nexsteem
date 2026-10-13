/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Only packaged in the optional Kubernetes image; the base Debian distribution remains unchanged. */
@Configuration
public class ClusterDefaultConfiguration
{
  @Bean public static BeanFactoryPostProcessor clusterDefaultBlobProvider() {
    return factory -> {
      if (ClusterSettings.value("NX_CLUSTER_ID", "").isBlank()) { return; }
      for (String name : factory.getBeanDefinitionNames()) {
        org.springframework.beans.factory.config.BeanDefinition bean = factory.getBeanDefinition(name);
        if ("org.sonatype.nexus.self.hosted.blobstore.file.DefaultFileDefaultBlobStoreProvider".equals(bean.getBeanClassName())) {
          bean.setBeanClassName(ClusterDefaultProvider.class.getName());
        }
        else if ("org.sonatype.nexus.blobstore.file.internal.FileBlobStoreDescriptor".equals(bean.getBeanClassName())) {
          bean.setBeanClassName(ClusterDisabledDescriptors.File.class.getName());
        }
        else if ("org.sonatype.nexus.blobstore.s3.internal.S3BlobStoreDescriptor".equals(bean.getBeanClassName())) {
          bean.setBeanClassName(ClusterDisabledDescriptors.S3.class.getName());
        }
        else if ("org.sonatype.nexus.blobstore.group.internal.BlobStoreGroupDescriptor".equals(bean.getBeanClassName())) {
          bean.setBeanClassName(ClusterDisabledDescriptors.Group.class.getName());
        }
      }
    };
  }
}

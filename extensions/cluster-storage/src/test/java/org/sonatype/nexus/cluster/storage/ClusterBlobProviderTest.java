/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import jakarta.inject.Provider;

import org.junit.Test;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.common.QualifierUtil;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

/** Regression for the actual Core prototype-provider discovery failure, using its real qualifier mapper. */
public class ClusterBlobProviderTest
{
  @Test public void springProviderIsQualifiedAndReturnsSeparatePrototypes() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(DataStoreManager.class, () -> mock(DataStoreManager.class));
      context.registerBean(ClusterBlobStore.class, () -> new ClusterBlobStore(context.getBean(DataStoreManager.class)),
          definition -> definition.setScope("prototype"));
      context.register(ClusterBlobProviderConfiguration.class); context.refresh();
      Provider<BlobStore> provider = (Provider<BlobStore>) context.getBean("clusterBlobStoreProvider");
      assertEquals(ClusterBlobStore.TYPE, QualifierUtil.value(provider).orElse(""));
      assertNotSame(provider.get(), provider.get());
      assertNotNull(QualifierUtil.buildQualifierBeanMap(java.util.List.of(provider)).get(ClusterBlobStore.TYPE));
    }
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import jakarta.inject.Provider;

import org.sonatype.nexus.blobstore.api.BlobStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Core discovers BlobStore prototypes through explicitly qualified Provider beans. */
@Configuration
public class ClusterBlobProviderConfiguration
{
  @Bean public Provider<BlobStore> clusterBlobStoreProvider(final ObjectProvider<ClusterBlobStore> provider) {
    return new ClusterProvider(provider);
  }

  @Qualifier(ClusterBlobStore.TYPE)
  private static record ClusterProvider(ObjectProvider<ClusterBlobStore> provider) implements Provider<BlobStore>
  {
    @Override public BlobStore get() { return provider.getIfUnique(); }
  }
}

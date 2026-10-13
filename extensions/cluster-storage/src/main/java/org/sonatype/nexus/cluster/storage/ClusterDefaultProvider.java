/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.util.function.Supplier;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.DefaultBlobStoreProvider;

public final class ClusterDefaultProvider implements DefaultBlobStoreProvider
{
  @Override public BlobStoreConfiguration get(final Supplier<BlobStoreConfiguration> factory) {
    BlobStoreConfiguration config = factory.get();
    config.setName("default"); config.setType(ClusterBlobStore.TYPE);
    return config;
  }
}

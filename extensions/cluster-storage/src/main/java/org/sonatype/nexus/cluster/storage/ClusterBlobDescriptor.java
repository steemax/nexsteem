/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.util.List;

import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.formfields.FormField;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Qualifier(ClusterBlobStore.TYPE)
@AvailabilityVersion(from = "1.0")
public final class ClusterBlobDescriptor implements BlobStoreDescriptor
{
  public String getId() { return "cluster-local"; }
  public String getName() { return "Replicated local PVC"; }
  public List<FormField> getFormFields() { return List.of(); }
  public boolean isModifiable() { return false; }
  public boolean isEnabled() { return !ClusterSettings.value("NX_CLUSTER_ID", "").isBlank(); }
  public void validateConfig(final BlobStoreConfiguration config) {
    if (!isEnabled()) { throw new IllegalStateException("ClusterLocal is enabled only by the optional Kubernetes cluster profile"); }
  }
}

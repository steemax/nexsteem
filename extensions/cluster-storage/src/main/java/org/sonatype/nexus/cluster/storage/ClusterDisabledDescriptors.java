/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.util.List;

import org.sonatype.nexus.blobstore.BlobStoreDescriptor;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.upgrade.AvailabilityVersion;
import org.sonatype.nexus.formfields.FormField;
import org.springframework.beans.factory.annotation.Qualifier;

/** Replaces descriptor beans only in the optional cluster profile. SQL admission also protects direct API writes. */
public abstract class ClusterDisabledDescriptors implements BlobStoreDescriptor
{
  public boolean isEnabled() { return false; }
  public List<FormField> getFormFields() { return List.of(); }
  public void validateConfig(final BlobStoreConfiguration configuration) {
    throw new IllegalArgumentException("Kubernetes cluster requires a replicated ClusterLocal blob store");
  }

  @Qualifier("File") @AvailabilityVersion(from="1.0")
  public static final class File extends ClusterDisabledDescriptors {
    public String getId() { return "file"; } public String getName() { return "File"; }
  }
  @Qualifier("S3") @AvailabilityVersion(from="1.0")
  public static final class S3 extends ClusterDisabledDescriptors {
    public String getId() { return "s3"; } public String getName() { return "S3"; }
  }
  @Qualifier("Group") @AvailabilityVersion(from="1.0")
  public static final class Group extends ClusterDisabledDescriptors {
    public String getId() { return "group"; } public String getName() { return "Group"; }
  }
}

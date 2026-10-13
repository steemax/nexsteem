/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.io.IOException;
import java.util.*;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.config.WritePolicy;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.annotation.Qualifier;
@Component @Scope("prototype")
@org.sonatype.nexus.repository.Facet.Exposed
public class DockerContentFacet extends ContentFacetSupport {
  public DockerContentFacet(@Qualifier("docker") FormatStoreManager stores) { super(stores); }
  @Override protected WritePolicy writePolicy(Asset asset) {
    if (asset.path().contains("/uploads/")) { return WritePolicy.ALLOW; }
    if (asset.path().endsWith("/manifests/latest") && Boolean.TRUE.equals(getRepository().getConfiguration().attributes("storage").get("latestPolicy", Boolean.class))) {
      return WritePolicy.ALLOW;
    }
    return super.writePolicy(asset);
  }
  public Content read(String path) { return assets().path(path).find().map(a -> a.markAsDownloaded().download()).orElse(null); }
  public Optional<FluentAsset> asset(String path) { return assets().path(path).find(); }
  public Content put(String path, Payload payload, String kind, String image, String version) throws IOException {
    try (TempBlob blob = blobs().ingest(payload, List.of(HashAlgorithm.SHA256))) {
      String tail = path.substring(path.lastIndexOf('/') + 1);
      if (tail.matches("sha256:[a-f0-9]{64}") && !tail.equals("sha256:" + blob.getHashes().get(HashAlgorithm.SHA256))) {
        throw new IOException("Docker content digest mismatch");
      }
      var builder = assets().path(path).kind(kind).blob(blob);
      if (image != null && !"blob".equals(kind) && !kind.startsWith("upload")) {
        builder.component(components().name(image.substring(image.lastIndexOf('/') + 1))
            .namespace(image.contains("/") ? image.substring(0, image.lastIndexOf('/')) : "")
            .version(version == null ? "" : version).getOrCreate());
      }
      return builder.save().markAsCached(payload).download();
    }
  }
  public List<FluentAsset> listing() {
    List<FluentAsset> result = new ArrayList<>(); String cursor = null;
    do { Continuation<FluentAsset> page = assets().browse(1000, cursor); if (page.isEmpty()) { break; } result.addAll(page); cursor = page.nextContinuationToken(); }
    while (cursor != null);
    return result;
  }
  public boolean capacityForUpload() {
    int pending = 0; String cursor = null;
    var oldest = java.time.OffsetDateTime.now().minusHours(24);
    do {
      Continuation<FluentAsset> page = assets().byKind("upload").browse(1000, cursor);
      if (page.isEmpty()) { break; }
      for (FluentAsset asset : page) { if (asset.created().isBefore(oldest)) { removeUpload(asset.path()); } else { pending++; } }
      cursor = page.nextContinuationToken();
    } while (cursor != null && pending < 1000);
    return pending < 1000;
  }
  public void removeUpload(String path) {
    String cursor = null;
    do {
      var page = assets().byFilter("path LIKE #{filterParams.prefix}", Map.of("prefix", path + "/chunks/%")).browse(1000, cursor);
      if (page.isEmpty()) { break; }
      cursor = page.nextContinuationToken(); page.forEach(FluentAsset::delete);
    } while (cursor != null);
    remove(path);
  }
  public boolean remove(String path) { return asset(path).map(FluentAsset::delete).orElse(false); }
}

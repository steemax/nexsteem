/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.io.*;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sonatype.nexus.repository.mime.ContentValidator;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
/** Registry blobs are arbitrary digest-addressed bytes, including empty upload placeholders. */
@Component @Qualifier("docker")
public class DockerContentValidator implements ContentValidator {
  private final ObjectMapper json;
  private static final Set<String> TYPES = Set.of("application/vnd.docker.distribution.manifest.v2+json",
      "application/vnd.docker.distribution.manifest.list.v2+json", "application/vnd.oci.image.manifest.v1+json", "application/vnd.oci.image.index.v1+json");
  public DockerContentValidator(ObjectMapper json) { this.json = json; }
  public String determineContentType(boolean strict, InputStreamSupplier content, MimeRulesSource rules, String name, String declared) throws IOException {
    if (name != null && name.contains("/blobs/")) { return "application/octet-stream"; }
    byte[] bytes; try (InputStream stream = content.get()) { bytes = stream.readNBytes(4*1024*1024+1); }
    if (bytes.length > 4*1024*1024) { throw new InvalidContentException("Docker metadata exceeds maximum size"); }
    var document = json.readTree(bytes);
    if (document == null || !document.isObject()) { throw new InvalidContentException("Docker metadata must be a JSON object"); }
    if (name != null && name.contains("/manifests/")) {
      String type = declared == null ? document.path("mediaType").asText("") : declared.split(";",2)[0].trim();
      if (!TYPES.contains(type)) { throw new InvalidContentException("Unsupported Docker manifest media type"); }
      return type;
    }
    return "application/json";
  }
}

/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.repository.npm.internal.search.v1;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
/** Response construction without the removed Elasticsearch backend. */
@Component
public class NpmSearchResponseFactory {
  public NpmSearchResponse buildEmptyResponse() { return buildResponseForObjects(List.of()); }
  public NpmSearchResponse buildResponseForObjects(List<NpmSearchResponseObject> objects) {
    NpmSearchResponse response = new NpmSearchResponse();
    response.setObjects(objects); response.setTime(Instant.now().toString()); response.setTotal(objects.size());
    return response;
  }
}

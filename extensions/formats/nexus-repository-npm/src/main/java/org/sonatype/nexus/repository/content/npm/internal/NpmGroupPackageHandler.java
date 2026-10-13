/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.repository.content.npm.internal;

import java.util.ArrayList;
import java.util.Map;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.repository.group.GroupFacet;
import org.sonatype.nexus.repository.group.GroupHandler;
import org.sonatype.nexus.repository.npm.internal.NpmJsonUtils;
import org.sonatype.nexus.repository.npm.internal.NpmMetadataUtils;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.springframework.stereotype.Component;

/** Merge ordered member packuments while retaining the first member's duplicate versions. */
@Component
public class NpmGroupPackageHandler extends GroupHandler {
  @Override
  protected Response doGet(Context context, DispatchedRepositories dispatched) throws Exception {
    var responses = getAll(context, context.getRepository().facet(GroupFacet.class).members(), dispatched);
    var roots = new ArrayList<NestedAttributesMap>();
    for (Response response : responses.values()) {
      if (response.getStatus().getCode() == 200 && response.getPayload() != null) {
        try (var input = response.getPayload().openInputStream()) {
          roots.add(NpmJsonUtils.parse(() -> input));
        }
      }
    }
    if (roots.isEmpty()) { return HttpResponses.notFound(); }
    java.util.Collections.reverse(roots);
    NestedAttributesMap merged = NpmMetadataUtils.merge("group", roots);
    Object versions = merged.backing().get("versions");
    if (versions instanceof Map<?, ?> entries) {
      for (Object entry : entries.values()) {
        if (entry instanceof Map<?, ?> version && version.get("dist") instanceof Map<?, ?> dist) {
          Object tarball = dist.get("tarball");
          if (tarball instanceof String location) {
            int marker = location.indexOf("/-/");
            if (marker >= 0) {
              String name = String.valueOf(version.containsKey("name") ? version.get("name") : merged.backing().get("name"));
              ((Map<String, Object>) dist).put("tarball", context.getRepository().getUrl() + "/" + name + location.substring(marker));
            }
          }
        }
      }
    }
    return HttpResponses.ok(new BytesPayload(NpmJsonUtils.bytes(merged), "application/json"));
  }
}

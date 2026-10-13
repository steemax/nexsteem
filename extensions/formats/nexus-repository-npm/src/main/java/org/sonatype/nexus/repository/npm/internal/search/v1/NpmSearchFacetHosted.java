/* SPDX-License-Identifier: EPL-1.0
 * Copyright (c) 2026 Nexus Extensions contributors.
 */
package org.sonatype.nexus.repository.npm.internal.search.v1;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.npm.NpmContentFacet;
import org.sonatype.nexus.repository.npm.internal.NpmPackageId;
import org.sonatype.nexus.repository.npm.internal.NpmVersionComparator;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Parameters;
import org.sonatype.nexus.repository.view.payloads.StringPayload;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
/** SQL-backed search; reads the current committed content store per request. */
@Component
@Scope("prototype")
public class NpmSearchFacetHosted extends FacetSupport implements NpmSearchFacet {
  private final NpmSearchParameterExtractor parameters;
  private final NpmSearchResponseFactory factory;
  private final NpmSearchResponseMapper mapper;
  public NpmSearchFacetHosted(NpmSearchParameterExtractor parameters,
      NpmSearchResponseFactory factory, NpmSearchResponseMapper mapper) {
    this.parameters = parameters; this.factory = factory; this.mapper = mapper;
  }
  @Override
  public Content searchV1(Parameters query) throws IOException {
    // The legacy extractor adds Elasticsearch wildcards. SQL browse matches the actual query text.
    String text = java.util.Objects.toString(query.get("text"), "").trim().toLowerCase(Locale.ROOT);
    Map<String, FluentComponent> latest = new LinkedHashMap<>();
    if (!text.isBlank()) {
      NpmContentFacet content = getRepository().facet(NpmContentFacet.class);
      String cursor = null;
      do {
        Continuation<FluentComponent> page = content.components().browse(1000, cursor);
        if (page.isEmpty()) { break; }
        for (FluentComponent component : page) {
          String name = new NpmPackageId(component.namespace().isEmpty() ? null : component.namespace(), component.name()).id();
          if (name.toLowerCase(Locale.ROOT).contains(text)) {
            FluentComponent previous = latest.get(name);
            if (previous == null || NpmVersionComparator.versionComparator.compare(component.version(), previous.version()) > 0) {
              latest.put(name, component);
            }
          }
        }
        cursor = page.nextContinuationToken();
      } while (cursor != null);
    }
    List<NpmSearchResponseObject> results = new ArrayList<>();
    for (var entry : latest.entrySet().stream().skip(parameters.extractFrom(query)).limit(parameters.extractSize(query)).toList()) {
      NpmSearchResponsePackage pkg = new NpmSearchResponsePackage();
      pkg.setName(entry.getKey()); pkg.setVersion(entry.getValue().version());
      pkg.setDescription(""); pkg.setKeywords(List.of()); pkg.setMaintainers(List.of());
      pkg.setDate(entry.getValue().created().toString());
      // npm's CLI expects arrays even when optional publication fields are absent.
      var stored = getRepository().facet(NpmContentFacet.class).get(NpmPackageId.parse(entry.getKey()));
      if (stored.isPresent()) {
        try (var stream = stored.get().openInputStream()) {
          var jackson = new com.fasterxml.jackson.databind.ObjectMapper();
          var root = jackson.readTree(stream);
          var metadata = root.path("versions").path(entry.getValue().version());
          pkg.setDescription(metadata.path("description").asText(""));
          if (metadata.path("keywords").isArray()) {
            List<String> keywords = new ArrayList<>(); metadata.path("keywords").forEach(value -> { if (value.isTextual()) { keywords.add(value.asText()); } });
            pkg.setKeywords(keywords);
          }
          var maintainers = root.path("maintainers");
          if (!maintainers.isArray()) { maintainers = metadata.path("maintainers"); }
          if (maintainers.isArray()) {
            List<NpmSearchResponsePerson> people = new ArrayList<>();
            maintainers.forEach(value -> {
              if (value.isObject()) {
                NpmSearchResponsePerson person = new NpmSearchResponsePerson();
                person.setUsername(value.path("username").asText(value.path("name").asText("")));
                person.setEmail(value.path("email").asText("")); people.add(person);
              }
            }); pkg.setMaintainers(people);
          }
          String published = root.path("time").path(entry.getValue().version()).asText("");
          if (!published.isBlank()) { pkg.setDate(published); }
        }
      }
      NpmSearchResponsePackageLinks links = new NpmSearchResponsePackageLinks();
      links.setNpm(getRepository().getUrl() + "/" + entry.getKey()); pkg.setLinks(links);
      NpmSearchResponseScoreDetail detail = new NpmSearchResponseScoreDetail();
      detail.setMaintenance(0.0); detail.setPopularity(0.0); detail.setQuality(0.0);
      NpmSearchResponseScore score = new NpmSearchResponseScore(); score.setFinalScore(1.0); score.setDetail(detail);
      NpmSearchResponseObject result = new NpmSearchResponseObject();
      result.setPackageEntry(pkg); result.setSearchScore(1.0); result.setScore(score); results.add(result);
    }
    NpmSearchResponse response = factory.buildResponseForObjects(results); response.setTotal(latest.size());
    return new Content(new StringPayload(mapper.writeString(response), "application/json"));
  }
}

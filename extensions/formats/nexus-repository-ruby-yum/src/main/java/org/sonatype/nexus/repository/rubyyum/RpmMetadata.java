/* SPDX-License-Identifier: Apache-2.0
 * Derived from kkRepo protocol codecs (klboke/kkrepo), commit
 * 9c2b87ca3851a180e929f8139803212846412a9c. Adapted for Nexus SQL/blob facets.
 * See META-INF/NOTICE-kkrepo.txt and LICENSE-kkrepo.txt.
 */
package org.sonatype.nexus.repository.rubyyum;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record RpmMetadata(
    String name,
    String version,
    String release,
    String epoch,
    String arch,
    String summary,
    String description,
    String url,
    String license,
    String vendor,
    String group,
    String buildHost,
    String sourceRpm,
    String packager,
    long buildTime,
    long installedSize,
    long archiveSize,
    long headerStart,
    long headerEnd,
    List<RpmDependency> provides,
    List<RpmDependency> requires,
    List<RpmDependency> conflicts,
    List<RpmDependency> obsoletes,
    List<RpmFile> files) {
  private static final String PREFIX = "yum.rpm.";

  static RpmMetadata fallback(YumCodec.RpmCoordinate coordinate, Instant lastUpdated) {
    long timestamp = lastUpdated == null ? Instant.now().getEpochSecond() : lastUpdated.getEpochSecond();
    return new RpmMetadata(
        coordinate.name(), coordinate.version(), coordinate.release(), "0", coordinate.arch(),
        coordinate.name(), coordinate.name(), "", "", "", "Unspecified", "", "", "kkrepo",
        timestamp, 0L, 0L, 0L, 0L, List.of(), List.of(), List.of(), List.of(), List.of());
  }

  Map<String, Object> toAttributes() {
    Map<String, Object> attrs = new LinkedHashMap<>();
    put(attrs, "name", name);
    put(attrs, "version", version);
    put(attrs, "release", release);
    put(attrs, "epoch", epoch);
    put(attrs, "arch", arch);
    put(attrs, "summary", summary);
    put(attrs, "description", description);
    put(attrs, "url", url);
    put(attrs, "license", license);
    put(attrs, "vendor", vendor);
    put(attrs, "group", group);
    put(attrs, "buildHost", buildHost);
    put(attrs, "sourceRpm", sourceRpm);
    put(attrs, "packager", packager);
    attrs.put(PREFIX + "buildTime", buildTime);
    attrs.put(PREFIX + "installedSize", installedSize);
    attrs.put(PREFIX + "archiveSize", archiveSize);
    attrs.put(PREFIX + "headerStart", headerStart);
    attrs.put(PREFIX + "headerEnd", headerEnd);
    attrs.put(PREFIX + "provides", dependenciesToAttributes(provides));
    attrs.put(PREFIX + "requires", dependenciesToAttributes(requires));
    attrs.put(PREFIX + "conflicts", dependenciesToAttributes(conflicts));
    attrs.put(PREFIX + "obsoletes", dependenciesToAttributes(obsoletes));
    attrs.put(PREFIX + "files", filesToAttributes(files));
    return attrs;
  }

  static RpmMetadata fromAttributes(Map<String, Object> attrs) {
    if (attrs == null || !attrs.containsKey(PREFIX + "name")) {
      return null;
    }
    return new RpmMetadata(
        string(attrs.get(PREFIX + "name")),
        string(attrs.get(PREFIX + "version")),
        string(attrs.get(PREFIX + "release")),
        defaultString(attrs.get(PREFIX + "epoch"), "0"),
        string(attrs.get(PREFIX + "arch")),
        string(attrs.get(PREFIX + "summary")),
        string(attrs.get(PREFIX + "description")),
        string(attrs.get(PREFIX + "url")),
        string(attrs.get(PREFIX + "license")),
        string(attrs.get(PREFIX + "vendor")),
        defaultString(attrs.get(PREFIX + "group"), "Unspecified"),
        string(attrs.get(PREFIX + "buildHost")),
        string(attrs.get(PREFIX + "sourceRpm")),
        string(attrs.get(PREFIX + "packager")),
        longValue(attrs.get(PREFIX + "buildTime")),
        longValue(attrs.get(PREFIX + "installedSize")),
        longValue(attrs.get(PREFIX + "archiveSize")),
        longValue(attrs.get(PREFIX + "headerStart")),
        longValue(attrs.get(PREFIX + "headerEnd")),
        dependenciesFromAttributes(attrs.get(PREFIX + "provides")),
        dependenciesFromAttributes(attrs.get(PREFIX + "requires")),
        dependenciesFromAttributes(attrs.get(PREFIX + "conflicts")),
        dependenciesFromAttributes(attrs.get(PREFIX + "obsoletes")),
        filesFromAttributes(attrs.get(PREFIX + "files")));
  }

  private static void put(Map<String, Object> attrs, String key, String value) {
    attrs.put(PREFIX + key, value == null ? "" : value);
  }

  private static List<Map<String, Object>> dependenciesToAttributes(List<RpmDependency> deps) {
    List<Map<String, Object>> values = new ArrayList<>();
    for (RpmDependency dep : deps == null ? List.<RpmDependency>of() : deps) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("name", dep.name());
      value.put("flags", dep.flags());
      value.put("epoch", dep.epoch());
      value.put("version", dep.version());
      value.put("release", dep.release());
      value.put("pre", dep.pre());
      values.add(value);
    }
    return values;
  }

  private static List<Map<String, Object>> filesToAttributes(List<RpmFile> files) {
    List<Map<String, Object>> values = new ArrayList<>();
    for (RpmFile file : files == null ? List.<RpmFile>of() : files) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("path", file.path());
      value.put("type", file.type());
      value.put("digest", file.digest());
      values.add(value);
    }
    return values;
  }

  private static List<RpmDependency> dependenciesFromAttributes(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<RpmDependency> deps = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) continue;
      deps.add(new RpmDependency(
          string(map.get("name")),
          string(map.get("flags")),
          string(map.get("epoch")),
          string(map.get("version")),
          string(map.get("release")),
          booleanValue(map.get("pre"))));
    }
    return deps;
  }

  private static List<RpmFile> filesFromAttributes(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<RpmFile> files = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> map)) continue;
      files.add(new RpmFile(string(map.get("path")), string(map.get("type")), string(map.get("digest"))));
    }
    return files;
  }

  private static String defaultString(Object value, String fallback) {
    String string = string(value);
    return string.isBlank() ? fallback : string;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static long longValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static boolean booleanValue(Object value) {
    return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
  }
}

record RpmDependency(String name, String flags, String epoch, String version, String release, boolean pre) {
}

record RpmFile(String path, String type, String digest) {
}

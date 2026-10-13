/* SPDX-License-Identifier: Apache-2.0
 * Derived from kkRepo protocol codecs (klboke/kkrepo), commit
 * 9c2b87ca3851a180e929f8139803212846412a9c. Adapted for Nexus SQL/blob facets.
 * See META-INF/NOTICE-kkrepo.txt and LICENSE-kkrepo.txt.
 */
package org.sonatype.nexus.repository.rubyyum;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class RubygemsCodec {
  static final String SPECS_PATH="specs.4.8.gz", LATEST_SPECS_PATH="latest_specs.4.8.gz", PRERELEASE_SPECS_PATH="prerelease_specs.4.8.gz";
  static List<GemMetadata> sorted(List<GemMetadata> gems) {
    return gems.stream()
        .sorted(Comparator.comparing(GemMetadata::name)
            .thenComparing(GemMetadata::version, RubygemsCodec::compareGemVersions)
            .thenComparing(GemMetadata::platform))
        .toList();
  }

  static Map<String, List<GemMetadata>> byName(List<GemMetadata> gems) {
    Map<String, List<GemMetadata>> byName = new TreeMap<>();
    for (GemMetadata gem : gems) {
      byName.computeIfAbsent(gem.name(), ignored -> new ArrayList<>()).add(gem);
    }
    byName.replaceAll((ignored, list) -> sorted(list));
    return byName;
  }

  static List<GemMetadata> latestSpecs(List<GemMetadata> gems) {
    Map<String, GemMetadata> latest = new TreeMap<>();
    for (GemMetadata gem : gems) {
      if (gem.version().matches(".*[A-Za-z].*")) continue;
      String key = gem.name() + "\n" + gem.platform();
      GemMetadata current = latest.get(key);
      if (current == null || compareGemVersions(gem.version(), current.version()) > 0) {
        latest.put(key, gem);
      }
    }
    return new ArrayList<>(latest.values());
  }

  static List<GemMetadata> deduplicate(List<GemMetadata> gems) {
    Map<String, GemMetadata> byCoordinate = new LinkedHashMap<>();
    for (GemMetadata gem : gems) {
      byCoordinate.put(gem.name() + "\n" + gem.version() + "\n" + gem.platform(), gem);
    }
    return new ArrayList<>(byCoordinate.values());
  }

  static Optional<GemMetadata> gemFromPath(String path) {
    String file = path.substring(path.lastIndexOf('/') + 1);
    if (!file.endsWith(".gem")) return Optional.empty();
    file = file.substring(0, file.length() - ".gem".length());
    int versionStart = -1;
    for (int i = 0; i < file.length() - 1; i++) {
      if (file.charAt(i) == '-' && Character.isDigit(file.charAt(i + 1))) {
        versionStart = i;
      }
    }
    if (versionStart <= 0) return Optional.empty();
    String name = file.substring(0, versionStart);
    String versionAndPlatform = file.substring(versionStart + 1);
    String platform = "ruby";
    String version = versionAndPlatform;
    int platformStart = versionAndPlatform.indexOf('-', firstNonVersionChar(versionAndPlatform));
    if (platformStart > 0) {
      version = versionAndPlatform.substring(0, platformStart);
      platform = versionAndPlatform.substring(platformStart + 1);
    }
    return Optional.of(new GemMetadata(name, version, platform, List.of()));
  }

  static int firstNonVersionChar(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (!(Character.isDigit(c) || c == '.')) return i;
    }
    return value.length();
  }

  static GemIdentity yankIdentity(String path) {
    int queryIndex = path.indexOf('?');
    if (queryIndex < 0 || queryIndex == path.length() - 1) return null;
    Map<String, String> query = query(path.substring(queryIndex + 1));
    String name = firstNonBlank(query.get("gem_name"), query.get("name"));
    String version = query.get("version");
    String platform = firstNonBlank(query.get("platform"), "ruby");
    return name == null || version == null ? null : new GemIdentity(name, version, platform);
  }

  static Map<String, String> query(String raw) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : raw.split("&")) {
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      result.put(decodeFormValue(key), decodeFormValue(value));
    }
    return result;
  }

  static Set<String> requestedGemNames(String fullPath) {
    int queryIndex = fullPath.indexOf('?');
    if (queryIndex < 0 || queryIndex == fullPath.length() - 1) return Set.of();
    String raw = query(fullPath.substring(queryIndex + 1)).get("gems");
    if (raw == null || raw.isBlank()) return Set.of();
    Set<String> names = new LinkedHashSet<>();
    for (String name : raw.split(",")) {
      String decoded = name.trim(); // query() already decoded this parameter once.
      if (!decoded.isBlank()) names.add(decoded);
    }
    return names;
  }

  static GemMetadata readGemMetadata(Path gem) throws IOException {
    try (InputStream raw = Files.newInputStream(gem);
         TarArchiveInputStream tar = new TarArchiveInputStream(raw)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextTarEntry()) != null) {
        if (!entry.isDirectory() && "metadata.gz".equals(entry.getName())) {
          try (GzipCompressorInputStream gzip = new GzipCompressorInputStream(tar)) {
            return parseMetadataYaml(new String(boundedMetadata(gzip), StandardCharsets.UTF_8));
          }
        }
      }
    }
    throw new IllegalArgumentException("RubyGems package is missing metadata.gz");
  }

  static GemMetadata parseMetadataYaml(String yaml) {
    var options=new org.yaml.snakeyaml.LoaderOptions();
    options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(20);
    options.setNestingDepthLimit(50); options.setCodePointLimit(4*1024*1024);
    // Ruby tags describe data maps only. SafeConstructor never instantiates the
    // tagged classes, including any unknown/non-Ruby object tags.
    String data=yaml;
    Object parsed; try {parsed=new org.yaml.snakeyaml.Yaml(new RubySafeConstructor(options)).load(data);} catch(org.yaml.snakeyaml.error.YAMLException invalid) {throw new IllegalArgumentException("Invalid gem YAML",invalid);}
    if(!(parsed instanceof Map<?,?> map))throw new IllegalArgumentException("Invalid gem YAML root");
    String name=yamlString(map.get("name")),version=yamlVersion(map.get("version"));
    Object rawPlatform=map.get("platform"); String platform;
    if(rawPlatform instanceof Map<?,?> value) {
      var parts=new ArrayList<String>(); for(String key:List.of("cpu","os","version"))if(value.get(key)!=null&&!value.get(key).toString().isBlank())parts.add(yamlString(value.get(key)));
      platform=String.join("-",parts);
    } else platform=rawPlatform==null?"ruby":yamlString(rawPlatform);
    if(platform.isBlank())platform="ruby";
    var dependencies=new ArrayList<GemDependency>(); Object rawDependencies=map.get("dependencies");
    if(rawDependencies instanceof List<?> list)for(Object item:list) {
      if(!(item instanceof Map<?,?> dependency))throw new IllegalArgumentException("Invalid gem dependency");
      String type=dependency.get("type")==null?":runtime":yamlString(dependency.get("type")); if(type.equals(":development")||type.equals("development"))continue;
      dependencies.add(new GemDependency(yamlString(dependency.get("name")),yamlRequirements(dependency.get("requirement"))));
    }
    var authors=new ArrayList<String>();if(map.get("authors")instanceof List<?> list)for(Object author:list)authors.add(yamlString(author));
    String summary=map.get("summary")==null?"":yamlString(map.get("summary"));
    return new GemMetadata(name,version,platform,dependencies,"",summary,authors,yamlRequirements(map.get("required_ruby_version")),yamlRequirements(map.get("required_rubygems_version")));
  }

  private static final class RubySafeConstructor extends org.yaml.snakeyaml.constructor.SafeConstructor {
    RubySafeConstructor(org.yaml.snakeyaml.LoaderOptions options) {
      super(options);
      for(String type:List.of("Gem::Specification","Gem::Version","Gem::Requirement","Gem::Dependency","Gem::Platform"))
        yamlConstructors.put(new org.yaml.snakeyaml.nodes.Tag("!ruby/object:"+type),new ConstructYamlMap());
      yamlConstructors.put(new org.yaml.snakeyaml.nodes.Tag("!ruby/symbol"),new ConstructYamlStr());
    }
  }

  static String yamlString(Object value) {if(!(value instanceof String)&&!(value instanceof Number)&&!(value instanceof Boolean))throw new IllegalArgumentException("Expected scalar gem metadata");return value.toString();}

  static String yamlVersion(Object value) {
    if(value instanceof Map<?,?> map)value=map.get("version");
    String version=yamlString(value);if(!version.matches("[0-9][A-Za-z0-9_.-]{0,254}"))throw new IllegalArgumentException("Invalid gem version");return version;
  }

  static List<String> yamlRequirements(Object value) {
    if(!(value instanceof Map<?,?> map)||!(map.get("requirements")instanceof List<?> requirements))return List.of(">= 0");
    var result=new ArrayList<String>();for(Object item:requirements) {
      if(!(item instanceof List<?> pair)||pair.size()!=2)throw new IllegalArgumentException("Invalid gem version requirement");
      String operator=String.valueOf(pair.get(0)); if(!List.of("=",">",">=","<","<=","~>","!=").contains(operator))throw new IllegalArgumentException("Invalid gem requirement operator");
      result.add(operator+" "+yamlVersion(pair.get(1)));
    }
    return result.isEmpty()?List.of(">= 0"):List.copyOf(result);
  }

  static String parseNestedVersion(String yaml) {
    boolean inVersion = false;
    for (String rawLine : yaml.split("\\R")) {
      String trimmed = rawLine.trim();
      if (trimmed.equals("version: !ruby/object:Gem::Version")) {
        inVersion = true;
        continue;
      }
      if (inVersion && trimmed.startsWith("version:")) {
        return cleanYaml(trimmed.substring("version:".length()));
      }
      if (inVersion && !trimmed.isBlank() && !trimmed.startsWith("version:")) {
        inVersion = false;
      }
    }
    return null;
  }

  static byte[] rubyMarshalSpecs(List<GemMetadata> gems) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {4, 8, '['});
    writeFixnum(out, gems.size());
    for (GemMetadata gem : gems) {
      out.write('[');
      writeFixnum(out, 3);
      writeRubyString(out, gem.name());
      writeGemVersion(out, gem.version());
      writeRubyString(out, gem.platform());
    }
    return out.toByteArray();
  }

  static byte[] rubyMarshalGemSpecification(GemMetadata gem) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes(new byte[] {4, 8});
    out.write('u');
    writeSymbol(out, "Gem::Specification");
    ByteArrayOutputStream payload = new ByteArrayOutputStream();
    payload.writeBytes(new byte[] {4, 8, '['});
    writeFixnum(payload, 19);
    writeRubyString(payload, "3.0.6");
    writeRubyInteger(payload, 4);
    writeRubyString(payload, gem.name());
    writeGemVersion(payload, gem.version());
    writeRubyTime(payload);
    writeRubyString(payload, gem.effectiveSummary());
    writeGemRequirement(payload, gem.requiredRuby());
    writeGemRequirement(payload, gem.requiredRubygems());
    writeRubyString(payload, gem.platform());
    payload.write('[');
    writeFixnum(payload, gem.dependencies().size());
    for (GemDependency dependency : gem.dependencies()) {
      writeGemDependency(payload, dependency);
    }
    writeRubyString(payload, "");
    writeRubyNil(payload);
    writeRubyStringArray(payload, gem.authors());
    writeRubyNil(payload);
    writeRubyNil(payload);
    payload.write('T');
    writeRubyString(payload, gem.platform());
    payload.write('[');
    writeFixnum(payload, 0);
    payload.write('{');
    writeFixnum(payload, 0);
    byte[] payloadBytes = payload.toByteArray();
    writeFixnum(out, payloadBytes.length);
    out.writeBytes(payloadBytes);
    return out.toByteArray();
  }

  @SuppressWarnings("unchecked")
  static void writeObject(ByteArrayOutputStream out, Object value) {
    if (value instanceof String s) {
      writeRubyString(out, s);
    } else if (value instanceof List<?> list) {
      out.write('[');
      writeFixnum(out, list.size());
      for (Object item : list) {
        if (item instanceof GemDependency dep) {
          out.write('[');
          writeFixnum(out, dep.requirements().size() + 1);
          writeRubyString(out, dep.name());
          for (String requirement : dep.requirements()) {
            writeRubyString(out, requirement);
          }
        } else if (item instanceof List<?> nested) {
          writeObject(out, nested);
        } else {
          writeObject(out, String.valueOf(item));
        }
      }
    } else if (value instanceof Map<?, ?> map) {
      writeHash(out, (Map<String, Object>) map);
    } else {
      writeRubyString(out, String.valueOf(value));
    }
  }

  static void writeHash(ByteArrayOutputStream out, Map<String, Object> map) {
    out.write('{');
    writeFixnum(out, map.size());
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      writeSymbol(out, entry.getKey());
      writeObject(out, entry.getValue());
    }
  }

  static void writeGemVersion(ByteArrayOutputStream out, String version) {
    out.write('U');
    writeSymbol(out, "Gem::Version");
    out.write('[');
    writeFixnum(out, 1);
    writeRubyString(out, version);
  }

  static void writeGemRequirement(ByteArrayOutputStream out, String requirement) {
    writeGemRequirement(out, List.of(requirement));
  }

  static void writeGemRequirement(ByteArrayOutputStream out, List<String> requirements) {
    out.write('U');
    writeSymbol(out, "Gem::Requirement");
    List<String> effective = requirements == null || requirements.isEmpty() ? List.of(">= 0") : requirements;
    out.write('[');
    writeFixnum(out, 1);
    out.write('[');
    writeFixnum(out, effective.size());
    for (String requirement : effective) {
      String[] parts = splitRequirement(requirement);
      out.write('[');
      writeFixnum(out, 2);
      writeRubyString(out, parts[0]);
      writeGemVersion(out, parts[1]);
    }
  }

  static void writeGemDependency(ByteArrayOutputStream out, GemDependency dependency) {
    out.write('o');
    writeSymbol(out, "Gem::Dependency");
    writeFixnum(out, 5);
    writeSymbol(out, "@name");
    writeRubyString(out, dependency.name());
    writeSymbol(out, "@requirement");
    writeGemRequirement(out, dependency.requirements());
    writeSymbol(out, "@type");
    writeSymbol(out, "runtime");
    writeSymbol(out, "@prerelease");
    out.write('F');
    writeSymbol(out, "@version_requirements");
    writeGemRequirement(out, dependency.requirements());
  }

  static String[] splitRequirement(String requirement) {
    String value = requirement == null || requirement.isBlank() ? ">= 0" : requirement.trim();
    int versionStart = 0;
    while (versionStart < value.length() && !Character.isDigit(value.charAt(versionStart))) {
      versionStart++;
    }
    if (versionStart <= 0 || versionStart >= value.length()) {
      return new String[] {">=", "0"};
    }
    String operator = value.substring(0, versionStart).trim();
    String version = value.substring(versionStart).trim();
    if (operator.isBlank() || version.isBlank()) {
      return new String[] {">=", "0"};
    }
    return new String[] {operator, version};
  }

  static void writeRubyTime(ByteArrayOutputStream out) {
    out.write('I');
    out.write('u');
    writeSymbol(out, "Time");
    out.writeBytes(new byte[] {
        13, 32, (byte) 0x95, 31, (byte) 0xc0, 0, 0, 0, 0, 6
    });
    writeSymbol(out, "zone");
    writeRubyString(out, "UTC");
  }

  static void writeRubyString(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.write('I');
    out.write('"');
    writeFixnum(out, bytes.length);
    out.writeBytes(bytes);
    writeFixnum(out, 1);
    writeSymbol(out, "E");
    out.write('T');
  }

  static void writeRubyStringArray(ByteArrayOutputStream out, List<String> values) {
    out.write('[');
    writeFixnum(out, values.size());
    for (String value : values) {
      writeRubyString(out, value);
    }
  }

  static void writeRubyNil(ByteArrayOutputStream out) {
    out.write('0');
  }

  static void writeSymbol(ByteArrayOutputStream out, String value) {
    out.write(':');
    writeRubySymbol(out, value);
  }

  static void writeRubyInteger(ByteArrayOutputStream out, int value) {
    out.write('i');
    writeFixnum(out, value);
  }

  static void writeRubySymbol(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    writeFixnum(out, bytes.length);
    out.writeBytes(bytes);
  }

  static void writeFixnum(ByteArrayOutputStream out, int value) {
    if (value == 0) {
      out.write(0);
    } else if (value > 0 && value < 123) {
      out.write(value + 5);
    } else if (value < 0 && value > -124) {
      out.write((256 + value - 5) & 0xff);
    } else {
      int bytes = value;
      int count = 0;
      while (bytes != 0 && bytes != -1) {
        count++;
        bytes >>= 8;
      }
      out.write(count);
      for (int i = 0; i < count; i++) {
        out.write((value >> (8 * i)) & 0xff);
      }
    }
  }

  static byte[] gzip(byte[] payload) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
        gzip.write(payload);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to gzip RubyGems index", e);
    }
  }

  static byte[] deflateLikeRuby(byte[] payload) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
        deflater.write(payload);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to deflate RubyGems quick spec", e);
    }
  }

  static String gemPath(GemMetadata metadata) {
    return gemPath(metadata.name(), metadata.version(), metadata.platform());
  }

  static String gemPath(String name, String version, String platform) {
    String file = name + "-" + version;
    if (platform != null && !platform.isBlank() && !"ruby".equals(platform)) {
      file += "-" + platform;
    }
    return "gems/" + file + ".gem";
  }

  static String specsPath(SpecMode mode) {
    return switch (mode) {
      case ALL -> SPECS_PATH;
      case LATEST -> LATEST_SPECS_PATH;
      case PRERELEASE -> PRERELEASE_SPECS_PATH;
    };
  }

  static String infoPath(String name) {
    return "info/" + name;
  }

  static String quickSpecPath(String fullName) {
    return "quick/Marshal.4.8/" + fullName + ".gemspec.rz";
  }

  static String fullName(GemMetadata metadata) {
    String path = gemPath(metadata);
    return path.substring("gems/".length(), path.length() - ".gem".length());
  }

  static String fullName(GemIdentity identity) {
    String path = gemPath(identity.name(), identity.version(), identity.platform());
    return path.substring("gems/".length(), path.length() - ".gem".length());
  }

  static String dependenciesAttr(List<GemDependency> dependencies) {
    StringBuilder out = new StringBuilder();
    for (GemDependency dep : dependencies) {
      if (!out.isEmpty()) out.append('\n');
      out.append(dep.name()).append('|');
      for (int i = 0; i < dep.requirements().size(); i++) {
        if (i > 0) out.append(',');
        out.append(dep.requirements().get(i));
      }
    }
    return out.toString();
  }

  static String authorsAttr(List<String> authors) {
    return String.join("\n", authors);
  }

  static List<String> authorsFromAttr(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return List.of(raw.split("\\R")).stream()
        .filter(author -> !author.isBlank())
        .toList();
  }

  static List<GemDependency> dependenciesFromAttr(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    List<GemDependency> deps = new ArrayList<>();
    for (String line : raw.split("\\R")) {
      int sep = line.indexOf('|');
      if (sep <= 0) continue;
      String name = line.substring(0, sep);
      List<String> requirements = List.of(line.substring(sep + 1).split(",")).stream()
          .filter(s -> !s.isBlank())
          .toList();
      deps.add(new GemDependency(name, requirements));
    }
    return deps;
  }

  static String compactDependencies(List<GemDependency> dependencies) {
    StringBuilder out = new StringBuilder();
    for (GemDependency dep : dependencies) {
      if (!out.isEmpty()) out.append(',');
      out.append(dep.name());
      if (!dep.requirements().isEmpty()) {
        out.append(':');
        for (int i = 0; i < dep.requirements().size(); i++) {
          if (i > 0) out.append('&');
          out.append(dep.requirements().get(i));
        }
      }
    }
    return out.toString();
  }

  static List<Object> dependencyApiDependencies(List<GemDependency> dependencies) {
    var result=new ArrayList<Object>();
    if(dependencies!=null)for(var dependency:dependencies)result.add(List.of(dependency.name(),dependency.requirements()));
    return result;
  }

  static String dependencyApiRequirement(List<String> requirements) {
    if (requirements == null || requirements.isEmpty()) {
      return ">= 0";
    }
    return String.join(", ", requirements);
  }

  static int compareGemVersions(String left, String right) {
    List<String> l = versionParts(left);
    List<String> r = versionParts(right);
    int max = Math.max(l.size(), r.size());
    for (int i = 0; i < max; i++) {
      String a = i < l.size() ? l.get(i) : "0";
      String b = i < r.size() ? r.get(i) : "0";
      int cmp;
      if (a.chars().allMatch(Character::isDigit) && b.chars().allMatch(Character::isDigit)) {
        cmp = new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
      } else {
        cmp = a.compareToIgnoreCase(b);
      }
      if (cmp != 0) return cmp;
    }
    return 0;
  }

  static List<String> versionParts(String version) {
    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = 0; i < version.length(); i++) {
      char c = version.charAt(i);
      if (Character.isLetterOrDigit(c)) {
        current.append(c);
      } else if (!current.isEmpty()) {
        parts.add(current.toString());
        current.setLength(0);
      }
    }
    if (!current.isEmpty()) parts.add(current.toString());
    return parts;
  }

  static String cleanYaml(String value) {
    String cleaned = value == null ? "" : value.trim();
    while (cleaned.startsWith("-")) cleaned = cleaned.substring(1).trim();
    if ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
        || (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
      cleaned = cleaned.substring(1, cleaned.length() - 1);
    }
    return cleaned;
  }

  static String stringAttr(Map<String, Object> attrs, String key) {
    Object v = attrs == null ? null : attrs.get(key);
    return v == null ? null : v.toString();
  }

  static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  static String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  static String md5(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing MD5", e);
    }
  }

  enum SpecMode {
    ALL,
    LATEST,
    PRERELEASE
  }

  record GemMetadata(
      String name,
      String version,
      String platform,
      List<GemDependency> dependencies,
      String sha256,
      String summary,
      List<String> authors,
      List<String> requiredRuby,
      List<String> requiredRubygems) {
    GemMetadata {
      dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
      sha256 = sha256 == null ? "" : sha256;
      summary = summary == null ? "" : summary;
      authors = authors == null ? List.of() : List.copyOf(authors);
      requiredRuby=requiredRuby==null?List.of(">= 0"):List.copyOf(requiredRuby);
      requiredRubygems=requiredRubygems==null?List.of(">= 0"):List.copyOf(requiredRubygems);
    }

    GemMetadata(String name,String version,String platform,List<GemDependency> dependencies,String sha256,String summary,List<String> authors) {
      this(name,version,platform,dependencies,sha256,summary,authors,List.of(">= 0"),List.of(">= 0"));
    }

    GemMetadata(String name, String version, String platform, List<GemDependency> dependencies) {
      this(name, version, platform, dependencies, "", "", List.of());
    }

    GemMetadata(
        String name,
        String version,
        String platform,
        List<GemDependency> dependencies,
        String sha256) {
      this(name, version, platform, dependencies, sha256, "", List.of());
    }

    GemMetadata withSha256(String sha256) {
      return new GemMetadata(name, version, platform, dependencies, sha256, summary, authors,requiredRuby,requiredRubygems);
    }

    String effectiveSummary() {
      return summary == null || summary.isBlank() ? name : summary;
    }
  }

  record GemDependency(String name, List<String> requirements) {
  }

  record GemIdentity(String name, String version, String platform) {
  }

  static byte[] boundedMetadata(InputStream input) throws IOException { byte[] result=input.readNBytes(4*1024*1024+1); if(result.length>4*1024*1024)throw new IllegalArgumentException("Gem metadata exceeds limit"); return result; }
  static String compactVersionsBody(Map<String, List<GemMetadata>> byName) {
    StringBuilder body = new StringBuilder();
    body.append("created_at: ").append(System.currentTimeMillis() / 1000L).append('\n');
    body.append("---\n");
    for (Map.Entry<String, List<GemMetadata>> entry : byName.entrySet()) {
      body.append(entry.getKey()).append(' ');
      String info = compactInfoBody(entry.getValue());
      for (int i = 0; i < entry.getValue().size(); i++) {
        if (i > 0) body.append(',');
        GemMetadata gem = entry.getValue().get(i);
        body.append(gem.version());
        if (!"ruby".equals(gem.platform())) {
          body.append('-').append(gem.platform());
        }
      }
      body.append(' ').append(md5(info.getBytes(StandardCharsets.UTF_8))).append('\n');
    }
    return body.toString();
  }

  static String compactInfoBody(List<GemMetadata> gems) {
    StringBuilder body = new StringBuilder();
    body.append("---\n");
    for (GemMetadata gem : gems) {
      body.append(gem.version());
      if (!"ruby".equals(gem.platform())) {
        body.append('-').append(gem.platform());
      }
      if (!gem.dependencies().isEmpty()) {
        body.append(' ').append(compactDependencies(gem.dependencies()));
      }
      if (gem.sha256() != null && !gem.sha256().isBlank()) {
        if (gem.dependencies().isEmpty()) {
          body.append(' ');
        }
        body.append("|checksum:").append(gem.sha256());
        body.append(",ruby:").append(String.join("&",gem.requiredRuby())).append(",rubygems:").append(String.join("&",gem.requiredRubygems()));
      }
      body.append('\n');
    }
    return body.toString();
  }


  static String decodeFormValue(String value) {return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);}
}

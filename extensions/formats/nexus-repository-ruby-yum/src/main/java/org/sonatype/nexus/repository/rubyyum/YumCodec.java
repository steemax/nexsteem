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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

final class YumCodec {
  static String primaryXml(List<RpmAsset> rpms) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<metadata xmlns=\"http://linux.duke.edu/metadata/common\" packages=\"")
        .append(rpms.size()).append("\">\n");
    for (RpmAsset rpm : rpms) {
      xml.append("  <package type=\"rpm\">\n");
      RpmMetadata meta = rpm.metadata();
      xml.append("    <name>").append(escape(meta.name())).append("</name>\n");
      xml.append("    <arch>").append(escape(meta.arch())).append("</arch>\n");
      appendVersion(xml, meta);
      xml.append("    <checksum type=\"sha256\" pkgid=\"YES\">").append(rpm.sha256()).append("</checksum>\n");
      xml.append("    <summary>").append(escape(defaultString(meta.summary(), meta.name()))).append("</summary>\n");
      xml.append("    <description>").append(escape(defaultString(meta.description(), meta.summary()))).append("</description>\n");
      xml.append("    <packager>").append(escape(defaultString(meta.packager(), "Nexus Open"))).append("</packager>\n");
      xml.append("    <url>").append(escape(meta.url())).append("</url>\n");
      xml.append("    <time file=\"").append(rpm.unixTime()).append("\" build=\"")
          .append(meta.buildTime() > 0 ? meta.buildTime() : rpm.unixTime()).append("\"/>\n");
      xml.append("    <size package=\"").append(rpm.size()).append("\" installed=\"")
          .append(meta.installedSize()).append("\" archive=\"").append(meta.archiveSize()).append("\"/>\n");
      xml.append("    <location href=\"").append(escapeAttr(rpm.path())).append("\"/>\n");
      xml.append("    <format xmlns:rpm=\"http://linux.duke.edu/metadata/rpm\">\n");
      xml.append("      <rpm:license>").append(escape(meta.license())).append("</rpm:license>\n");
      xml.append("      <rpm:vendor>").append(escape(meta.vendor())).append("</rpm:vendor>\n");
      xml.append("      <rpm:group>").append(escape(defaultString(meta.group(), "Unspecified"))).append("</rpm:group>\n");
      xml.append("      <rpm:buildhost>").append(escape(meta.buildHost())).append("</rpm:buildhost>\n");
      xml.append("      <rpm:sourcerpm>").append(escape(meta.sourceRpm())).append("</rpm:sourcerpm>\n");
      xml.append("      <rpm:header-range start=\"").append(meta.headerStart()).append("\" end=\"")
          .append(meta.headerEnd()).append("\"/>\n");
      appendDependencies(xml, "rpm:provides", meta.provides());
      appendDependencies(xml, "rpm:conflicts", meta.conflicts());
      appendDependencies(xml, "rpm:obsoletes", meta.obsoletes());
      appendDependencies(xml, "rpm:requires", meta.requires());
      appendFiles(xml, meta.files(), true);
      xml.append("    </format>\n");
      xml.append("  </package>\n");
    }
    xml.append("</metadata>\n");
    return xml.toString();
  }

  static String filelistsXml(List<RpmAsset> rpms) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<filelists xmlns=\"http://linux.duke.edu/metadata/filelists\" packages=\"")
        .append(rpms.size()).append("\">\n");
    for (RpmAsset rpm : rpms) {
      xml.append("  <package pkgid=\"").append(rpm.sha256()).append("\" name=\"")
          .append(escapeAttr(rpm.metadata().name())).append("\" arch=\"")
          .append(escapeAttr(rpm.metadata().arch())).append("\">\n");
      appendVersion(xml, rpm.metadata());
      appendFiles(xml, rpm.metadata().files(), false);
      xml.append("  </package>\n");
    }
    xml.append("</filelists>\n");
    return xml.toString();
  }

  static String otherXml(List<RpmAsset> rpms) {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<otherdata xmlns=\"http://linux.duke.edu/metadata/other\" packages=\"")
        .append(rpms.size()).append("\">\n");
    for (RpmAsset rpm : rpms) {
      xml.append("  <package pkgid=\"").append(rpm.sha256()).append("\" name=\"")
          .append(escapeAttr(rpm.metadata().name())).append("\" arch=\"")
          .append(escapeAttr(rpm.metadata().arch())).append("\">\n");
      appendVersion(xml, rpm.metadata());
      xml.append("  </package>\n");
    }
    xml.append("</otherdata>\n");
    return xml.toString();
  }

  static String repomdXml(List<RepomdEntry> entries) {
    long now = Instant.now().getEpochSecond();
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<repomd xmlns=\"http://linux.duke.edu/metadata/repo\">\n");
    for (RepomdEntry entry : entries) {
      MetadataFile file = entry.file();
      xml.append("  <data type=\"").append(entry.type()).append("\">\n");
      xml.append("    <checksum type=\"sha256\">").append(file.sha256()).append("</checksum>\n");
      xml.append("    <open-checksum type=\"sha256\">").append(file.openSha256()).append("</open-checksum>\n");
      xml.append("    <location href=\"").append(entry.href()).append("\"/>\n");
      xml.append("    <timestamp>").append(now).append("</timestamp>\n");
      xml.append("    <size>").append(file.bytes().length).append("</size>\n");
      xml.append("    <open-size>").append(file.openSize()).append("</open-size>\n");
      xml.append("  </data>\n");
    }
    xml.append("</repomd>\n");
    return xml.toString();
  }

  static MetadataFile gzipMetadata(String xml) {
    byte[] open = xml.getBytes(StandardCharsets.UTF_8);
    byte[] gz;
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
        gzip.write(open);
      }
      gz = out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("Failed to gzip Yum metadata", e);
    }
    return new MetadataFile(sha256(gz) + "-" + kindName(xml) + ".xml.gz",
        gz, sha256(gz), sha256(open), open.length);
  }

  static String kindName(String xml) {
    if (xml.contains("<metadata ")) return "primary";
    if (xml.contains("<filelists ")) return "filelists";
    return "other";
  }

  static RpmCoordinate rpmCoordinate(String path) {
    String file = path.substring(path.lastIndexOf('/') + 1);
    if (file.endsWith(".rpm")) file = file.substring(0, file.length() - 4);
    int archSep = file.lastIndexOf('.');
    String arch = archSep > 0 && archSep < file.length() - 1 ? file.substring(archSep + 1) : "noarch";
    String nameVersionRelease = archSep > 0 ? file.substring(0, archSep) : file;
    int releaseSep = nameVersionRelease.lastIndexOf('-');
    int versionSep = releaseSep <= 0 ? -1 : nameVersionRelease.lastIndexOf('-', releaseSep - 1);
    if (versionSep <= 0 || releaseSep <= versionSep) {
      return new RpmCoordinate(file, "0", "0", arch);
    }
    return new RpmCoordinate(
        nameVersionRelease.substring(0, versionSep),
        nameVersionRelease.substring(versionSep + 1, releaseSep),
        nameVersionRelease.substring(releaseSep + 1),
        arch);
  }

  static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  static void appendVersion(StringBuilder xml, RpmMetadata meta) {
    xml.append("    <version epoch=\"").append(escapeAttr(defaultString(meta.epoch(), "0")))
        .append("\" ver=\"").append(escapeAttr(meta.version()))
        .append("\" rel=\"").append(escapeAttr(meta.release())).append("\"/>\n");
  }

  static void appendDependencies(StringBuilder xml, String element, List<RpmDependency> dependencies) {
    if (dependencies == null || dependencies.isEmpty()) {
      return;
    }
    xml.append("      <").append(element).append(">\n");
    for (RpmDependency dependency : dependencies) {
      if (dependency.name() == null || dependency.name().isBlank()) continue;
      xml.append("        <rpm:entry name=\"").append(escapeAttr(dependency.name())).append("\"");
      if (dependency.flags() != null && !dependency.flags().isBlank()) {
        xml.append(" flags=\"").append(escapeAttr(dependency.flags())).append("\"");
        if (dependency.epoch() != null && !dependency.epoch().isBlank()) {
          xml.append(" epoch=\"").append(escapeAttr(dependency.epoch())).append("\"");
        }
        if (dependency.version() != null && !dependency.version().isBlank()) {
          xml.append(" ver=\"").append(escapeAttr(dependency.version())).append("\"");
        }
        if (dependency.release() != null && !dependency.release().isBlank()) {
          xml.append(" rel=\"").append(escapeAttr(dependency.release())).append("\"");
        }
      }
      if (dependency.pre()) {
        xml.append(" pre=\"1\"");
      }
      xml.append("/>\n");
    }
    xml.append("      </").append(element).append(">\n");
  }

  static void appendFiles(StringBuilder xml, List<RpmFile> files, boolean primaryOnly) {
    if (files == null || files.isEmpty()) {
      return;
    }
    for (RpmFile file : files) {
      if (file.path() == null || file.path().isBlank()) continue;
      if (primaryOnly && !RpmMetadataParser.isPrimaryFile(file.path())) continue;
      xml.append(primaryOnly ? "      " : "    ");
      xml.append("<file");
      if (file.type() != null && !file.type().isBlank() && !"file".equals(file.type())) {
        xml.append(" type=\"").append(escapeAttr(file.type())).append("\"");
      }
      xml.append(">").append(escape(file.path())).append("</file>\n");
    }
  }

  static String escape(String value) {
    if (value == null) return "";
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;");
  }

  static String escapeAttr(String value) {
    return escape(value).replace("\"", "&quot;");
  }

  static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Missing SHA-256", e);
    }
  }

  record RpmAsset(
      String path,
      RpmMetadata metadata,
      String sha256,
      long size,
      Instant lastUpdated) {
    long unixTime() {
      return lastUpdated.getEpochSecond();
    }
  }

  record RpmCoordinate(String name, String version, String release, String arch) {
  }

  record MetadataFile(
      String fileName,
      byte[] bytes,
      String sha256,
      String openSha256,
      long openSize) {
  }

  record RepomdEntry(String type, String href, MetadataFile file) {
  }
}

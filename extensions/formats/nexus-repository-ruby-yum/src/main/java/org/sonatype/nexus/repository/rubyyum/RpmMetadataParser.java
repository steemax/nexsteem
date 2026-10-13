/* SPDX-License-Identifier: Apache-2.0
 * Derived from kkRepo protocol codecs (klboke/kkrepo), commit
 * 9c2b87ca3851a180e929f8139803212846412a9c. Adapted for Nexus SQL/blob facets.
 * See META-INF/NOTICE-kkrepo.txt and LICENSE-kkrepo.txt.
 */
package org.sonatype.nexus.repository.rubyyum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RpmMetadataParser {
  private static final int LEAD_SIZE = 96;
  private static final int HEADER_PREFIX_SIZE = 16;
  private static final int INDEX_ENTRY_SIZE = 16;
  private static final int LEAD_MAGIC = 0xedabeedb;
  private static final int HEADER_MAGIC = 0x8eade801;

  private static final int TYPE_CHAR = 1;
  private static final int TYPE_INT8 = 2;
  private static final int TYPE_INT16 = 3;
  private static final int TYPE_INT32 = 4;
  private static final int TYPE_INT64 = 5;
  private static final int TYPE_STRING = 6;
  private static final int TYPE_BIN = 7;
  private static final int TYPE_STRING_ARRAY = 8;
  private static final int TYPE_I18NSTRING = 9;

  private static final int RPMTAG_NAME = 1000;
  private static final int RPMTAG_VERSION = 1001;
  private static final int RPMTAG_RELEASE = 1002;
  private static final int RPMTAG_EPOCH = 1003;
  private static final int RPMTAG_SUMMARY = 1004;
  private static final int RPMTAG_DESCRIPTION = 1005;
  private static final int RPMTAG_BUILDTIME = 1006;
  private static final int RPMTAG_BUILDHOST = 1007;
  private static final int RPMTAG_SIZE = 1009;
  private static final int RPMTAG_VENDOR = 1011;
  private static final int RPMTAG_LICENSE = 1014;
  private static final int RPMTAG_PACKAGER = 1015;
  private static final int RPMTAG_GROUP = 1016;
  private static final int RPMTAG_URL = 1020;
  private static final int RPMTAG_ARCH = 1022;
  private static final int RPMTAG_OLDFILENAMES = 1027;
  private static final int RPMTAG_FILEMODES = 1030;
  private static final int RPMTAG_FILEDIGESTS = 1035;
  private static final int RPMTAG_FILEFLAGS = 1037;
  private static final int RPMTAG_SOURCERPM = 1044;
  private static final int RPMTAG_ARCHIVESIZE = 1046;
  private static final int RPMTAG_PROVIDENAME = 1047;
  private static final int RPMTAG_REQUIREFLAGS = 1048;
  private static final int RPMTAG_REQUIRENAME = 1049;
  private static final int RPMTAG_REQUIREVERSION = 1050;
  private static final int RPMTAG_CONFLICTFLAGS = 1053;
  private static final int RPMTAG_CONFLICTNAME = 1054;
  private static final int RPMTAG_CONFLICTVERSION = 1055;
  private static final int RPMTAG_OBSOLETENAME = 1090;
  private static final int RPMTAG_PROVIDEFLAGS = 1112;
  private static final int RPMTAG_PROVIDEVERSION = 1113;
  private static final int RPMTAG_OBSOLETEFLAGS = 1114;
  private static final int RPMTAG_OBSOLETEVERSION = 1115;
  private static final int RPMTAG_DIRINDEXES = 1116;
  private static final int RPMTAG_BASENAMES = 1117;
  private static final int RPMTAG_DIRNAMES = 1118;
  private static final int RPMTAG_FILEDIGESTALGO = 5011;
  private static final int RPMTAG_LONGARCHIVESIZE = 271;
  private static final int RPMTAG_LONGSIZE = 5009;

  private static final int RPMFILE_GHOST = 1 << 6;
  private static final int MODE_DIRECTORY = 0040000;

  private static final int RPMSENSE_LESS = 1 << 1;
  private static final int RPMSENSE_GREATER = 1 << 2;
  private static final int RPMSENSE_EQUAL = 1 << 3;
  private static final int RPMSENSE_POSTTRANS = 1 << 5;
  private static final int RPMSENSE_PREREQ = 1 << 6;
  private static final int RPMSENSE_PRETRANS = 1 << 7;
  private static final int RPMSENSE_SCRIPT_PRE = 1 << 9;
  private static final int RPMSENSE_SCRIPT_POST = 1 << 10;

  private static final int MAX_HEADER_BYTES = 64 * 1024 * 1024;
  private static final int DEFAULT_RANGE_READ_BYTES = 8 * 1024 * 1024;

  private RpmMetadataParser() {
  }

  static RpmMetadata parse(Path rpm) throws IOException {
    long size = Files.size(rpm);
    int readBytes = (int) Math.min(size, DEFAULT_RANGE_READ_BYTES);
    while (readBytes <= Math.min(size, MAX_HEADER_BYTES)) {
      try (InputStream input = Files.newInputStream(rpm)) {
        return parse(input.readNBytes(readBytes));
      } catch (IllegalArgumentException e) {
        if (readBytes >= size || readBytes >= MAX_HEADER_BYTES) {
          throw e;
        }
        readBytes = (int) Math.min(Math.min(size, MAX_HEADER_BYTES), (long) readBytes * 2);
      }
    }
    throw new IllegalArgumentException("RPM header exceeds supported size");
  }

  static RpmMetadata parse(InputStream input) throws IOException {
    return parse(input.readAllBytes());
  }

  static RpmMetadata parse(byte[] bytes) {
    Headers headers = readHeaders(bytes);
    return toMetadata(headers.signature(), headers.main(), headers.range());
  }

  static int defaultRangeReadBytes() {
    return DEFAULT_RANGE_READ_BYTES;
  }

  static HeaderRange headerRange(byte[] bytes) {
    if (bytes.length < LEAD_SIZE + HEADER_PREFIX_SIZE) {
      throw new IllegalArgumentException("RPM is too small to contain headers");
    }
    if (int32(bytes, 0) != LEAD_MAGIC) {
      throw new IllegalArgumentException("Invalid RPM lead magic");
    }
    Header signature = readHeader(bytes, LEAD_SIZE);
    long mainStart = align8(signature.end());
    Header main = readHeader(bytes, checkedInt(mainStart));
    return new HeaderRange(main.start(), main.end());
  }

  private static Headers readHeaders(byte[] bytes) {
    HeaderRange range = headerRange(bytes);
    Header signature = readHeader(bytes, LEAD_SIZE);
    Header main = readHeader(bytes, checkedInt(range.start()));
    return new Headers(signature, main, range);
  }

  private static RpmMetadata toMetadata(Header signature, Header header, HeaderRange range) {
    List<RpmFile> files = files(header);
    List<RpmDependency> provides = dependencies(header, RPMTAG_PROVIDENAME, RPMTAG_PROVIDEFLAGS,
        RPMTAG_PROVIDEVERSION, false, Set.of(), Set.of());
    Set<String> provided = rawDependencyKeys(header, RPMTAG_PROVIDENAME, RPMTAG_PROVIDEFLAGS,
        RPMTAG_PROVIDEVERSION);
    Set<String> primaryFiles = primaryFilePaths(files);
    return new RpmMetadata(
        required(header.string(RPMTAG_NAME), "name"),
        required(header.string(RPMTAG_VERSION), "version"),
        required(header.string(RPMTAG_RELEASE), "release"),
        String.valueOf(header.longValue(RPMTAG_EPOCH, 0)),
        required(header.string(RPMTAG_ARCH), "arch"),
        header.string(RPMTAG_SUMMARY),
        header.string(RPMTAG_DESCRIPTION),
        header.string(RPMTAG_URL),
        header.string(RPMTAG_LICENSE),
        header.string(RPMTAG_VENDOR),
        defaultString(header.string(RPMTAG_GROUP), "Unspecified"),
        header.string(RPMTAG_BUILDHOST),
        header.string(RPMTAG_SOURCERPM),
        header.string(RPMTAG_PACKAGER),
        header.longValue(RPMTAG_BUILDTIME, 0),
        header.longValue(RPMTAG_LONGSIZE, header.longValue(RPMTAG_SIZE, 0)),
        header.longValue(RPMTAG_LONGARCHIVESIZE,
            signature.longValue(RPMTAG_LONGARCHIVESIZE, header.longValue(RPMTAG_ARCHIVESIZE, 0))),
        range.start(),
        range.end(),
        provides,
        dependencies(header, RPMTAG_REQUIRENAME, RPMTAG_REQUIREFLAGS, RPMTAG_REQUIREVERSION,
            true, provided, primaryFiles),
        dependencies(header, RPMTAG_CONFLICTNAME, RPMTAG_CONFLICTFLAGS, RPMTAG_CONFLICTVERSION,
            false, Set.of(), Set.of()),
        dependencies(header, RPMTAG_OBSOLETENAME, RPMTAG_OBSOLETEFLAGS, RPMTAG_OBSOLETEVERSION,
            false, Set.of(), Set.of()),
        files);
  }

  private static List<RpmDependency> dependencies(
      Header header,
      int nameTag,
      int flagsTag,
      int versionTag,
      boolean requires,
      Set<String> provided,
      Set<String> primaryFiles) {
    List<String> names = header.strings(nameTag);
    List<Long> flags = header.longs(flagsTag);
    List<String> versions = header.strings(versionTag);
    int count = Math.min(names.size(), Math.min(flags.size(), versions.size()));
    List<RpmDependency> deps = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    RpmDependency libcHighest = null;
    for (int i = 0; i < count; i++) {
      String name = names.get(i);
      if (name == null || name.isBlank()) continue;
      long sense = flags.get(i);
      String version = versions.get(i);
      String flag = flagString(sense);
      RpmDependency dep = dependency(name, flag, version, requires && prereq(sense));
      if (requires) {
        if (name.startsWith("rpmlib(")) continue;
        if (name.startsWith("/") && primaryFiles.contains(name)) continue;
        String providedKey = rawDependencyKey(name, flag, version);
        if (provided.contains(providedKey)) continue;
        if (dep.name().startsWith("libc.so.6")) {
          libcHighest = highestLibc(libcHighest, dep);
          continue;
        }
      }
      if (seen.add(dependencyKey(dep))) {
        deps.add(dep);
      }
    }
    if (libcHighest != null && seen.add(dependencyKey(libcHighest))) {
      deps.add(libcHighest);
    }
    return deps;
  }

  private static RpmDependency dependency(String name, String flags, String version, boolean pre) {
    Evr evr = parseEvr(version);
    return new RpmDependency(name, flags, evr.epoch(), evr.version(), evr.release(), pre);
  }

  private static Evr parseEvr(String value) {
    if (value == null || value.isBlank()) {
      return new Evr("", "", "");
    }
    String epoch = "";
    String rest = value;
    int colon = value.indexOf(':');
    if (colon >= 0) {
      String candidate = value.substring(0, colon);
      if (candidate.chars().allMatch(Character::isDigit)) {
        epoch = candidate;
      }
      rest = value.substring(colon + 1);
    }
    int dash = rest.indexOf('-');
    if (dash >= 0) {
      return new Evr(epoch, rest.substring(0, dash), rest.substring(dash + 1));
    }
    return new Evr(epoch, rest, "");
  }

  private static String flagString(long flags) {
    return switch ((int) (flags & 0xf)) {
      case RPMSENSE_LESS -> "LT";
      case RPMSENSE_GREATER -> "GT";
      case RPMSENSE_EQUAL -> "EQ";
      case RPMSENSE_LESS | RPMSENSE_EQUAL -> "LE";
      case RPMSENSE_GREATER | RPMSENSE_EQUAL -> "GE";
      default -> "";
    };
  }

  private static boolean prereq(long flags) {
    return (flags & (RPMSENSE_PREREQ | RPMSENSE_SCRIPT_PRE | RPMSENSE_POSTTRANS
        | RPMSENSE_PRETRANS | RPMSENSE_SCRIPT_POST)) != 0;
  }

  private static List<RpmFile> files(Header header) {
    List<String> oldFileNames = header.strings(RPMTAG_OLDFILENAMES);
    if (!oldFileNames.isEmpty()) {
      return oldFileNames.stream().map(path -> new RpmFile(path, "", "")).toList();
    }
    List<String> basenames = header.strings(RPMTAG_BASENAMES);
    List<String> dirnames = header.strings(RPMTAG_DIRNAMES);
    List<Long> dirIndexes = header.longs(RPMTAG_DIRINDEXES);
    List<Long> flags = header.longs(RPMTAG_FILEFLAGS);
    List<Long> modes = header.longs(RPMTAG_FILEMODES);
    List<String> digests = header.strings(RPMTAG_FILEDIGESTS);
    int count = Math.min(basenames.size(), dirIndexes.size());
    List<RpmFile> files = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      String dir = "";
      long dirIndex = dirIndexes.get(i);
      if (dirIndex >= 0 && dirIndex < dirnames.size()) {
        dir = dirnames.get((int) dirIndex);
      }
      String path = (dir == null ? "" : dir) + basenames.get(i);
      long fileFlags = i < flags.size() ? flags.get(i) : 0;
      long mode = i < modes.size() ? modes.get(i) : 0;
      String type = (mode & MODE_DIRECTORY) == MODE_DIRECTORY
          ? "dir"
          : (fileFlags & RPMFILE_GHOST) != 0 ? "ghost" : "";
      String digest = i < digests.size() ? digests.get(i) : "";
      files.add(new RpmFile(path, type, digest));
    }
    return files;
  }

  private static Set<String> rawDependencyKeys(Header header, int nameTag, int flagsTag, int versionTag) {
    List<String> names = header.strings(nameTag);
    List<Long> flags = header.longs(flagsTag);
    List<String> versions = header.strings(versionTag);
    int count = Math.min(names.size(), Math.min(flags.size(), versions.size()));
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < count; i++) {
      keys.add(rawDependencyKey(names.get(i), flagString(flags.get(i)), versions.get(i)));
    }
    return keys;
  }

  private static String dependencyKey(RpmDependency dependency) {
    return dependency.name() + "\0" + nullToEmpty(dependency.flags()) + "\0"
        + nullToEmpty(dependency.epoch()) + "\0" + nullToEmpty(dependency.version()) + "\0"
        + nullToEmpty(dependency.release()) + "\0" + dependency.pre();
  }

  private static String rawDependencyKey(String name, String flags, String version) {
    return name + "\0" + nullToEmpty(flags) + "\0" + nullToEmpty(version);
  }

  private static Set<String> primaryFilePaths(List<RpmFile> files) {
    Set<String> primary = new HashSet<>();
    for (RpmFile file : files) {
      if (isPrimaryFile(file.path())) {
        primary.add(file.path());
      }
    }
    return primary;
  }

  static boolean isPrimaryFile(String path) {
    if (path == null) return false;
    return path.contains("bin/") || path.startsWith("/etc/") || "/usr/lib/sendmail".equals(path);
  }

  private static RpmDependency highestLibc(RpmDependency current, RpmDependency candidate) {
    if (current == null) {
      return candidate;
    }
    return current.name().compareTo(candidate.name()) < 0 ? candidate : current;
  }

  private static Header readHeader(byte[] bytes, int start) {
    if (start < 0 || start + HEADER_PREFIX_SIZE > bytes.length) {
      throw new IllegalArgumentException("RPM header starts outside available bytes");
    }
    int magic = int32(bytes, start);
    if (magic != HEADER_MAGIC) {
      throw new IllegalArgumentException("Invalid RPM header magic at " + start);
    }
    int indexCount = int32(bytes, start + 8);
    int dataLength = int32(bytes, start + 12);
    if (indexCount < 0 || dataLength < 0) {
      throw new IllegalArgumentException("Invalid RPM header lengths");
    }
    long indexStart = (long) start + HEADER_PREFIX_SIZE;
    long dataStart = indexStart + (long) indexCount * INDEX_ENTRY_SIZE;
    long end = dataStart + dataLength;
    if (end < start || end > bytes.length || end - start > MAX_HEADER_BYTES) {
      throw new IllegalArgumentException("RPM header exceeds available bytes");
    }
    Map<Integer, Entry> entries = new HashMap<>();
    for (int i = 0; i < indexCount; i++) {
      int offset = checkedInt(indexStart + (long) i * INDEX_ENTRY_SIZE);
      int tag = int32(bytes, offset);
      int type = int32(bytes, offset + 4);
      int dataOffset = int32(bytes, offset + 8);
      int count = int32(bytes, offset + 12);
      if (dataOffset < 0 || count < 0 || (long) dataOffset > dataLength) {
        continue;
      }
      entries.put(tag, new Entry(tag, type, checkedInt(dataStart + dataOffset), count));
    }
    return new Header(start, end, bytes, entries);
  }

  private static long align8(long value) {
    long remainder = value % 8;
    return remainder == 0 ? value : value + 8 - remainder;
  }

  private static int int32(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes, offset, Integer.BYTES).order(ByteOrder.BIG_ENDIAN).getInt();
  }

  private static long int64(byte[] bytes, int offset) {
    return ByteBuffer.wrap(bytes, offset, Long.BYTES).order(ByteOrder.BIG_ENDIAN).getLong();
  }

  private static int int16(byte[] bytes, int offset) {
    return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, Short.BYTES).order(ByteOrder.BIG_ENDIAN).getShort());
  }

  private static int checkedInt(long value) {
    if (value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("RPM header offset is too large");
    }
    return (int) value;
  }

  private static String defaultString(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("RPM header is missing " + field);
    }
    return value;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  record HeaderRange(long start, long end) {
  }

  private record Headers(Header signature, Header main, HeaderRange range) {
  }

  private record Evr(String epoch, String version, String release) {
  }

  private record Entry(int tag, int type, int offset, int count) {
  }

  private record Header(long start, long end, byte[] bytes, Map<Integer, Entry> entries) {
    String string(int tag) {
      List<String> strings = strings(tag);
      return strings.isEmpty() ? "" : strings.get(0);
    }

    List<String> strings(int tag) {
      Entry entry = entries.get(tag);
      if (entry == null) {
        return List.of();
      }
      if (entry.type() != TYPE_STRING && entry.type() != TYPE_STRING_ARRAY && entry.type() != TYPE_I18NSTRING) {
        return List.of();
      }
      List<String> values = new ArrayList<>();
      int offset = entry.offset();
      int limit = checkedInt(end);
      int count = entry.type() == TYPE_STRING ? 1 : entry.count();
      for (int i = 0; i < count && offset < limit; i++) {
        int zero = offset;
        while (zero < limit && bytes[zero] != 0) {
          zero++;
        }
        values.add(new String(bytes, offset, zero - offset, java.nio.charset.StandardCharsets.UTF_8));
        offset = zero + 1;
      }
      return values;
    }

    long longValue(int tag, long fallback) {
      List<Long> values = longs(tag);
      return values.isEmpty() ? fallback : values.get(0);
    }

    List<Long> longs(int tag) {
      Entry entry = entries.get(tag);
      if (entry == null) {
        return List.of();
      }
      return switch (entry.type()) {
        case TYPE_CHAR, TYPE_INT8, TYPE_BIN -> readInts(entry, 1);
        case TYPE_INT16 -> readInts(entry, 2);
        case TYPE_INT32 -> readInts(entry, 4);
        case TYPE_INT64 -> readInts(entry, 8);
        default -> List.of();
      };
    }

    private List<Long> readInts(Entry entry, int width) {
      List<Long> values = new ArrayList<>();
      long endOffset = (long) entry.offset() + (long) entry.count() * width;
      if (endOffset > end) {
        return List.of();
      }
      for (int i = 0; i < entry.count(); i++) {
        int offset = entry.offset() + i * width;
        long value = switch (width) {
          case 1 -> Byte.toUnsignedInt(bytes[offset]);
          case 2 -> int16(bytes, offset);
          case 4 -> Integer.toUnsignedLong(int32(bytes, offset));
          case 8 -> int64(bytes, offset);
          default -> 0L;
        };
        values.add(value);
      }
      return values;
    }
  }
}

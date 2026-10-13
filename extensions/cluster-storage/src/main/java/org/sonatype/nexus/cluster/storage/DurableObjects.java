/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Content addressed local PVC; acknowledgement is issued only after fsync and atomic installation. */
public final class DurableObjects
{
  private final Path root;
  private final ObjectMapper json = new ObjectMapper();
  private static final int VERIFIED_LIMIT = 65536;
  private final java.util.Map<String, Fingerprint> verified = java.util.Collections.synchronizedMap(
      new java.util.LinkedHashMap<String, Fingerprint>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(final java.util.Map.Entry<String, Fingerprint> eldest) {
          return size() > VERIFIED_LIMIT;
        }
      });

  public DurableObjects(final Path root) throws IOException {
    this.root = root.toAbsolutePath().normalize();
    Files.createDirectories(this.root.resolve("objects"));
    Files.createDirectories(this.root.resolve("journal"));
    Files.createDirectories(this.root.resolve("staging"));
    forceDirectory(this.root);
  }

  public BlobVersion ingest(final InputStream stream) throws IOException {
    Path stage = root.resolve("staging").resolve(UUID.randomUUID() + ".part");
    MessageDigest sha256 = digest("SHA-256"), sha1 = digest("SHA-1");
    long size = 0;
    try {
      try (FileChannel output = FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        byte[] buffer = new byte[128 * 1024];
        for (int count; (count = stream.read(buffer)) != -1;) {
          if (count == 0) { continue; }
          size = Math.addExact(size, count);
          sha256.update(buffer, 0, count); sha1.update(buffer, 0, count);
          ByteBuffer b = ByteBuffer.wrap(buffer, 0, count);
          while (b.hasRemaining()) { output.write(b); }
        }
        output.force(true);
      }
      BlobVersion version = new BlobVersion();
      version.size = size;
      version.sha256 = HexFormat.of().formatHex(sha256.digest());
      version.sha1 = HexFormat.of().formatHex(sha1.digest());
      Path dest = object(version.sha256);
      Files.createDirectories(dest.getParent());
      if (!has(version)) {
        Files.move(stage, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      else { Files.delete(stage); }
      forceDirectory(dest.getParent());
      forceDirectory(root.resolve("objects"));
      return version;
    }
    finally { Files.deleteIfExists(stage); }
  }

  public void receive(final BlobVersion expected, final InputStream stream) throws IOException {
    BlobVersion actual = ingest(stream);
    if (actual.size != expected.size || !actual.sha256.equals(expected.sha256)
        || !actual.sha1.equals(expected.sha1)) {
      throw new IOException("Replicated content checksum or length mismatch");
    }
    record(expected);
  }

  public void record(final BlobVersion version) throws IOException {
    if (version.sequence <= 0) { throw new IOException("Invalid journal sequence"); }
    Path dest = root.resolve("journal").resolve(Long.toString(version.sequence) + ".json");
    Path stage = root.resolve("staging").resolve(UUID.randomUUID() + ".manifest");
    byte[] data = json.writeValueAsBytes(version);
    try {
      try (FileChannel output = FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        while (buffer.hasRemaining()) { output.write(buffer); }
        output.force(true);
      }
      Files.move(stage, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      forceDirectory(dest.getParent());
    }
    finally { Files.deleteIfExists(stage); }
  }

  public boolean has(final BlobVersion v) throws IOException {
    Path file = object(v.sha256);
    if (!Files.isRegularFile(file) || Files.size(file) != v.size) { return false; }
    java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
    Fingerprint current = new Fingerprint(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime());
    if (current.equals(verified.get(v.sha256))) { return true; }
    MessageDigest digest = digest("SHA-256");
    try (InputStream bytes = new BufferedInputStream(Files.newInputStream(file))) {
      byte[] buffer = new byte[128 * 1024];
      for (int n; (n = bytes.read(buffer)) >= 0;) { if (n > 0) { digest.update(buffer, 0, n); } }
    }
    boolean valid = v.sha256.equals(HexFormat.of().formatHex(digest.digest()));
    if (valid) { verified.put(v.sha256, current); }
    else { verified.remove(v.sha256); }
    return valid;
  }

  public boolean scrub(final BlobVersion v) throws IOException { verified.remove(v.sha256); return has(v); }

  private record Fingerprint(Object key, long size, java.nio.file.attribute.FileTime modified) {}

  public InputStream open(final String sha256) throws IOException {
    return new BufferedInputStream(new VerifiedStream(Files.newInputStream(object(sha256)), sha256));
  }

  /** Hold the final chunk until SHA validation so a corrupt full download ends as a truncated response. */
  private static final class VerifiedStream extends InputStream {
    private final InputStream source;
    private final String expected;
    private final MessageDigest digest = digest("SHA-256");
    private byte[] current;
    private byte[] next;
    private int offset;
    VerifiedStream(final InputStream source, final String expected) throws IOException {
      this.source = source; this.expected = expected;
      try {
        current = block(); next = block();
        if (next.length == 0) { validate(); }
      }
      catch (IOException e) { source.close(); throw e; }
    }
    private byte[] block() throws IOException {
      byte[] bytes = source.readNBytes(128 * 1024); digest.update(bytes); return bytes;
    }
    private void validate() throws IOException {
      if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) { throw new IOException("Stored object SHA256 integrity failure"); }
    }
    @Override public int read() throws IOException {
      byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }
    @Override public int read(final byte[] bytes, final int start, final int length) throws IOException {
      java.util.Objects.checkFromIndexSize(start, length, bytes.length);
      if (length == 0) { return 0; }
      if (offset == current.length) {
        if (next.length == 0) { return -1; }
        current = next; offset = 0; next = block();
        if (next.length == 0) { validate(); }
      }
      int count = Math.min(length, current.length - offset);
      if (count == 0) { return -1; }
      System.arraycopy(current, offset, bytes, start, count); offset += count; return count;
    }
    @Override public void close() throws IOException { source.close(); }
  }

  public Path object(final String sha256) {
    if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
      throw new IllegalArgumentException("Invalid object digest");
    }
    return root.resolve("objects").resolve(sha256.substring(0, 2)).resolve(sha256 + ".bytes");
  }

  public Path root() { return root; }

  public boolean available() throws IOException {
    if (!Files.isDirectory(root) || Files.getFileStore(root).getUsableSpace() < 64L * 1024 * 1024) { return false; }
    Path probe = root.resolve("staging").resolve("health-" + UUID.randomUUID());
    try (FileChannel c = FileChannel.open(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { c.force(true); }
    finally { Files.deleteIfExists(probe); }
    forceDirectory(root.resolve("staging"));
    return true;
  }

  public String generation() throws IOException {
    Path file = root.resolve("generation");
    if (!Files.exists(file)) {
      String value = UUID.randomUUID().toString();
      try (FileChannel c = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        ByteBuffer b = ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        while (b.hasRemaining()) { c.write(b); } c.force(true);
      }
      catch (java.nio.file.FileAlreadyExistsException concurrentlyCreated) { /* read the installed identity */ }
      forceDirectory(root);
    }
    String value = Files.readString(file).trim();
    if (!value.matches("[a-f0-9-]{36}")) { throw new IOException("Invalid PVC generation identity"); }
    return value;
  }

  static MessageDigest digest(final String name) {
    try { return MessageDigest.getInstance(name); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }

  static void forceDirectory(final Path directory) throws IOException {
    try (FileChannel file = FileChannel.open(directory, StandardOpenOption.READ)) { file.force(true); }
  }
}

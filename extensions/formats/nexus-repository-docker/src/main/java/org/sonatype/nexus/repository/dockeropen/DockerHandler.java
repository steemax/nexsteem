/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.dockeropen;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.regex.*;
import com.fasterxml.jackson.databind.*;
import com.google.common.util.concurrent.Striped;
import org.apache.shiro.SecurityUtils;
import org.sonatype.nexus.repository.*;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.group.*;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
@Component
public class DockerHandler implements Handler {
  private static final Pattern RESOURCE = Pattern.compile("^/v2/(.+)/(blobs|manifests|tags|referrers)/(.*)$");
  private static final Set<String> MANIFEST_TYPES = Set.of("application/vnd.docker.distribution.manifest.v2+json",
      "application/vnd.docker.distribution.manifest.list.v2+json", "application/vnd.oci.image.manifest.v1+json", "application/vnd.oci.image.index.v1+json");
  private final ObjectMapper json; private final GroupHandler first;
  private final Striped<Lock> locks = Striped.lock(256); private final long maxBlobBytes;
  public DockerHandler(ObjectMapper json, @Qualifier("default") GroupHandler first,
                       @Value("${nexus.open.docker.max-blob-bytes:21474836480}") long maxBlobBytes) {
    this.json = json; this.first = first; this.maxBlobBytes = maxBlobBytes;
  }
  private static boolean digest(String value) { return value != null && value.matches("sha256:[a-f0-9]{64}"); }
  private static boolean image(String value) { return value.length() <= 255 && value.matches("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*"); }
  private static boolean reference(String value) { return digest(value) || value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}"); }
  private static String sha(byte[] value) throws Exception { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
  private String prefix(Context context) { return context.getRequest().getAttributes().get("open.docker.route-prefix", "/v2/").toString(); }
  private Response response(int code, Payload payload) {
    return new Response.Builder().status(code < 400 ? Status.success(code) : Status.failure(code)).payload(payload)
        .header("Docker-Distribution-Api-Version", "registry/2.0").build();
  }
  private Response data(Object object) throws IOException { return response(200, new StringPayload(json.writeValueAsString(object), "application/json")); }
  private Response error(int code, String reason) throws IOException { return response(code,
      new StringPayload(json.writeValueAsString(Map.of("errors", List.of(Map.of("code", reason, "message", reason)))), "application/json")); }
  private static Response header(Response response, String name, String value) { response.getHeaders().set(name, value); return response; }
  private static String blobPath(String name, String digest) { return "/v2/" + name + "/blobs/" + digest; }
  @Override public Response handle(Context context) throws Exception {
    Request request = context.getRequest(); String path = request.getPath(), method = request.getAction();
    if (path.equals("/v2/")) { return data(Map.of()); }
    if (path.contains("%") || path.matches(".*(?:^|/)\\.\\.(?:/|$).*") || path.contains("\\") || path.contains("\u0000")) { return error(400, "NAME_INVALID"); }
    Repository repository = context.getRepository(); DockerContentFacet content = repository.facet(DockerContentFacet.class);
    if (path.equals("/v2/_catalog")) { return listing(context, null); }
    java.util.regex.Matcher parsed = RESOURCE.matcher(path);
    if (!parsed.matches() || !image(parsed.group(1))) { return error(404, "NAME_UNKNOWN"); }
    String name = parsed.group(1), category = parsed.group(2), target = parsed.group(3);
    if (category.equals("tags") && target.equals("list") && List.of("GET", "HEAD").contains(method)) { return listing(context, name); }
    if (!repository.getType().getValue().equals("hosted")) {
      if (!List.of("GET", "HEAD").contains(method)) { return error(405, "UNSUPPORTED"); }
      if (repository.getType().getValue().equals("group")) { return first.handle(context); }
      Content cached = repository.facet(DockerProxyFacet.class).get(context);
      return cached == null ? error(404, category.equals("blobs") ? "BLOB_UNKNOWN" : "MANIFEST_UNKNOWN") : download(cached, category, target);
    }
    if (category.equals("blobs") && target.startsWith("uploads/")) { return upload(context, content, name, target.substring(8)); }
    if (category.equals("manifests")) {
      if (!reference(target)) { return error(400, "TAG_INVALID"); }
      if (method.equals("PUT")) { return manifest(context, content, name, target); }
      if (method.equals("DELETE")) {
        if (!digest(target)) { return error(400, "DIGEST_INVALID"); }
        Content existing = content.read(path); if (existing == null) { return error(404, "MANIFEST_UNKNOWN"); }
        for (FluentAsset asset : content.listing()) {
          if (asset.path().startsWith("/v2/" + name + "/manifests/") && asset.blob().map(blob -> target.equals("sha256:" + blob.checksums().get("sha256"))).orElse(false)) { asset.delete(); }
        }
        return response(202, null);
      }
    } else if (category.equals("blobs")) {
      if (!digest(target)) { return error(400, "DIGEST_INVALID"); }
      if (method.equals("DELETE")) { return content.remove(path) ? response(202, null) : error(404, "BLOB_UNKNOWN"); }
    } else if (category.equals("referrers") && digest(target)) { return referrers(context, content, name, target); }
    else { return error(404, "NAME_UNKNOWN"); }
    if (!List.of("GET", "HEAD").contains(method)) { return error(405, "UNSUPPORTED"); }
    Content stored = content.read(path);
    return stored == null ? error(404, category.equals("blobs") ? "BLOB_UNKNOWN" : "MANIFEST_UNKNOWN") : download(stored, category, target);
  }
  private Response download(Content stored, String kind, String reference) throws Exception {
    Response result = response(200, stored);
    var asset = stored.getAttributes().get(org.sonatype.nexus.repository.content.Asset.class);
    String hash = asset == null ? null : asset.blob().map(blob -> blob.checksums().get("sha256")).orElse(null);
    if (hash != null) { header(result, "Docker-Content-Digest", "sha256:" + hash); header(result, "ETag", "\"sha256:" + hash + "\""); }
    if (kind.equals("blobs")) { header(result, "Content-Type", "application/octet-stream"); }
    header(result, "Content-Length", Long.toString(stored.getSize())); return result;
  }
  private Response upload(Context context, DockerContentFacet content, String name, String uuid) throws Exception {
    String method = context.getRequest().getAction(); String owner = Objects.toString(SecurityUtils.getSubject().getPrincipal(), "anonymous");
    if (uuid.isEmpty() && method.equals("POST")) {
      if (!content.capacityForUpload()) { return error(429, "TOOMANYREQUESTS"); }
      String mount = context.getRequest().getParameters().get("mount"), from = context.getRequest().getParameters().get("from");
      if (digest(mount) && from != null && image(from)) {
        Request source = new Request.Builder().action("HEAD").path(blobPath(from, mount)).build();
        context.getRepository().facet(org.sonatype.nexus.repository.security.SecurityFacet.class).ensurePermitted(source);
        Content blob = content.read(blobPath(from, mount));
        if (blob != null) { content.put(blobPath(name, mount), blob, "blob", name, mount); return committed(context, name, mount); }
      }
      uuid = UUID.randomUUID().toString(); String path = "/v2/" + name + "/blobs/uploads/" + uuid;
      content.put(path, new Bytes(new byte[0], "application/octet-stream"), "upload", null, null);
      content.asset(path).orElseThrow().withAttribute("uploadOwner", owner);
      String monolithic = context.getRequest().getParameters().get("digest");
      if (monolithic != null) { return append(context, content, name, uuid, monolithic, owner); }
      return progress(context, name, uuid, 0, 202);
    }
    if (!uuid.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) { return error(404, "BLOB_UPLOAD_UNKNOWN"); }
    if (method.equals("PUT") || method.equals("PATCH")) { return append(context, content, name, uuid,
        method.equals("PUT") ? context.getRequest().getParameters().get("digest") : null, owner); }
    String path = "/v2/" + name + "/blobs/uploads/" + uuid;
    var stored = content.asset(path);
    if (stored.isEmpty() || !owner.equals(stored.get().attributes().get("uploadOwner", String.class))) { return error(404, "BLOB_UPLOAD_UNKNOWN"); }
    if (method.equals("DELETE")) { content.removeUpload(path); return response(204, null); }
    if (method.equals("GET")) { return progress(context, name, uuid, offset(stored.get()), 204); }
    return error(405, "UNSUPPORTED");
  }
  private Response append(Context context, DockerContentFacet content, String name, String uuid, String expected, String owner) throws Exception {
    String path = "/v2/" + name + "/blobs/uploads/" + uuid;
    Lock lock = locks.get(context.getRepository().getName() + path); lock.lock();
    try {
      var old = content.asset(path);
      if (old.isEmpty() || !owner.equals(old.get().attributes().get("uploadOwner", String.class))) { return error(404, "BLOB_UPLOAD_UNKNOWN"); }
      if (context.getRequest().getAction().equals("PUT") && !digest(expected)) { return error(400, "DIGEST_INVALID"); }
      Payload incoming = context.getRequest().getPayload(); long oldSize = offset(old.get());
      int count = partCount(old.get());
      if (incoming != null && incoming.getSize() > maxBlobBytes - oldSize) { return error(413, "SIZE_INVALID"); }
      String range = context.getRequest().getHeaders().get("Content-Range");
      if (range != null) {
        java.util.regex.Matcher bounds = Pattern.compile("(?:bytes )?(\\d+)-(\\d+)(?:/\\*)?").matcher(range);
        try {
          if (!bounds.matches() || Long.parseLong(bounds.group(1)) != oldSize
              || Long.parseLong(bounds.group(2)) < oldSize || Long.parseLong(bounds.group(2)) >= maxBlobBytes
              || incoming != null && incoming.getSize() >= 0 && Long.parseLong(bounds.group(2)) - oldSize + 1 != incoming.getSize()) { return error(416, "RANGE_INVALID"); }
        } catch (NumberFormatException invalid) { return error(416, "RANGE_INVALID"); }
      }
      if (incoming != null && incoming.getSize() != 0) {
        if (count >= 10000) { return error(413, "SIZE_INVALID"); }
        // Persist each chunk once. Re-reading all previous bytes on every PATCH is quadratic IO.
        Payload bounded = new Payload() {
          public String getContentType() { return "application/octet-stream"; }
          public long getSize() { return incoming.getSize(); }
          public InputStream openInputStream() throws IOException {
            return new FilterInputStream(incoming.openInputStream()) { long read;
              private void count(long amount) throws IOException { if (amount > 0 && (read += amount) > maxBlobBytes - oldSize) { throw new IOException("Docker blob exceeds configured maximum size"); } }
              public int read() throws IOException { int value = super.read(); count(value == -1 ? 0 : 1); return value; }
              public int read(byte[] value, int start, int count) throws IOException { int actual = in.read(value, start, count); count(actual); return actual; }
            };
          }
        };
        Content chunk = content.put(chunkPath(path, count), bounded, "upload-part", null, null);
        content.asset(path).orElseThrow().withAttribute("uploadState", Map.of("offset", oldSize + chunk.getSize(), "parts", count + 1));
      }
      FluentAsset current = content.asset(path).orElseThrow();
      if (expected != null) {
        int parts = partCount(current); long length = offset(current);
        Payload joined = new Payload() {
          public String getContentType() { return "application/octet-stream"; } public long getSize() { return length; }
          public InputStream openInputStream() throws IOException {
            Enumeration<InputStream> streams = new Enumeration<>() {
              int index = -1;
              public boolean hasMoreElements() { return index < parts; }
              public InputStream nextElement() {
                if (!hasMoreElements()) { throw new NoSuchElementException(); }
                // The initial asset may contain an old-style pre-upgrade partial upload.
                try {
                  Content item = index++ < 0 ? current.download() : content.read(chunkPath(path, index-1));
                  if (item == null) { throw new IOException("Persisted Docker upload part is missing"); }
                  return item.openInputStream();
                } catch (IOException failure) { throw new UncheckedIOException(failure); }
              }
            };
            return new SequenceInputStream(streams);
          }
        };
        // Validate before publishing the final digest-addressed asset.
        try (var temporary = content.blobs().ingest(joined, List.of(org.sonatype.nexus.common.hash.HashAlgorithm.SHA256))) {
          String actual = "sha256:" + temporary.getHashes().get(org.sonatype.nexus.common.hash.HashAlgorithm.SHA256);
          if (!expected.equals(actual)) { return error(400, "DIGEST_INVALID"); }
          if (content.asset(blobPath(name, expected)).isEmpty()) {
            content.assets().path(blobPath(name, expected)).kind("blob").blob(temporary).save();
          }
        }
        content.removeUpload(path); return committed(context, name, expected);
      }
      return progress(context, name, uuid, offset(current), 202);
    } finally { lock.unlock(); }
  }
  private static String chunkPath(String path, int index) { return path + "/chunks/" + String.format(java.util.Locale.ROOT, "%08d", index); }
  private static int partCount(FluentAsset asset) { Object value = asset.attributes().child("uploadState").get("parts"); return value instanceof Number number ? number.intValue() : 0; }
  private static long offset(FluentAsset asset) { Object value = asset.attributes().child("uploadState").get("offset"); return value instanceof Number number ? number.longValue() : asset.download().getSize(); }
  private Response progress(Context context, String name, String uuid, long size, int code) {
    return header(header(header(response(code, null), "Docker-Upload-UUID", uuid), "Location", prefix(context) + name + "/blobs/uploads/" + uuid),
        "Range", "0-" + Math.max(0, size - 1));
  }
  private Response committed(Context context, String name, String digest) {
    return header(header(response(201, null), "Location", prefix(context) + name + "/blobs/" + digest), "Docker-Content-Digest", digest);
  }
  private Response manifest(Context context, DockerContentFacet content, String name, String reference) throws Exception {
    if (context.getRequest().getPayload() == null) { return error(400, "MANIFEST_INVALID"); }
    String type = context.getRequest().getPayload().getContentType(); if (type != null) { type = type.split(";", 2)[0].trim(); }
    if (type == null || !MANIFEST_TYPES.contains(type)) { return error(415, "MANIFEST_INVALID"); }
    byte[] bytes; try (InputStream stream = context.getRequest().getPayload().openInputStream()) { bytes = stream.readNBytes(4 * 1024 * 1024 + 1); }
    if (bytes.length > 4 * 1024 * 1024) { return error(413, "MANIFEST_INVALID"); }
    JsonNode manifest;
    try { manifest = json.readTree(bytes); } catch (IOException invalid) { return error(400, "MANIFEST_INVALID"); }
    if (manifest == null || !manifest.path("schemaVersion").isIntegralNumber() || manifest.path("schemaVersion").asInt() != 2 || manifest.has("mediaType") && !type.equals(manifest.path("mediaType").asText())) { return error(400, "MANIFEST_INVALID"); }
    String digest = sha(bytes); if (reference.contains(":") && !reference.equals(digest)) { return error(400, "DIGEST_INVALID"); }
    boolean index = type.contains("index") || type.contains("list");
    List<JsonNode> descriptors = new ArrayList<>();
    if (index) {
      if (!manifest.path("manifests").isArray()) { return error(400, "MANIFEST_INVALID"); }
      manifest.path("manifests").forEach(descriptors::add);
    } else {
      if (!manifest.path("config").isObject() || !manifest.path("layers").isArray()) { return error(400, "MANIFEST_INVALID"); }
      descriptors.add(manifest.path("config")); manifest.path("layers").forEach(descriptors::add);
    }
    for (JsonNode descriptor : descriptors) {
      String expected = descriptor.path("digest").asText(); if (!digest(expected) || !descriptor.path("size").canConvertToLong() || descriptor.path("size").asLong() < 0) { return error(400, "MANIFEST_INVALID"); }
      Content item = content.read("/v2/" + name + "/" + (index ? "manifests/" : "blobs/") + expected);
      if (item == null || item.getSize() != descriptor.path("size").asLong()) { return error(400, "MANIFEST_BLOB_UNKNOWN"); }
    }
    Payload payload = new Bytes(bytes, type); String canonical = "/v2/" + name + "/manifests/" + digest;
    if (content.asset(canonical).isEmpty()) { content.put(canonical, payload, "manifest", name, digest); }
    String path = "/v2/" + name + "/manifests/" + reference;
    if (!path.equals(canonical)) { content.put(path, payload, "manifest-tag", name, reference); }
    if (manifest.path("subject").path("digest").isTextual()) {
      content.asset(canonical).orElseThrow().withAttribute("dockerSubject", manifest.path("subject").path("digest").asText());
      content.asset(canonical).orElseThrow().withAttribute("dockerArtifactType", manifest.path("artifactType").asText(""));
    }
    return header(header(response(201, null), "Location", prefix(context) + name + "/manifests/" + digest), "Docker-Content-Digest", digest);
  }
  private Response listing(Context context, String image) throws Exception {
    Request request = context.getRequest(); if (!List.of("GET", "HEAD").contains(request.getAction())) { return error(405, "UNSUPPORTED"); }
    if (context.getRepository().getType().getValue().equals("proxy")) {
      Content remote = context.getRepository().facet(DockerProxyFacet.class).get(context); return remote == null ? error(404, "NAME_UNKNOWN") : response(200, remote);
    }
    TreeSet<String> values = new TreeSet<>(); String prefix = image == null ? "/v2/" : "/v2/" + image + "/manifests/";
    List<Repository> repositories = context.getRepository().getType().getValue().equals("group") ? context.getRepository().facet(GroupFacet.class).members() : List.of(context.getRepository());
    for (Repository repository : repositories) {
      Request memberRequest = new Request.Builder().copy(request).build();
      if (context.getRepository().getType().getValue().equals("group")) {
        Response result = repository.facet(ViewFacet.class).dispatch(memberRequest, context);
        if (result.getStatus().getCode() == 200 && result.getPayload() != null) {
          try (InputStream stream = result.getPayload().openInputStream()) { json.readTree(stream).path(image == null ? "repositories" : "tags").forEach(value -> values.add(value.asText())); }
        }
        continue;
      }
      for (FluentAsset asset : repository.facet(DockerContentFacet.class).listing()) {
        String path = asset.path(); int marker = path.lastIndexOf("/manifests/");
        if (marker < 4 || !path.startsWith(prefix)) { continue; }
        if (image == null) { values.add(path.substring(4, marker)); }
        else { String tag = path.substring(marker + 11); if (!tag.contains(":") && reference(tag)) { values.add(tag); } }
      }
    }
    String last = request.getParameters().get("last"); int limit = 1000;
    try { if (request.getParameters().get("n") != null) { limit = Integer.parseInt(request.getParameters().get("n")); } }
    catch (NumberFormatException invalid) { return error(400, "PAGINATION_NUMBER_INVALID"); }
    if (limit < 1 || limit > 1000) { return error(400, "PAGINATION_NUMBER_INVALID"); }
    List<String> all = values.stream().filter(value -> last == null || value.compareTo(last) > 0).toList();
    List<String> page = all.subList(0, Math.min(limit, all.size()));
    Map<String,Object> output = new HashMap<>(); if (image != null) { output.put("name", image); } output.put(image == null ? "repositories" : "tags", page);
    Response result = data(output);
    if (all.size() > limit) {
      String location = image == null ? prefix(context) + "_catalog" : prefix(context) + image + "/tags/list";
      header(result, "Link", "<" + location + "?n=" + limit + "&last=" + URLEncoder.encode(page.get(page.size()-1), StandardCharsets.UTF_8) + ">; rel=\"next\"");
    }
    return result;
  }
  private Response referrers(Context context, DockerContentFacet content, String name, String subject) throws Exception {
    List<Map<String,Object>> descriptors = new ArrayList<>(); String artifact = context.getRequest().getParameters().get("artifactType");
    for (FluentAsset asset : content.listing()) {
      if (!asset.path().startsWith("/v2/" + name + "/manifests/sha256:") || !subject.equals(asset.attributes().get("dockerSubject", String.class))) { continue; }
      String type = asset.attributes().get("dockerArtifactType", String.class); if (artifact != null && !artifact.equals(type)) { continue; }
      Map<String,Object> descriptor = new HashMap<>(); descriptor.put("digest", asset.path().substring(asset.path().lastIndexOf('/')+1));
      descriptor.put("size", asset.download().getSize()); descriptor.put("mediaType", asset.download().getContentType());
      if (type != null && !type.isEmpty()) { descriptor.put("artifactType", type); } descriptors.add(descriptor);
    }
    return response(200, new StringPayload(json.writeValueAsString(Map.of("schemaVersion", 2,
        "mediaType", "application/vnd.oci.image.index.v1+json", "manifests", descriptors)), "application/vnd.oci.image.index.v1+json"));
  }
  private record Bytes(byte[] value, String contentType) implements Payload {
    public long getSize() { return value.length; } public String getContentType() { return contentType; }
    public InputStream openInputStream() { return new ByteArrayInputStream(value); }
  }
}

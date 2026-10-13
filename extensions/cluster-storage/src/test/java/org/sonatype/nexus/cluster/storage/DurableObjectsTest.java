/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.storage;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DurableObjectsTest
{
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void committedContentSurvivesReopenAndRejectsPathTraversal() throws Exception {
    Path root = temporary.newFolder().toPath();
    byte[] bytes = new byte[2 * 1024 * 1024]; new java.util.Random(19).nextBytes(bytes);
    DurableObjects initial = new DurableObjects(root);
    BlobVersion v = initial.ingest(new ByteArrayInputStream(bytes)); v.sequence = 1; initial.record(v);
    String generation = initial.generation();
    DurableObjects reopened = new DurableObjects(root);
    assertEquals(generation, reopened.generation()); assertTrue(reopened.has(v));
    try (java.io.InputStream in = reopened.open(v.sha256)) { assertArrayEquals(bytes, in.readAllBytes()); }
    try { reopened.object("../../other-file"); fail("Traversal accepted"); }
    catch (IllegalArgumentException expected) {}
  }

  @Test public void sameLengthCorruptionIsDetectedAndRepairedFromVerifiedReplica() throws Exception {
    DurableObjects objects = new DurableObjects(temporary.newFolder().toPath());
    byte[] correct = "artifact-with-valid-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    BlobVersion v = objects.ingest(new ByteArrayInputStream(correct)); v.sequence = 2;
    byte[] corrupt = correct.clone(); corrupt[3] ^= 4;
    Files.write(objects.object(v.sha256), corrupt);
    assertFalse(objects.has(v));
    try (java.io.InputStream in = objects.open(v.sha256)) { in.readAllBytes(); fail("Corrupt read accepted"); }
    catch (java.io.IOException expected) {}
    objects.receive(v, new ByteArrayInputStream(correct));
    assertTrue(objects.has(v));
    try (java.io.InputStream in = objects.open(v.sha256)) { assertArrayEquals(correct, in.readAllBytes()); }
  }

  @Test public void invalidReplicaIsNeverAcknowledgedAsValidVersion() throws Exception {
    DurableObjects objects = new DurableObjects(temporary.newFolder().toPath());
    BlobVersion v = objects.ingest(new ByteArrayInputStream(new byte[]{1, 2, 3})); v.sequence = 3;
    try { objects.receive(v, new ByteArrayInputStream(new byte[]{3, 2, 1})); fail("Bad digest accepted"); }
    catch (java.io.IOException expected) {}
    assertFalse(Files.exists(objects.root().resolve("journal/3.json")));
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolSafetyTest {
  @Test public void rubyYamlRetainsExactRuntimeAndRubyRequirements() {
    String yaml="--- !ruby/object:Gem::Specification\nname: proof\nversion: !ruby/object:Gem::Version\n  version: 1.0.0\nplatform: ruby\ndependencies:\n- !ruby/object:Gem::Dependency\n  name: child\n  requirement: !ruby/object:Gem::Requirement\n    requirements:\n    - - '='\n      - !ruby/object:Gem::Version\n        version: 2.0.0\n  type: :runtime\n- !ruby/object:Gem::Dependency\n  name: dev-only\n  type: :development\n  requirement: !ruby/object:Gem::Requirement\n    requirements:\n    - - '>='\n      - !ruby/object:Gem::Version\n        version: '0'\nrequired_ruby_version: !ruby/object:Gem::Requirement\n  requirements:\n  - - '>='\n    - !ruby/object:Gem::Version\n      version: 3.1.0\n";
    var metadata=RubygemsCodec.parseMetadataYaml(yaml);
    assertEquals("1.0.0",metadata.version());assertEquals(1,metadata.dependencies().size());
    assertEquals(List.of("= 2.0.0"),metadata.dependencies().get(0).requirements());
    assertEquals(List.of(">= 3.1.0"),metadata.requiredRuby());
  }
  @Test(expected=IllegalArgumentException.class) public void yamlCannotConstructJavaObjects() {
    RubygemsCodec.parseMetadataYaml("!!java.net.URL ['https://invalid.example']");
  }
  @Test(expected=IllegalArgumentException.class) public void duplicateYamlKeysAreRejected() {
    RubygemsCodec.parseMetadataYaml("name: first\nname: second\nversion: 1.0.0\n");
  }
  @Test public void marshalReaderSupportsGemVersionDataWithoutInstantiatingClasses() {
    var specs=List.of(new RubygemsCodec.GemMetadata("proof","1.0.0","ruby",List.of()));
    var decoded=RubyMarshal.specs(RubygemsCodec.rubyMarshalSpecs(specs));
    assertEquals("proof",decoded.get(0).name());assertEquals("1.0.0",decoded.get(0).version());
  }
  @Test(expected=IllegalArgumentException.class) public void truncatedMarshalReferenceIsRejected() {
    RubyMarshal.specs(new byte[]{4,8,'[',6,'@',99});
  }
  @Test(expected=Exception.class) public void yumXmlRejectsExternalEntities() throws Exception {
    YumGroupMetadata.parse("<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///etc/passwd'>]><metadata>&p;</metadata>".getBytes(StandardCharsets.UTF_8));
  }
  @Test(expected=IllegalArgumentException.class) public void rpmHeaderCountsAreBounded() {
    RpmMetadataParser.parse(new byte[128]);
  }
}

/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;

import org.sonatype.nexus.mime.MimeRule;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** NuGet uses ZIP/OPC packaging; its .rels and Content_Types.xml also trigger Tika's OOXML detector. */
@Component
@Qualifier("nuget")
public class NugetMimeRules implements MimeRulesSource {
  private static final MimeRule PACKAGE = new MimeRule(true, "application/zip", "application/x-tika-ooxml");

  @Override
  public MimeRule getRuleForName(String name) {
    return name.endsWith(".nupkg") ? PACKAGE : null;
  }
}

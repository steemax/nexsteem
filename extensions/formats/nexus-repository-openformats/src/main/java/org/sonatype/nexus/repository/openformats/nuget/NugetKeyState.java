/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component
public final class NugetKeyState implements org.sonatype.nexus.rapture.StateContributor {
 private final org.sonatype.nexus.security.realm.RealmManager realms;
 public NugetKeyState(org.sonatype.nexus.security.realm.RealmManager realms){this.realms=realms;}
 @Override public java.util.Map<String,Object> getState(){return java.util.Map.of("nugetApiKeyRealmEnabled",realms.isRealmEnabled(NugetApiKey.NAME));}
}

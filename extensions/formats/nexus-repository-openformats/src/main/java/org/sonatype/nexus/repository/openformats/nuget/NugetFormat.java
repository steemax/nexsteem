/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("nuget") public class NugetFormat extends org.sonatype.nexus.repository.Format { public NugetFormat() { super("nuget"); } }

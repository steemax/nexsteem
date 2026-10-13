/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NugetVersionsTest {
 @Test void normalizesLegacyReleaseAndBuildMetadata(){assertEquals("1.2.3",NugetPackage.version("01.002.3.0+build.4"));assertEquals("1.0.0",NugetPackage.version("1"));assertEquals("1.0.0-beta.10",NugetPackage.version("1.0-BETA.10+ignored"));}
 @Test void ordersReleaseAndNumericPrereleases(){assertTrue(NugetVersions.compare("1.0.0-beta.10","1.0.0-beta.2")>0);assertTrue(NugetVersions.compare("1.0.0","1.0.0-rc.1")>0);assertEquals(0,NugetVersions.compare("1.0.0+one","1.0.0+two"));}
 @Test void ordersFourthReleasePartAndHugeNumbers(){assertTrue(NugetVersions.compare("1.0.0.10","1.0.0.2")>0);assertTrue(NugetVersions.compare("999999999999999999999.0.0","100.0.0")>0);}
 @Test void rejectsPathsAndMalformedVersions(){assertThrows(IllegalArgumentException.class,()->NugetPackage.id("../../x"));assertThrows(IllegalArgumentException.class,()->NugetPackage.version("1.0.0/../../../x"));assertThrows(IllegalArgumentException.class,()->NugetPackage.version("1..0"));}
}

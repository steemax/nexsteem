/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum.rubygems;
@org.springframework.stereotype.Component @org.springframework.beans.factory.annotation.Qualifier("rubygems") public class RubygemsSecurityContributor extends org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor { public RubygemsSecurityContributor(@org.springframework.beans.factory.annotation.Qualifier("rubygems") org.sonatype.nexus.repository.Format format) { super(format); } }

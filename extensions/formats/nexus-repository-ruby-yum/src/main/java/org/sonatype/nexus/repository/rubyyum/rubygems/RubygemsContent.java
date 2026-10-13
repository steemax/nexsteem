/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum.rubygems;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype") @org.springframework.beans.factory.annotation.Qualifier("rubygems") public class RubygemsContent extends org.sonatype.nexus.repository.rubyyum.RubyYumContentFacet { public RubygemsContent(@org.springframework.beans.factory.annotation.Qualifier("rubygems") org.sonatype.nexus.repository.content.store.FormatStoreManager store) { super(store); } }

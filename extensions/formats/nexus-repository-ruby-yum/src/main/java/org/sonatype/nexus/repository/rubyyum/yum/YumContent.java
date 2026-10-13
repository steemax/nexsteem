/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum.yum;
@org.springframework.stereotype.Component @org.springframework.context.annotation.Scope("prototype") @org.springframework.beans.factory.annotation.Qualifier("yum") public class YumContent extends org.sonatype.nexus.repository.rubyyum.RubyYumContentFacet { public YumContent(@org.springframework.beans.factory.annotation.Qualifier("yum") org.sonatype.nexus.repository.content.store.FormatStoreManager store) { super(store); } }

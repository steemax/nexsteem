/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.pypi.internal;
import java.util.Map;
import org.sonatype.nexus.repository.view.payloads.TempBlobPartPayload;
import static java.util.Objects.requireNonNull;
public final class SignablePyPiPackage {
 private final TempBlobPartPayload wheelPayload, gpgSignature;
 private final Map<String,String> attributes;
 public SignablePyPiPackage(TempBlobPartPayload wheelPayload, Map<String,String> attributes, TempBlobPartPayload gpgSignature) {
  this.wheelPayload=requireNonNull(wheelPayload); this.attributes=requireNonNull(attributes); this.gpgSignature=gpgSignature;
 }
 public TempBlobPartPayload getWheelPayload(){return wheelPayload;}
 public TempBlobPartPayload getGpgSignature(){return gpgSignature;}
 public Map<String,String> getAttributes(){return attributes;}
}

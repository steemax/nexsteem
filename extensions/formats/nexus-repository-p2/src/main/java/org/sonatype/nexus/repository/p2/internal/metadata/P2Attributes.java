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
package org.sonatype.nexus.repository.p2.internal.metadata;
import org.sonatype.nexus.repository.p2.internal.AssetKind;
import java.util.Objects;
public final class P2Attributes {
private final String pluginName;
private final String componentName;
private final String componentVersion;
private final String path;
private final String fileName;
private final String extension;
private final AssetKind assetKind;
private P2Attributes(Builder b){this.pluginName=b.pluginName;this.componentName=b.componentName;this.componentVersion=b.componentVersion;this.path=b.path;this.fileName=b.fileName;this.extension=b.extension;this.assetKind=b.assetKind;}
public static Builder builder(){return new Builder();}
public String getPluginName(){return pluginName;}
public String getComponentName(){return componentName;}
public String getComponentVersion(){return componentVersion;}
public String getPath(){return path;}
public String getFileName(){return fileName;}
public String getExtension(){return extension;}
public AssetKind getAssetKind(){return assetKind;}
public boolean isEmpty(){return pluginName==null&&componentName==null&&componentVersion==null&&path==null&&fileName==null&&extension==null&&assetKind==null;}
public static final class Builder {
private String pluginName;
private String componentName;
private String componentVersion;
private String path;
private String fileName;
private String extension;
private AssetKind assetKind;
public Builder pluginName(String v){pluginName=v;return this;}
public Builder componentName(String v){componentName=v;return this;}
public Builder componentVersion(String v){componentVersion=v;return this;}
public Builder path(String v){path=v;return this;}
public Builder fileName(String v){fileName=v;return this;}
public Builder extension(String v){extension=v;return this;}
public Builder assetKind(AssetKind v){assetKind=v;return this;}
public P2Attributes build(){return new P2Attributes(this);}
public Builder merge(P2Attributes one,P2Attributes two){pluginName(two.pluginName!=null?two.pluginName:one.pluginName);componentName(two.componentName!=null?two.componentName:one.componentName);componentVersion(two.componentVersion!=null?two.componentVersion:one.componentVersion);path(two.path!=null?two.path:one.path);fileName(two.fileName!=null?two.fileName:one.fileName);extension(two.extension!=null?two.extension:one.extension);assetKind(two.assetKind!=null?two.assetKind:one.assetKind);return this;}
}
@Override public boolean equals(Object o){return o instanceof P2Attributes a&&Objects.equals(pluginName,a.pluginName)&&Objects.equals(componentName,a.componentName)&&Objects.equals(componentVersion,a.componentVersion)&&Objects.equals(path,a.path)&&Objects.equals(fileName,a.fileName)&&Objects.equals(extension,a.extension)&&Objects.equals(assetKind,a.assetKind);}
@Override public int hashCode(){return Objects.hash(pluginName,componentName,componentVersion,path,fileName,extension,assetKind);}
}
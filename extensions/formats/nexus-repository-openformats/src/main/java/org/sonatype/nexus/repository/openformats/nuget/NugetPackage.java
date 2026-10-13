/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats.nuget;
import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;
import org.sonatype.nexus.common.io.SafeXml;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
/** Read the nuspec without extracting paths or loading the complete package into memory. */
public final class NugetPackage {
 public record Metadata(String id,String lowerId,String version,byte[] nuspec,Map<String,Object> catalog){}
 public static String id(String id){if(id==null||!id.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid NuGet package ID");return id.toLowerCase(Locale.ROOT);}
 public static String version(String value){
  if(value==null||value.length()>255||!value.matches("[0-9]+(?:\\.[0-9]+){0,3}(?:-[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)?(?:\\+[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*)?"))throw new IllegalArgumentException("Invalid NuGet package version");
  String raw=NugetVersions.withoutBuild(value).toLowerCase(Locale.ROOT);String[] halves=raw.split("-",2);var parts=new ArrayList<String>();for(String part:halves[0].split("\\."))parts.add(new BigInteger(part).toString());while(parts.size()<3)parts.add("0");if(parts.size()==4&&parts.get(3).equals("0"))parts.remove(3);return String.join(".",parts)+(halves.length==2?"-"+halves[1]:"");
 }
 public static Metadata parse(TempBlob blob)throws Exception{
  byte[] nuspec=null;long expanded=0;int entries=0;
  try(var zip=new ZipInputStream(blob.get())){
   ZipEntry entry;byte[] buffer=new byte[8192];
   while((entry=zip.getNextEntry())!=null){
    if(++entries>30000||entry.getName().contains("\\")||entry.getName().matches(".*(^|/)\\.\\.(/|$).*"))throw new IllegalArgumentException("Invalid NuGet archive path or entry count");
    boolean manifest=entry.getName().toLowerCase(Locale.ROOT).endsWith(".nuspec")&&!entry.getName().contains("/");var output=manifest?new ByteArrayOutputStream():null;int read;
    while((read=zip.read(buffer))!=-1){expanded+=read;if(expanded>2L*1024*1024*1024)throw new IllegalArgumentException("Expanded NuGet archive exceeds maximum size");if(manifest){if(output.size()+read>4*1024*1024)throw new IllegalArgumentException("Nuspec exceeds maximum size");output.write(buffer,0,read);}}
    if(manifest){if(nuspec!=null)throw new IllegalArgumentException("Multiple package manifests");nuspec=output.toByteArray();}
   }
  }
  if(nuspec==null)throw new IllegalArgumentException("NuGet package nuspec missing");
  var factory=SafeXml.newdocumentBuilderFactory();factory.setNamespaceAware(true);var doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(nuspec));Element metadata=first(doc.getDocumentElement(),"metadata");if(metadata==null)throw new IllegalArgumentException("NuGet metadata missing");
  String originalId=text(metadata,"id"),lower=id(originalId),originalVersion=text(metadata,"version"),version=version(originalVersion);var catalog=new LinkedHashMap<String,Object>();catalog.put("id",originalId);catalog.put("version",version);catalog.put("semVer2",originalVersion.contains("+")||version.contains("-")&&version.substring(version.indexOf('-')).contains("."));
  for(String key:List.of("description","summary","title","authors","tags","projectUrl","licenseUrl","licenseExpression","iconUrl","language")){String value=text(metadata,key);if(!value.isBlank())catalog.put(key,value);}
  catalog.put("listed",true);catalog.put("published",java.time.Instant.now().toString());catalog.put("requireLicenseAcceptance",Boolean.parseBoolean(text(metadata,"requireLicenseAcceptance")));
  var packageTypes=new ArrayList<Map<String,String>>();Element types=first(metadata,"packageTypes");if(types!=null)for(var type:children(types,"packageType")){String name=type.getAttribute("name");if(!name.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Invalid NuGet package type");packageTypes.add(Map.of("name",name));}if(packageTypes.isEmpty())packageTypes.add(Map.of("name","Dependency"));catalog.put("packageTypes",packageTypes);
  var dependencies=new ArrayList<Map<String,Object>>();Element deps=first(metadata,"dependencies");if(deps!=null){var groups=children(deps,"group");if(groups.isEmpty())dependencies.add(dependencies(deps,""));else for(var group:groups)dependencies.add(dependencies(group,group.getAttribute("targetFramework")));}catalog.put("dependencyGroups",dependencies);
  return new Metadata(originalId,lower,version,nuspec,catalog);
 }
 private static Map<String,Object> dependencies(Element root,String framework){var dependencies=new ArrayList<Map<String,String>>();for(Element dep:children(root,"dependency")){String name=dep.getAttribute("id");id(name);var item=new LinkedHashMap<String,String>();item.put("id",name);item.put("range",dep.getAttribute("version").isBlank()?"(,)":dep.getAttribute("version"));dependencies.add(item);}return Map.of("targetFramework",framework,"dependencies",dependencies);}
 private static List<Element> children(Element e,String name){var result=new ArrayList<Element>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child&&name.equals(child.getLocalName()==null?child.getTagName():child.getLocalName()))result.add(child);return result;}
 private static Element first(Element e,String name){return children(e,name).stream().findFirst().orElse(null);}
 private static String text(Element e,String name){Element found=first(e,name);return found==null?"":found.getTextContent().strip();}
}

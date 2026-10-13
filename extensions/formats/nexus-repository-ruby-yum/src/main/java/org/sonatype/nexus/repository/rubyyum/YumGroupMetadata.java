/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.rubyyum;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import com.github.luben.zstd.ZstdInputStream;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import org.w3c.dom.*;
import static org.sonatype.nexus.repository.rubyyum.ProtocolSupport.*;
import static org.sonatype.nexus.repository.rubyyum.YumCodec.*;

final class YumGroupMetadata {
  static Response build(Context context,RubyYumGroupHandler handler,org.sonatype.nexus.repository.group.GroupHandler.DispatchedRepositories dispatched)throws Exception {
    String path=path(context),root=path.substring(0,path.length()-"/repodata/repomd.xml".length());
    var store=context.getRepository().facet(RubyYumContentFacet.class);
    synchronized(store) {
      var sources=handler.fetch(context,path,dispatched);
      var merged=new LinkedHashMap<String,Document>();
      var secondaryIds=new HashMap<String,Set<String>>();
      var seenCoordinates=new HashSet<String>();var allowedIds=new HashSet<String>();boolean found=false;
      for(var source:sources.entrySet()) {
        Response response=source.getValue();if(!response.getStatus().isSuccessful()||response.getPayload()==null)continue;
        found=true;Document repomd=parse(read(response.getPayload(),16*1024*1024));var data=repomd.getElementsByTagNameNS("*","data");
        // Process primary first so filelists/other contain exactly selected RPMs.
        var byType=new HashMap<String,String>();
        for(int i=0;i<data.getLength();i++){Element e=(Element)data.item(i);String type=e.getAttribute("type");if(!Set.of("primary","filelists","other").contains(type))continue;var location=e.getElementsByTagNameNS("*","location");if(location.getLength()!=1)throw new IllegalArgumentException("Invalid Yum metadata location");byType.put(type,((Element)location.item(0)).getAttribute("href"));}
        if(!byType.containsKey("primary"))throw new IllegalArgumentException("Yum member has no primary metadata");
        for(String type:List.of("primary","filelists","other")) {
          String href=byType.get(type);if(href==null)continue;
          String sourcePath=resolved(root,href);
          var memberResponse=handler.fetchMember(context,source.getKey(),sourcePath,dispatched);
          if(!memberResponse.getStatus().isSuccessful()||memberResponse.getPayload()==null)throw new IllegalArgumentException("Yum member metadata unavailable");
          byte[] compressed=read(memberResponse.getPayload(),64*1024*1024);
          Document member=parse(inflate(compressed,href));
          Document target=merged.get(type);if(target==null){target=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();Element element=(Element)target.importNode(member.getDocumentElement(),false);target.appendChild(element);merged.put(type,target);}
          var packages=member.getElementsByTagNameNS("*","package");
          for(int i=0;i<packages.getLength();i++) {
            Element e=(Element)packages.item(i);String id;
            if(type.equals("primary")) {
              id=textOf(e,"checksum");Element version=(Element)e.getElementsByTagNameNS("*","version").item(0);
              String coordinate=textOf(e,"name")+"|"+textOf(e,"arch")+"|"+version.getAttribute("epoch")+"|"+version.getAttribute("ver")+"|"+version.getAttribute("rel");
              if(!seenCoordinates.add(coordinate))continue;
              allowedIds.add(id);
              var locations=e.getElementsByTagNameNS("*","location");if(locations.getLength()!=1)throw new IllegalArgumentException("Invalid RPM location");
              Element location=(Element)locations.item(0);String rpmPath=resolved(root,location.getAttribute("href"));
              location.setAttribute("href","__members/"+source.getKey().getName()+rpmPath);
            } else {id=e.getAttribute("pkgid");if(!allowedIds.contains(id)||!secondaryIds.computeIfAbsent(type,k->new HashSet<>()).add(id))continue;}
            target.getDocumentElement().appendChild(target.importNode(e,true));
          }
        }
      }
      if(!found)return response(404,null);
      var entries=new ArrayList<RepomdEntry>();
      for(var entry:merged.entrySet()) {
        Document document=entry.getValue();document.getDocumentElement().setAttribute("packages",Integer.toString(document.getDocumentElement().getElementsByTagNameNS("*","package").getLength()));
        MetadataFile file=gzipMetadata(xml(document));String relative="repodata/"+file.fileName();
        store.put(root+"/"+relative,new BytesPayload(file.bytes(),"application/gzip"),"METADATA",null,null);entries.add(new RepomdEntry(entry.getKey(),relative,file));
      }
      return response(200,new BytesPayload(repomdXml(entries).getBytes(StandardCharsets.UTF_8),"application/xml"));
    }
  }
  private static String resolved(String root,String href) {
    URI value=URI.create(href);
    if(value.isAbsolute()||value.getRawQuery()!=null||value.getRawFragment()!=null||href.contains("\\")||href.contains("..")||href.startsWith("/"))throw new IllegalArgumentException("Unsafe Yum metadata location");
    String path=(root+"/"+href).replaceAll("/+","/");if(!path.startsWith("/"))path="/"+path;return path;
  }
  private static String textOf(Element e,String name) {var nodes=e.getElementsByTagNameNS("*",name);if(nodes.getLength()!=1)throw new IllegalArgumentException("Invalid Yum package metadata");return nodes.item(0).getTextContent();}
  static Document parse(byte[] bytes)throws Exception {
    var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
  }
  private static byte[] inflate(byte[] bytes,String name)throws IOException {
    InputStream raw=new ByteArrayInputStream(bytes);InputStream input=name.endsWith(".gz")?new GZIPInputStream(raw):name.endsWith(".xz")?new XZCompressorInputStream(raw):name.endsWith(".bz2")?new BZip2CompressorInputStream(raw):name.endsWith(".zst")?new ZstdInputStream(raw):raw;
    try(input){byte[] result=input.readNBytes(128*1024*1024+1);if(result.length>128*1024*1024)throw new IllegalArgumentException("Yum metadata exceeds limit");return result;}
  }
  private static String xml(Document document)throws Exception {
    var factory=TransformerFactory.newInstance();factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");var out=new StringWriter();factory.newTransformer().transform(new DOMSource(document),new StreamResult(out));return out.toString();
  }
}

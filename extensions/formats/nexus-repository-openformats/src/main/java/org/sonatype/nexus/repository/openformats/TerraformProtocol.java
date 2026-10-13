/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.bouncycastle.bcpg.*;
import org.sonatype.nexus.repository.security.GpgUtils;
import org.sonatype.nexus.repository.view.*;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
public final class TerraformProtocol {
 static final String NAME="[A-Za-z0-9][A-Za-z0-9_-]{0,63}";
 static boolean valid(String p){return p.equals("/.well-known/terraform.json")||p.matches("/v1/providers/"+NAME+"/"+NAME+"/(?:versions|"+CargoProtocol.VERSION+"/(?:download/"+NAME+"/"+NAME+"|"+NAME+"/"+NAME+"/package\\.zip|SHA256SUMS(?:\\.sig)?|metadata\\.json))")||p.matches("/v1/modules/"+NAME+"/"+NAME+"/"+NAME+"/(?:versions|"+CargoProtocol.VERSION+"/(?:download|archive\\.tar\\.gz))");}
 public static Response handle(Context c,OpenContentFacet store)throws Exception {
  String p=path(c),method=c.getRequest().getAction();if(!valid(p))return response(404,null);
  if(p.equals("/.well-known/terraform.json"))return json(200,Map.of("providers.v1",base(c)+"/v1/providers/","modules.v1",base(c)+"/v1/modules/"));
  var parts=p.split("/");boolean provider=parts[2].equals("providers");
  if(method.equals("PUT")&&!p.endsWith("/versions")&&!p.contains("/download")) {
   synchronized(store){if(store.get(p).isPresent())return json(409,Map.of("errors",List.of("Release artifact already exists")));
    if(p.endsWith("/package.zip"))return publishProvider(c,store,p,parts);
    if(p.endsWith("/archive.tar.gz")){store.put(p,c.getRequest().getPayload(),"TERRAFORM_MODULE",parts[3]+"/"+parts[4]+"/"+parts[5],parts[6]);return response(201,null);}
    // Manually supplied release metadata/checksums are permitted for imported signed providers.
    if(p.endsWith("/metadata.json")){var m=json(c.getRequest().getPayload());if(!m.path("protocols").isArray())throw new IllegalArgumentException("protocols array required");store.json(p,m);return response(201,null);}
    store.put(p,c.getRequest().getPayload(),"TERRAFORM_SIGNATURE",parts[3]+"/"+parts[4],parts[5]);return response(201,null);
   }
  }
  if(!List.of("GET","HEAD").contains(method))return response(405,null);
  if(p.endsWith("/versions"))return versions(c,store,p,provider);
  if(provider&&p.contains("/download/"))return providerDownload(c,store,parts);
  if(!provider&&p.endsWith("/download")){String target=p.substring(0,p.length()-9)+"/archive.tar.gz";return store.get(target).isPresent()?new Response.Builder().status(Status.success(204)).header("X-Terraform-Get",base(c)+target).build():response(404,null);}
  return store.get(p).map(a->response(200,a)).orElseGet(()->response(404,null));
 }
 private static Response publishProvider(Context c,OpenContentFacet store,String p,String[] parts)throws Exception {
  String release="/v1/providers/"+parts[3]+"/"+parts[4]+"/"+parts[5];
  var signing=c.getRepository().getConfiguration().attributes("terraformSigning");String privateKey=signing.get("keypair",String.class,""),passphrase=signing.get("passphrase",String.class,"");
  if(!GpgUtils.isConfigured(privateKey,passphrase))throw new IllegalArgumentException("Valid Terraform signing keypair required");
  byte[] data=read(c.getRequest().getPayload(),256*1024*1024);if(data.length<4||data[0]!='P'||data[1]!='K')throw new IllegalArgumentException("Provider package must be ZIP");
  String filename="terraform-provider-"+parts[4]+"_"+parts[5]+"_"+parts[6]+"_"+parts[7]+".zip";
  String previous=new String(Optional.ofNullable(store.bytes(release+"/SHA256SUMS",1024*1024)).orElse(new byte[0]),StandardCharsets.UTF_8),checksums=previous+sha256(data)+"  "+filename+"\n";
  byte[] armored=GpgUtils.signExternal(new ByteArrayInputStream(checksums.getBytes(StandardCharsets.UTF_8)),privateKey,passphrase);
  byte[] signature; try(var decoded=org.bouncycastle.openpgp.PGPUtil.getDecoderStream(new ByteArrayInputStream(armored))){signature=decoded.readAllBytes();}
  var key=GpgUtils.getPublicKey(privateKey);var out=new ByteArrayOutputStream();try(var armor=new ArmoredOutputStream(out)){key.encode(armor);}
  var keyInfo=Map.of("key_id",String.format("%016X",key.getKeyID()),"ascii_armor",out.toString(StandardCharsets.UTF_8));
  store.put(p,new BytesPayload(data,"application/zip"),"TERRAFORM_PROVIDER",parts[3]+"/"+parts[4],parts[5]);
  store.put(release+"/SHA256SUMS",new BytesPayload(checksums.getBytes(StandardCharsets.UTF_8),"text/plain"),"METADATA",null,null);
  store.put(release+"/SHA256SUMS.sig",new BytesPayload(signature,"application/pgp-signature"),"METADATA",null,null);
  store.json(release+"/metadata.json",Map.of("protocols",List.of("5.0","6.0"),"signing_keys",Map.of("gpg_public_keys",List.of(keyInfo))));
  return response(201,null);
 }
 private static Response versions(Context c,OpenContentFacet store,String p,boolean provider)throws Exception {
  String prefix=p.substring(0,p.length()-9);var versions=new TreeMap<String,Object>();
  for(var a:store.all())if(a.path().startsWith(prefix+"/")&&(provider?a.path().endsWith("/package.zip"):a.path().endsWith("/archive.tar.gz"))){String suffix=a.path().substring(prefix.length()+1);String[] segments=suffix.split("/");String version=segments[0];
   if(provider){@SuppressWarnings("unchecked") var entry=(Map<String,Object>)versions.computeIfAbsent(version,v->new LinkedHashMap<>(Map.of("version",v,"protocols",List.of("5.0","6.0"),"platforms",new ArrayList<>())));@SuppressWarnings("unchecked") var platforms=(List<Object>)entry.get("platforms");platforms.add(Map.of("os",segments[1],"arch",segments[2]));}
   else versions.put(version,Map.of("version",version));
  }
  return json(200,provider?Map.of("versions",versions.values()):Map.of("modules",List.of(Map.of("versions",versions.values()))));
 }
 private static Response providerDownload(Context c,OpenContentFacet store,String[] p)throws Exception {
  String release="/v1/providers/"+p[3]+"/"+p[4]+"/"+p[5],artifact=release+"/"+p[7]+"/"+p[8]+"/package.zip";var content=store.get(artifact);var bytes=store.bytes(release+"/metadata.json",1024*1024);
  if(content.isEmpty()||bytes==null)return response(404,null);var m=JSON.readTree(bytes);String filename="terraform-provider-"+p[4]+"_"+p[5]+"_"+p[7]+"_"+p[8]+".zip";
  String sum=sha256(read(content.get(),256*1024*1024));
  return json(200,Map.of("protocols",m.path("protocols"),"os",p[7],"arch",p[8],"filename",filename,"download_url",base(c)+artifact,"shasums_url",base(c)+release+"/SHA256SUMS","shasums_signature_url",base(c)+release+"/SHA256SUMS.sig","shasum",sum,"signing_keys",m.path("signing_keys")));
 }
}

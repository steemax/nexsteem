/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.openformats;
import java.util.*;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.view.*;
import org.springframework.stereotype.Component;
import static org.sonatype.nexus.repository.openformats.ProtocolSupport.*;
@Component
public class OpenProtocolHandler implements Handler {
 private final OpenGroupHandler groups;
 private final org.sonatype.nexus.repository.proxy.ProxyHandler proxy;
 public OpenProtocolHandler(OpenGroupHandler groups,org.sonatype.nexus.repository.proxy.ProxyHandler proxy){this.groups=groups;this.proxy=proxy;}
 @Override public Response handle(Context c)throws Exception {
  try {
   String format=c.getRepository().getFormat().getValue(),type=c.getRepository().getType().getValue(),p=path(c),method=c.getRequest().getAction();var content=c.getRepository().facet(OpenContentFacet.class);
   if(format.equals("cargo")&&p.equals("/config.json"))return CargoProtocol.handle(c,content);
   if(format.equals("cargo")&&c.getRepository().getConfiguration().attributes("cargo").get("requireAuthentication",Boolean.class,false)&&!org.apache.shiro.SecurityUtils.getSubject().isAuthenticated())return new Response.Builder().status(Status.failure(401)).header("WWW-Authenticate","Basic realm=\"Nexus Cargo\"").build();
   if(format.equals("terraform")&&p.equals("/.well-known/terraform.json"))return TerraformProtocol.handle(c,content);
   boolean valid=p.matches("/_remote/[a-f0-9]{64}")||switch(format){case "cargo"->CargoProtocol.valid(p);case "gitlfs"->GitLfsProtocol.valid(p);case "go"->p.matches("/.+/(?:@v/(?:list|v[^/]+\\.(?:info|mod|zip))|@latest)");case "swift"->SwiftProtocol.valid(p);case "terraform"->TerraformProtocol.valid(p);case "composer"->p.equals("/packages.json")||p.startsWith("/p2/")&&p.endsWith(".json")||p.startsWith("/p/")&&p.endsWith(".json")||p.matches("/[^/]+\\$[a-f0-9]+\\.json");case "huggingface"->p.matches("/(?:api/(?:models|datasets|spaces)(?:/.+)?|(?:datasets/|spaces/)?[^/]+/[^/]+/(?:resolve|raw)/.+)");default->false;};
   if(!valid)return response(404,null);
   if(type.equals("group"))return groups.handle(c);
   if(type.equals("proxy")) {
    if(!List.of("GET","HEAD").contains(method))return response(405,null);
    Response response=proxy.handle(c); if(!(response.getPayload() instanceof Content)) return response; Content result=(Content)response.getPayload(); var asset=result.getAttributes().get(org.sonatype.nexus.repository.content.Asset.class);var metadataBase=asset==null?null:asset.attributes().get("openMetadataBase");if(metadataBase instanceof String previous&&!previous.equals(base(c))&&List.of("composer","swift","terraform").contains(format)){var node=JSON.readTree(read(result,16*1024*1024));rebase(node,previous,base(c));result=new Content(new org.sonatype.nexus.repository.view.payloads.BytesPayload(JSON.writeValueAsBytes(node),result.getContentType()));}
    if(format.equals("terraform")&&p.startsWith("/v1/modules/")&&p.endsWith("/download")){var metadata=JSON.readTree(read(result,1024*1024));if(metadata.hasNonNull("openModuleDownload"))return new Response.Builder().status(Status.success(204)).header("X-Terraform-Get",metadata.path("openModuleDownload").asText()).build();}
    var builder=new Response.Builder().copy(response).payload(result);OpenProxyFacet.remoteHeaders(c).forEach((name,value)->builder.headers().replace(name,value));
    if(asset!=null){Object attrs=asset.attributes().get("openProtocolHeaders");if(attrs instanceof Map<?,?> map)for(var e:map.entrySet()){String name=String.valueOf(e.getKey()),value=String.valueOf(e.getValue());if(format.equals("huggingface")&&name.equalsIgnoreCase("Link"))value=repositoryLinks(value,c.getRepository().facet(ProxyFacet.class).getRemoteUrl(),base(c));builder.headers().replace(name,value);}}
    Response outgoing=builder.build();return format.equals("swift")?SwiftProtocol.result(outgoing):outgoing;
   }
   return org.sonatype.nexus.transaction.Transactional.operation.withStore(content).throwing(Exception.class).call(() -> switch(format){case "cargo"->CargoProtocol.handle(c,content);case "gitlfs"->GitLfsProtocol.handle(c,content);case "swift"->SwiftProtocol.handle(c,content);case "terraform"->TerraformProtocol.handle(c,content);default->response(405,null);});
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){return json(400,Map.of("errors",List.of(Map.of("detail","Malformed JSON body"))));}
  catch(IllegalArgumentException e){return json(400,Map.of("errors",List.of(Map.of("detail",e.getMessage()==null?"Invalid protocol request":e.getMessage()))));}
 }
}

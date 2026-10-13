/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.repository.cocoapods.datastore;
import java.net.URI;
import java.util.Arrays;
import org.apache.http.auth.*;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.sonatype.nexus.httpclient.config.*;
/** Scope repository credentials to its configured origin, including across redirects. */
final class CocoapodsCredentials {
 static void add(BasicCredentialsProvider provider,String host,int port,AuthenticationConfiguration authentication){
  Credentials credentials=null;
  if(authentication instanceof UsernameAuthenticationConfiguration config){char[] password=config.getPassword()==null?new char[0]:config.getPassword().decrypt();try{credentials=new UsernamePasswordCredentials(config.getUsername(),new String(password));}finally{Arrays.fill(password,'\0');}}
  if(authentication instanceof NtlmAuthenticationConfiguration config){char[] password=config.getPassword()==null?new char[0]:config.getPassword().decrypt();try{credentials=new NTCredentials(config.getUsername(),new String(password),config.getHost(),config.getDomain());}finally{Arrays.fill(password,'\0');}}
  if(credentials!=null)provider.setCredentials(new AuthScope(host,port),credentials);
 }
 static int port(URI uri){return uri.getPort()<0?("https".equalsIgnoreCase(uri.getScheme())?443:80):uri.getPort();}
}

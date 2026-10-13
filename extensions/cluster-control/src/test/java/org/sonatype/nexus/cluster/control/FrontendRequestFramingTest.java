/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrontendRequestFramingTest {
  @Test public void headAndEmptyPostKeepConnectionUsableForManifestPutWithZeroDrain()throws Exception {
    new HttpAdmission(new Properties());
    var errors=new ConcurrentLinkedQueue<Throwable>();
    var connections=ConcurrentHashMap.<Integer>newKeySet();
    var executor=Executors.newVirtualThreadPerTaskExecutor();
    HttpServer backend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);
    backend.setExecutor(executor);
    backend.createContext("/",exchange->{try{
      long bytes=exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
      if(exchange.getRequestMethod().equals("PUT")&&bytes!=600)throw new IOException("Manifest body changed");
      if(exchange.getRequestMethod().equals("HEAD")){
        exchange.getResponseHeaders().set("Content-Length","91000");exchange.sendResponseHeaders(200,-1);
      }else exchange.sendResponseHeaders(201,-1);
    }catch(Throwable failure){errors.add(failure);}finally{exchange.close();}});
    backend.start();
    var outbound=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    HttpServer frontend=HttpServer.create(new InetSocketAddress("127.0.0.1",0),32);
    frontend.setExecutor(executor);
    frontend.createContext("/",exchange->{try{
      connections.add(exchange.getRemoteAddress().getPort());
      boolean empty=ClusterSupervisor.completeEmptyRequest(exchange);
      var body=empty?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofInputStream(exchange::getRequestBody);
      var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+backend.getAddress().getPort()+exchange.getRequestURI()))
          .timeout(Duration.ofSeconds(5)).method(exchange.getRequestMethod(),body).build();
      var response=outbound.send(request,HttpResponse.BodyHandlers.ofInputStream());
      try(var in=response.body()){
        if(exchange.getRequestMethod().equals("HEAD"))exchange.getResponseHeaders().set("Content-Length",response.headers().firstValue("Content-Length").orElseThrow());
        exchange.sendResponseHeaders(response.statusCode(),-1);
      }
    }catch(Throwable failure){errors.add(failure);}finally{exchange.close();}});
    frontend.start();
    var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    try{
      String base="http://127.0.0.1:"+frontend.getAddress().getPort();
      for(int i=0;i<100;i++)for(String method:new String[]{"HEAD","POST","PUT"}){
        var body=method.equals("PUT")?HttpRequest.BodyPublishers.ofByteArray(new byte[600]):HttpRequest.BodyPublishers.noBody();
        var request=HttpRequest.newBuilder(URI.create(base+"/"+i)).timeout(Duration.ofSeconds(5)).method(method,body).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(method.equals("HEAD")?200:201,response.statusCode());
        if(method.equals("HEAD"))assertEquals("91000",response.headers().firstValue("Content-Length").orElseThrow());
      }
      assertTrue(errors.toString(),errors.isEmpty());
      assertTrue("Keepalive was not reused: "+connections.size(),connections.size()<=4);
    }finally{frontend.stop(0);backend.stop(0);client.close();outbound.close();executor.close();}
  }
}

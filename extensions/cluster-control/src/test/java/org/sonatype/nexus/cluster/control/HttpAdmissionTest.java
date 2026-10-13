/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Properties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import com.sun.net.httpserver.HttpServer;

public class HttpAdmissionTest {
  @Test public void reservesControlCapacityWhenAllProxyStreamsAreOccupied(){
    HttpAdmission capacity=new HttpAdmission(new Properties());
    assertTrue(capacity.proxy.tryAcquire(32));assertFalse(capacity.proxy.tryAcquire());
    assertTrue(capacity.control.tryAcquire());capacity.control.release();capacity.proxy.release(32);
    Properties invalid=new Properties();invalid.setProperty("NX_CLUSTER_HTTP_MAX_CONNECTIONS","-1");
    try{new HttpAdmission(invalid);fail("Unlimited connections accepted");}catch(IllegalArgumentException expected){}
  }
  @Test public void rejectionDoesNotWaitForADeclaredButUnsentUploadBody()throws Exception {
    new HttpAdmission(new Properties());
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),10);
    var executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
    server.createContext("/",e->{try{e.getResponseHeaders().set("Connection","close");e.sendResponseHeaders(503,4);e.getResponseBody().write("busy".getBytes(StandardCharsets.US_ASCII));}finally{e.close();}});server.start();
    try(Socket client=new Socket("127.0.0.1",server.getAddress().getPort())){
      client.setSoTimeout(3000);long begin=System.nanoTime();
      client.getOutputStream().write("POST / HTTP/1.1\r\nHost: local\r\nContent-Length: 1000000\r\n\r\nx".getBytes(StandardCharsets.US_ASCII));client.getOutputStream().flush();
      String response=new String(client.getInputStream().readAllBytes(),StandardCharsets.US_ASCII);
      assertTrue(response,response.startsWith("HTTP/1.1 503"));assertTrue(response.endsWith("busy"));assertTrue(System.nanoTime()-begin<3_000_000_000L);
    }finally{server.stop(0);executor.close();}
  }
}

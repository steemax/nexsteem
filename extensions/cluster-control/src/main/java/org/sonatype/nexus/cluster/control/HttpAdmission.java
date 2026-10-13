/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.cluster.control;
import java.util.Properties;
import java.util.concurrent.Semaphore;

/** Bounded active streams and independent capacity for member health and status. */
final class HttpAdmission {
  final Semaphore proxy;
  final Semaphore control;
  HttpAdmission(Properties values){
    int active=number(values,"NX_CLUSTER_MAX_PROXY_REQUESTS",32,8,256);
    int health=number(values,"NX_CLUSTER_MAX_CONTROL_REQUESTS",4,2,32);
    proxy=new Semaphore(active);control=new Semaphore(health);
    // These must be set BEFORE any frontend or ReplicaServer initializes JDK ServerConfig.
    System.setProperty("jdk.httpserver.maxConnections",Integer.toString(number(values,"NX_CLUSTER_HTTP_MAX_CONNECTIONS",128,active+health+16,4096)));
    System.setProperty("sun.net.httpserver.maxIdleConnections","16");
    System.setProperty("sun.net.httpserver.maxReqHeaders","100");
    System.setProperty("sun.net.httpserver.maxReqHeaderSize",Integer.toString(number(values,"NX_CLUSTER_HTTP_MAX_HEADER_BYTES",32768,4096,262144)));
    System.setProperty("sun.net.httpserver.maxReqTime",Integer.toString(number(values,"NX_CLUSTER_HTTP_MAX_REQUEST_SECONDS",3600,60,86400)));
    System.setProperty("sun.net.httpserver.maxRspTime",Integer.toString(number(values,"NX_CLUSTER_HTTP_MAX_RESPONSE_SECONDS",3600,60,86400)));
    // Rejected uploads must close their connection without waiting for any unread body.
    System.setProperty("sun.net.httpserver.drainAmount","0");
  }
  static int number(Properties values,String name,int fallback,int minimum,int maximum){
    int value=Integer.parseInt(values.getProperty(name,Integer.toString(fallback)));
    if(value<minimum||value>maximum)throw new IllegalArgumentException(name+" must be "+minimum+".."+maximum);
    return value;
  }
}

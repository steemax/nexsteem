/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/** JNDI creates the socket synchronously. Each connection uses its own trusted TLS context. */
public class LdapSocketFactory extends SSLSocketFactory {
  private static final ThreadLocal<SSLSocketFactory> CURRENT = new ThreadLocal<>();
  private final SSLSocketFactory delegate;
  public LdapSocketFactory() {
    delegate = CURRENT.get();
    if (delegate == null) { throw new IllegalStateException("No LDAP TLS context"); }
  }
  public static SocketFactory getDefault() { return new LdapSocketFactory(); }
  static void use(SSLSocketFactory factory) { CURRENT.set(factory); }
  static void clear() { CURRENT.remove(); }
  public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
  public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
  public Socket createSocket() throws IOException { return delegate.createSocket(); }
  public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException { return delegate.createSocket(socket, host, port, close); }
  public Socket createSocket(String host, int port) throws IOException { return delegate.createSocket(host, port); }
  public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return delegate.createSocket(host, port, local, localPort); }
  public Socket createSocket(InetAddress host, int port) throws IOException { return delegate.createSocket(host, port); }
  public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return delegate.createSocket(host, port, local, localPort); }
}

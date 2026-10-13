/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapName;
import org.sonatype.nexus.ssl.TrustStore;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Lazy;

/** LDAPv3 bind/search with bounded results and timeouts; no referral credential forwarding. */
@Component
public class LdapDirectory {
  public record DirectoryUser(String username, String realName, String email, String dn, Set<String> membership) { }
  private final TrustStore trust;
  public LdapDirectory(@Lazy TrustStore trust) { this.trust = trust; }

  private InitialLdapContext open(LdapServer server, String username, String password, String scheme) throws NamingException {
    Hashtable<String, Object> environment = new Hashtable<>();
    environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
    environment.put(Context.PROVIDER_URL, server.endpoint());
    environment.put(Context.SECURITY_AUTHENTICATION, scheme);
    environment.put(Context.REFERRAL, "throw");
    environment.put("com.sun.jndi.ldap.connect.timeout", String.valueOf(server.connectionTimeout * 1000));
    environment.put("com.sun.jndi.ldap.read.timeout", String.valueOf(server.connectionTimeout * 1000));
    if (!"none".equals(scheme)) {
      if (username == null || username.isBlank() || password == null || password.isEmpty()) {
        throw new javax.naming.AuthenticationException("LDAP bind requires nonempty credentials");
      }
      environment.put(Context.SECURITY_PRINCIPAL, username);
      environment.put(Context.SECURITY_CREDENTIALS, password);
      if (server.authRealm != null && !server.authRealm.isBlank()) { environment.put("java.naming.security.sasl.realm", server.authRealm); }
    }
    try {
      if ("ldaps".equals(server.protocol) && server.useTrustStore) {
        LdapSocketFactory.use(trust.getSSLContext().getSocketFactory());
        environment.put("java.naming.ldap.factory.socket", LdapSocketFactory.class.getName());
      }
      return new InitialLdapContext(environment, null);
    }
    finally { LdapSocketFactory.clear(); environment.remove(Context.SECURITY_CREDENTIALS); }
  }

  public void verifyConnection(LdapServer server, String password) throws NamingException {
    server.validate(false);
    InitialLdapContext context = open(server, server.authUsername, password, server.authScheme);
    try { context.getAttributes(server.searchBase, new String[]{"objectClass"}); }
    finally { context.close(); }
  }

  public List<DirectoryUser> users(LdapServer server, String password, String username, boolean prefix, int limit) throws NamingException {
    server.validate(true);
    InitialLdapContext context = open(server, server.authUsername, password, server.authScheme);
    try {
      String filter = "(&(objectClass=" + server.userObjectClass + ")";
      List<Object> arguments = new ArrayList<>();
      if (username != null && !username.isEmpty()) {
        filter += "(" + server.userIdAttribute + "={0}" + (prefix ? "*" : "") + ")";
        arguments.add(username);
      }
      if (server.userLdapFilter != null && !server.userLdapFilter.isBlank()) {
        filter += server.userLdapFilter;
      }
      filter += ")";
      List<SearchResult> entries = search(context, base(server.userBaseDn, server.searchBase), filter,
          arguments.toArray(), server.userSubtree, limit, server.connectionTimeout);
      List<DirectoryUser> result = new ArrayList<>();
      for (SearchResult entry : entries) {
        Attributes attributes = entry.getAttributes();
        String id = value(attributes, server.userIdAttribute);
        if (id == null || id.isBlank()) { continue; }
        String dn = entry.getNameInNamespace();
        Set<String> groups = new LinkedHashSet<>();
        if (server.ldapGroupsAsRoles && "dynamic".equals(server.groupType)) {
          Attribute memberOf = attributes.get(server.userMemberOfAttribute);
          if (memberOf != null) {
            NamingEnumeration<?> values = memberOf.getAll();
            try {
              while (values.hasMore() && groups.size() < 1000) {
                LdapName name = new LdapName(String.valueOf(values.next()));
                if (!name.isEmpty()) { groups.add(String.valueOf(name.getRdn(name.size() - 1).getValue())); }
              }
            }
            finally { values.close(); }
          }
        }
        else if (server.ldapGroupsAsRoles) {
          String member = server.groupMemberFormat.replace("${username}", id).replace("${dn}", dn);
          String groupFilter = "(&(objectClass=" + server.groupObjectClass + ")(" + server.groupMemberAttribute + "={0}))";
          for (SearchResult group : search(context, base(server.groupBaseDn, server.searchBase), groupFilter,
              new Object[]{member}, server.groupSubtree, 1000, server.connectionTimeout)) {
            String role = value(group.getAttributes(), server.groupIdAttribute);
            if (role != null) { groups.add(role); }
          }
        }
        result.add(new DirectoryUser(id, value(attributes, server.userRealNameAttribute),
            value(attributes, server.userEmailAddressAttribute), dn, Set.copyOf(groups)));
      }
      return result;
    }
    finally { context.close(); }
  }

  public DirectoryUser authenticate(LdapServer server, String systemPassword, String username, String password) throws NamingException {
    if (password == null || password.isEmpty()) { throw new javax.naming.AuthenticationException("Empty LDAP user password"); }
    List<DirectoryUser> users = users(server, systemPassword, username, false, 2);
    if (users.size() != 1) { throw new javax.naming.NameNotFoundException("LDAP user was not found uniquely"); }
    DirectoryUser user = users.get(0);
    InitialLdapContext context = open(server, user.dn(), password, "simple");
    try { context.getAttributes(user.dn(), new String[]{"objectClass"}); }
    finally { context.close(); }
    return user;
  }

  public Set<String> groups(LdapServer server, String password, String query) throws NamingException {
    if (!server.ldapGroupsAsRoles) { return Set.of(); }
    server.validate(true);
    InitialLdapContext context = open(server, server.authUsername, password, server.authScheme);
    try {
      Set<String> result = new LinkedHashSet<>();
      for (SearchResult entry : search(context, base(server.groupBaseDn, server.searchBase),
          "(&(objectClass=" + server.groupObjectClass + ")(" + server.groupIdAttribute + "={0}*))",
          new Object[]{query == null ? "" : query}, server.groupSubtree, 1000, server.connectionTimeout)) {
        String value = value(entry.getAttributes(), server.groupIdAttribute);
        if (value != null) { result.add(value); }
      }
      return result;
    }
    finally { context.close(); }
  }

  private static List<SearchResult> search(InitialLdapContext context, String base, String filter,
      Object[] arguments, boolean subtree, int limit, int timeout) throws NamingException {
    SearchControls controls = new SearchControls();
    controls.setSearchScope(subtree ? SearchControls.SUBTREE_SCOPE : SearchControls.ONELEVEL_SCOPE);
    controls.setCountLimit(limit + 1L); controls.setTimeLimit(timeout * 1000); controls.setReturningObjFlag(false);
    NamingEnumeration<SearchResult> entries = context.search(base, filter, arguments, controls);
    try {
      List<SearchResult> result = new ArrayList<>();
      while (entries.hasMore() && result.size() < limit) { result.add(entries.next()); }
      return result;
    }
    finally { entries.close(); }
  }
  private static String base(String relative, String root) {
    return relative == null || relative.isBlank() ? root : root == null || root.isBlank() ? relative : relative + "," + root;
  }
  private static String value(Attributes attributes, String name) throws NamingException {
    if (name == null || name.isBlank()) { return null; }
    Attribute attribute = attributes.get(name);
    return attribute == null || attribute.size() == 0 ? null : String.valueOf(attribute.get());
  }
}

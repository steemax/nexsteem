/* SPDX-License-Identifier: EPL-1.0 */
package org.sonatype.nexus.ldap;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

/** The fields used by the upstream LDAP UI and REST clients. */
public class LdapServer {
  public String id, name, url;
  public int order;
  public String protocol = "ldap", host, searchBase = "", authScheme = "none";
  public String authRealm, authUsername, authPassword;
  public int port = 389, connectionTimeout = 10, connectionRetryDelay = 60, maxIncidentsCount = 3;
  public boolean useTrustStore, userSubtree = true, groupSubtree = true, ldapGroupsAsRoles;
  public String userBaseDn = "", userObjectClass = "inetOrgPerson", userLdapFilter = "";
  public String userIdAttribute = "uid", userRealNameAttribute = "cn", userEmailAddressAttribute = "mail";
  public String userPasswordAttribute, userMemberOfAttribute, groupType = "static", groupBaseDn = "";
  public String groupIdAttribute = "cn", groupMemberAttribute = "member", groupMemberFormat = "${dn}";
  public String groupObjectClass = "groupOfNames";

  public void validate(boolean mapping) {
    if (name == null || name.isBlank() || name.length() > 255) { throw new IllegalArgumentException("LDAP name is required (maximum 255 characters)"); }
    protocol = protocol == null ? "" : protocol.toLowerCase(Locale.ROOT);
    if (!Set.of("ldap", "ldaps").contains(protocol) || host == null ||
        !host.matches("[a-zA-Z0-9._:-]+") || port < 1 || port > 65535) {
      throw new IllegalArgumentException("Invalid LDAP server address");
    }
    if (authScheme == null || !Set.of("none", "simple", "DIGEST-MD5", "CRAM-MD5").contains(authScheme)) {
      throw new IllegalArgumentException("Invalid LDAP authentication method");
    }
    if (!"none".equals(authScheme) && (authUsername == null || authUsername.isBlank())) {
      throw new IllegalArgumentException("LDAP bind username is required");
    }
    if (connectionTimeout < 1 || connectionTimeout > 120 || connectionRetryDelay < 1 ||
        connectionRetryDelay > 3600 || maxIncidentsCount < 1 || maxIncidentsCount > 20) {
      throw new IllegalArgumentException("Invalid LDAP connection limits");
    }
    dn(searchBase);
    if (mapping) {
      if (userIdAttribute == null || userIdAttribute.isBlank()) { throw new IllegalArgumentException("LDAP user ID attribute is required"); }
      dn(userBaseDn); dn(groupBaseDn);
      attribute(userIdAttribute); attribute(userRealNameAttribute); attribute(userEmailAddressAttribute);
      attribute(userPasswordAttribute); attribute(userMemberOfAttribute);
      attribute(groupIdAttribute); attribute(groupMemberAttribute);
      if (userObjectClass == null || !userObjectClass.matches("[a-zA-Z0-9._-]+")) {
        throw new IllegalArgumentException("Invalid LDAP user object class");
      }
      if (ldapGroupsAsRoles && (groupType == null || !Set.of("static", "dynamic").contains(groupType))) {
        throw new IllegalArgumentException("Invalid LDAP group type");
      }
      if (ldapGroupsAsRoles && "dynamic".equals(groupType) &&
          (userMemberOfAttribute == null || userMemberOfAttribute.isBlank())) {
        throw new IllegalArgumentException("Dynamic groups require a memberOf attribute");
      }
      if (ldapGroupsAsRoles && "static".equals(groupType) &&
          (groupObjectClass == null || !groupObjectClass.matches("[a-zA-Z0-9._-]+") ||
          groupIdAttribute == null || groupIdAttribute.isBlank() || groupMemberAttribute == null || groupMemberAttribute.isBlank() || groupMemberFormat == null ||
          !(groupMemberFormat.contains("${username}") || groupMemberFormat.contains("${dn}")))) {
        throw new IllegalArgumentException("Invalid LDAP static group mapping");
      }
      if (userLdapFilter != null && userLdapFilter.length() > 4096) {
        throw new IllegalArgumentException("LDAP filter is too long");
      }
    }
    url = endpoint() + "/" + searchBase;
  }

  public String endpoint() {
    try { return new URI(protocol, null, host, port, null, null, null).toString(); }
    catch (Exception failure) { throw new IllegalArgumentException("Invalid LDAP address"); }
  }

  private static void attribute(String value) {
    if (value != null && !value.isBlank() && !value.matches("[a-zA-Z][a-zA-Z0-9;-]*|[0-9.]+")) {
      throw new IllegalArgumentException("Invalid LDAP attribute name");
    }
  }
  private static void dn(String value) {
    try { new LdapName(value == null ? "" : value); }
    catch (InvalidNameException failure) { throw new IllegalArgumentException("Invalid LDAP distinguished name"); }
  }
}

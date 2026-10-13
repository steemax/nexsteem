# RubyGems and Yum

This module implements hosted, proxy and group recipes for both formats using
Nexus SQL ContentFacet and BlobStore. Repository permissions, routing rules,
negative cache, proxy validators, browse/search and cleanup use Core APIs.

RubyGems supports native push/download/yank, Marshal specs and quick gemspec,
compact indexes and dependency metadata. Groups merge indexes with member
priority. Enable `RubyGems API Key Realm` for CLI keys; Core ApiKeyService issues
and revokes them. Browser OIDC identities do not receive permanent local keys
automatically; access-token expiry remains enforced.

Yum parses RPM headers and generates primary/filelists/other and repomd indexes.
Hosted PUT/delete, cached proxies and merged group metadata preserve package
checksums. `yum.repodataDepth` supports 0–5. Original RPM signatures are retained;
this module does not sign generated repository indexes.

Malformed archives and optional checksum mismatches are rejected. Parsers have
size and complexity limits; YAML does not instantiate arbitrary Ruby classes,
and external XML entities are disabled. The common `ScopedProxyRequests` helper
scopes credentials to their origin and validates redirects and SSRF boundaries.

Native client checks are in `scripts/test-ruby-yum.py` and
`scripts/test-ruby-yum-proxy-auth.py`. They cover gem/Bundler, RPM/DNF, checksum,
API-key and proxy-authentication scenarios against a dedicated test installation.

Pure codecs were adapted from Apache-2.0 kkRepo commit
`9c2b87ca3851a180e929f8139803212846412a9c`. Its storage backend is not included.
See SOURCE-ORIGIN.json and retained META-INF LICENSE/NOTICE files. Nexus
integration uses EPL-1.0; imported codec files retain Apache-2.0.

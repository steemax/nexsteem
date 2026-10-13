# NexSteem branding

The blue/cyan three-node artwork is original project artwork by **steemax**,
distributed under EPL-1.0. It does not use Sonatype's chevrons or wordmark.
`assets/header-light.svg` and `assets/header-dark.svg` are the light/dark
wordmarks; `assets/mark.svg` is the compact node mark. `docs/images/logo.svg`
is the repository documentation copy of the light wordmark.

`build-support/branding-overlay.py` applies this visual identity only to the
managed source copy used by `make prepare`/extended builds, after the OIDC
overlay. `make prepare-core` and the pinned `upstream/nexus-public` checkout
retain their original branding and contents.

The overlay replaces Core header/login artwork, the classic compact header
mark, the loading wordmark, SVG favicons, browser/default title and the main
visible/accessibility name with **NexSteem**. It also replaces the registered
About/Help marks and adds an explicit NexSteem product label to About. The About
details still identify **Sonatype Nexus Repository Core** as the open core,
and its Legal Terms and Copyright tab is retained. The self-hosted login uses
the Core logo while edition state is loading, avoiding a temporary Pro logo. The onboarding
wizard bundles its own NexSteem SVG mark rather than fetching the shared favicon;
the artwork therefore changes with the compiled frontend bundle. Its welcome
and completion text identify NexSteem. Core classic and preview dashboard
headings identify NexSteem as well. These are narrow product presentation changes;
onboarding registration, password changes, anonymous access, license acceptance,
and state machines remain unchanged. React Community/Pro logo branches remain intact.
The shared login layout uses an optional Core-only alt label supplied by the
Core wrapper. Existing SVG copyright/license preambles are preserved.

It does not change authentication, privileges, edition checks, package names,
copyright notices, licenses or legal attribution. Legacy raster
icon files remain in the source tree; the main page explicitly selects the
new SVG icons. An operator's persisted custom UI title remains a setting and
is not migrated or overwritten. This is independent project branding, not a
claim of Sonatype endorsement.

All SVGs are self-contained static XML with no scripts, embedded images,
external links or font downloads. Exact single-occurrence anchors and expected
SVG structure are checked before any file is written. If an upstream update
changes these inputs, preparation stops for review instead of applying a broad
text replacement. Re-run against a fresh prepared source tree, not an already
branded copy.

# Contributing

Keep `upstream/nexus-public` at the unmodified official commit recorded in
`upstream.lock.json`. Implement extensions in `extensions/` and build overlays
in `build-support/`; overlays apply only to the disposable `build/source` tree.
Use `make update TAG=...` to change the upstream pin and commit the submodule
pointer together with the lockfile.

Run `make verify` and `python3 scripts/check-public-tree.py` before submitting
changes. Run the relevant protocol, security and failover tests against a
separate test installation before releasing an image. Compilation alone does
not establish protocol compatibility or HA correctness.

Test harnesses create dedicated Docker containers or Kind clusters. Read their
arguments and resource names before running them; never run fault tests against
an installation that contains data you need to keep.

Do not commit generated builds, credentials, kubeconfigs, dumps or deployment
logs. Preserve original licenses, copyright and source-origin records when
adapting third-party code. Report security issues through the private channel
in [SECURITY.md](SECURITY.md).

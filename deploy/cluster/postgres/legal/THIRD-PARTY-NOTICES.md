# PostgreSQL / Patroni container sources and notices

The NexSteem integration scripts are by **steemax**, licensed under EPL-1.0.
See `LICENSE.txt` and `SOURCE-INFO.txt` for the complete project source release.
These terms do not replace the licenses of the container dependencies.

| Component | Source and license |
|---|---|
| PostgreSQL 17 | Official `postgres` base image pinned in `deploy/cluster/postgres/Dockerfile`; PostgreSQL License. Exact packaged copyright is retained in `/usr/share/doc/postgresql-17/copyright` and `../components/PostgreSQL-copyright.txt`. Upstream source releases: <https://www.postgresql.org/ftp/source/>; license: <https://www.postgresql.org/about/licence/>. |
| Official PostgreSQL Docker integration | <https://github.com/docker-library/postgres>; original base-image scripts and notices are retained. |
| Patroni 4.1.5 | <https://github.com/patroni/patroni/tree/v4.1.5>; MIT license preserved in `licenses/Patroni-MIT.txt`. Source release: <https://github.com/patroni/patroni/archive/refs/tags/v4.1.5.tar.gz>. |
| Python dependencies | Versions recorded in `/opt/nexus-postgres/requirements.lock` and `/opt/patroni/requirements-installed.txt`; installed package notices remain in their `*.dist-info` directories. Obtain matching source distributions from <https://pypi.org/> using the recorded names and versions. |
| Debian / Python runtime packages | Original copyright files remain in `/usr/share/doc`; matching package sources: <https://sources.debian.org/>. Package-specific licenses apply. |

The project source archive includes the container Dockerfile, pinned Python
dependency versions and the NexSteem integration scripts. The upstream projects
retain copyright in their respective components; nothing here relicenses them.

# Contributing to Rocoto

Thanks for your interest in Rocoto!

## Ground rules

- Everything in this repository is written in **English**: code, comments, docs, commit messages.
- Rocoto is MIT licensed. Only add dependencies with an MIT-compatible license
  (MIT, BSD, Apache-2.0) and keep runtime dependencies to an absolute minimum –
  the WAR runs inside other people's application servers.
- Security-relevant behaviour must never be weakened by default. New features are
  **off** or **read-only** unless the administrator opts in.
- Every request path must go through `RequestPipeline`. Never call an `MBeanServer`
  directly from a servlet or endpoint.

## Building

```bash
mvn verify
```

Requires JDK 17+ and Maven 3.9+. `mvn verify` includes the integration tests, which deploy the
real WAR into an embedded Tomcat. CI additionally runs `ci/wildfly/smoke-test.sh` against a real
WildFly (needs Docker) and checks links and Mermaid diagrams (`ci/docs/`).

## Pull requests

1. Open an issue first for anything larger than a bug fix.
2. Add tests. Security features need negative tests as well.
3. Update the guide under `docs/guide/` and its Mermaid diagrams when behaviour changes.
4. Keep commits focused and write meaningful commit messages.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Do not use public issues.

## Application server releases

`ci.yml` tests pinned server versions; `server-compat.yml` runs the same smoke tests weekly against
the newest images and opens an issue when one breaks. How to reproduce and fix such a failure, and
how to add a server, is described in
[docs/guide/14-keeping-up-with-servers.md](docs/guide/14-keeping-up-with-servers.md).

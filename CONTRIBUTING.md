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

Requires JDK 17+ and Maven 3.9+. Integration tests that need Docker
(Testcontainers) only run with `-Pcontainer-it`.

## Pull requests

1. Open an issue first for anything larger than a bug fix.
2. Add tests. Security features need negative tests as well.
3. Update the guide under `docs/guide/` and its Mermaid diagrams when behaviour changes.
4. Keep commits focused and write meaningful commit messages.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Do not use public issues.

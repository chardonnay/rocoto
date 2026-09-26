# Changelog

All notable changes to Rocoto are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions follow
[Semantic Versioning](https://semver.org/). Until 1.0.0 a minor version may change configuration
keys or API details; such changes are listed under "Changed".

## [Unreleased]

### Added

- Windows: the CI builds and tests on `windows-latest`; installation notes for Windows and
  `setenv.bat` for Tomcat.
- PowerShell examples (Windows PowerShell 5.1 and PowerShell 7): client with read, bulk, exec,
  SSE and WebSocket, `test.ps1` for the Tomcat example, `get-token.ps1` / `call-rocoto.ps1` for
  the Keycloak example; first calls with `Invoke-RestMethod` in the installation guide.

### Changed

- Undertow 2.4 (was 2.3) in `rocoto-undertow`, Netty 4.2 (was 4.1) in `rocoto-netty`, SnakeYAML 2.7
  (was 2.3).

### Fixed

- The Undertow all-jar no longer contains unrelocated multi-release copies of wildfly-common
  (and, with Undertow 2.4, jboss-threads) classes, which could shadow the application's own
  classes when the jar is used as a Java agent.
- The error for an unknown attribute of a JDK MBean no longer repeats its prefix
  ("No such attribute: No such attribute: X").

## [0.1.0] - 2026-09-20

First public release.

### Added

- **Management API** (`/api/v1`): read, write and invoke JMX MBeans as JSON over HTTP in resource
  and RPC style, bulk requests, type-faithful JSON, OpenAPI 3 document and a JSON Schema per MBean.
- **Security pipeline**: authentication is always required; `auth.mode` `builtin` (HTTP Basic with
  PBKDF2, scoped API tokens, client certificates with OCSP/CRL checking, JWT via JWKS),
  `container` (Elytron, Tomcat/Payara realms, Jetty LoginService, Liberty user registry) or
  `chain`. Role based policy per MBean/attribute/operation, built-in deny list, masking of secrets,
  JSON audit log, credentials over TLS only, fail-closed (503) on configuration errors.
- **Metrics**: Prometheus/OpenMetrics endpoint `/metrics` and OTLP/HTTP push, bundled YAML profiles
  `jvm`, `tomcat`, `wildfly`, `payara`, `jetty`, `liberty`; own rules in YAML.
- **Server adapters** with one normalized view of datasources, thread pools, deployments and
  sessions: WildFly / JBoss EAP, Apache Tomcat (also Apache TomEE), Eclipse Jetty 12,
  Open Liberty / WebSphere Liberty, Payara / GlassFish (experimental); SPI for own adapters.
- **Streaming**: attribute subscriptions and JMX notifications over SSE and WebSocket.
- **Jolokia compatibility**: `/jolokia` speaks protocol 7.x behind the same security pipeline.
- **Embedded runtime** for servers without a servlet container: handler for Undertow 2.3 and
  Netty 4.1, standalone jar and Java agent (`rocoto-undertow-<version>-all.jar`,
  `rocoto-netty-<version>-all.jar`); built-in authentication and SSE only.
- **`rocoto-tools`**: command line helper for users, password hashes and API tokens, converting a
  Jolokia policy file and checking a TLS endpoint.
- Compiled, architecture independent downloads (arm64 and amd64) with `SHA256SUMS`.

### Verified on

Tomcat 10.0.27, 10.1.0 - 10.1.60 and 11.0, TomEE 10, WildFly 40, Payara 6 and 7, GlassFish 7 and 8,
Jetty 12.0 and 12.1, Open Liberty and WebSphere Liberty 26.0.0.9, Undertow 2.3.26, Netty 4.1.138;
on linux/arm64, linux/amd64 and macOS (Apple Silicon).

### Known limitations

- GlassFish 7 rejects `:` in URL paths with 503; use the POST endpoints there.
- Payara / GlassFish need AMX and monitoring level `HIGH` for the normalized view.
- The embedded runtime has no WebSocket endpoint and supports `auth.mode: builtin` only.
- The artifacts are not on Maven Central; build locally with `mvn install` to use the adapter SPI
  or the embedded handlers as a dependency.

[Unreleased]: https://github.com/chardonnay/rocoto/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/chardonnay/rocoto/releases/tag/v0.1.0

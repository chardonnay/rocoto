# Rocoto user guide

Rocoto is a web application (WAR) that exposes the JMX MBeans of a Java application server over
HTTPS: authenticated, authorized by policy, masked and audited. This guide explains how it works,
how to install it and how to operate it.

## Chapters

| #  | Chapter | What it covers |
|----|---------|----------------|
| 01 | [Architecture](01-architecture.md) | Modules, request path, fail-closed start-up, design principles |
| 02 | [Installation](02-installation.md) | Build, requirements, deployment on WildFly, Tomcat and Payara, first user and token |
| 03 | [Authentication](03-authentication.md) | `auth.mode`, HTTP Basic, API tokens, mTLS, JWT, container authentication |
| 04 | [The JMX connection](04-jmx-connection.md) | How Rocoto reaches JMX, server adapters, normalized resources, type fidelity |
| 05 | [Authorization policy](05-authorization-policy.md) | Roles, rules, built-in deny list, masking, audit log, recipes |
| 06 | [Metrics](06-metrics.md) | `/metrics` for Prometheus and OpenMetrics, profiles, custom rules, OTLP push |
| 07 | [Streaming](07-streaming.md) | Server-Sent Events and WebSocket subscriptions, thresholds, notifications |
| 08 | [REST API](08-rest-api.md) | Endpoints of `/api/v1`, request and response formats |
| 09 | [Jolokia compatibility and migration](09-jolokia-compat-and-migration.md) | The `/jolokia` endpoint, differences, converting a Jolokia policy |
| 10 | [WildFly and Elytron](10-wildfly-elytron.md) | Security domains, authentication factories, management RBAC |
| 11 | [Security hardening](11-security-hardening.md) | Checklist for production deployments |
| 12 | [Troubleshooting](12-troubleshooting.md) | Status codes, log messages, common mistakes |
| 13 | [TLS and OCSP](13-tls-and-ocsp.md) | Revocation checking, OCSP stapling, the TLS self probe |

## Reference and examples

- [Configuration reference](../reference/configuration.md) - every key of `rocoto.yaml` and
  `rocoto-users.yaml` with its default. The guide links to it instead of repeating it.
- [Examples](../examples/README.md) - ready-to-use configuration files and client snippets.

## Conventions used in this guide

- Examples use `curl` and assume Rocoto is deployed under the context root `/rocoto` on
  `https://server:8443`. Adjust host and port to your server.
- `$TOKEN` stands for an API token (`rct_<id>_<secret>`) created with `rocoto-tools`.
- ObjectNames inside URL paths must be URL-encoded (`java.lang:type=Memory` becomes
  `java.lang%3Atype%3DMemory`). The RPC-style endpoints (`POST /api/v1/read`, ...) take the
  ObjectName in a JSON body and need no encoding.

## What is tested

- Automated integration tests (`rocoto-it`) deploy the real WAR into an embedded Tomcat; they
  include a run of the unmodified Jolokia Java client against `/jolokia`.
- A CI smoke test (`ci/wildfly/`) deploys the WAR into the official WildFly container image and
  checks adapter detection, Elytron and built-in authentication (`auth.mode: chain`), normalized
  resources, policy, metrics, Jolokia compatibility and SSE.
- The Elytron CLI scripts under [`scripts/wildfly/`](scripts/wildfly/) and the Tomcat snippets
  under [`scripts/tomcat/`](scripts/tomcat/) are recipes that are not executed by the build:
  review and adapt before use. Payara support is experimental and untested.

Start with [01 - Architecture](01-architecture.md).

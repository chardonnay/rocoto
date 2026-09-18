# Rocoto cookbook

One page per scenario. Every page has the same structure: goal, prerequisites, all files, the
commands, the expected output and what to look at next. Where a runnable project exists under
[`examples/`](../../examples/README.md), the files shown on the page are identical to the files
of that project.

Rocoto is not on Maven Central. Build it first in the repository root:

```sh
mvn install -DskipTests      # rocoto-war/target/rocoto.war, rocoto-tools/target/rocoto-tools.jar
```

## Getting started per server

| Page | Scenario |
|------|----------|
| [tomcat-builtin-auth](tomcat-builtin-auth.md) | Tomcat, Rocoto's own users and API tokens |
| [tomcat-realm](tomcat-realm.md) | Tomcat, users and roles from `tomcat-users.xml` (`auth.mode: container` / `chain`) |
| [wildfly-builtin-auth](wildfly-builtin-auth.md) | WildFly / JBoss EAP, Rocoto's own users and API tokens |
| [wildfly-basic-elytron](wildfly-basic-elytron.md) | WildFly, Elytron security domain with BASIC |
| [wildfly-mtls-ocsp](wildfly-mtls-ocsp.md) | WildFly, client certificates with OCSP revocation checking and stapling |
| [wildfly-oidc-keycloak](wildfly-oidc-keycloak.md) | WildFly, JWT bearer tokens issued by Keycloak |
| [payara-quickstart](payara-quickstart.md) | Payara (experimental) |
| [kubernetes-deployment](kubernetes-deployment.md) | ConfigMap, Secrets, TLS and a ServiceMonitor |

## Authorization

| Page | Scenario |
|------|----------|
| [policy-recipes](policy-recipes.md) | Allow and deny rules for common situations, masking, token scopes |
| [expose-your-own-mxbean](expose-your-own-mxbean.md) | An application MXBean end to end: read, write, exec, masking, metrics, streaming |

## Metrics

| Page | Scenario |
|------|----------|
| [prometheus-grafana](prometheus-grafana.md) | Scrape `/rocoto/metrics` with a token, provisioned Grafana dashboard |
| [otlp-collector](otlp-collector.md) | Push metrics to an OpenTelemetry Collector |
| [custom-metrics-profile](custom-metrics-profile.md) | Write your own mapping rules |

## Clients

| Page | Scenario |
|------|----------|
| [rest-clients](rest-clients.md) | `curl`, Java, Python, Node.js |
| [streaming-clients](streaming-clients.md) | Server-Sent Events and WebSocket |
| [jolokia-migration](jolokia-migration.md) | Existing Jolokia clients against `/rocoto/jolokia`, converting `jolokia-access.xml` |

## Extending

| Page | Scenario |
|------|----------|
| [custom-server-adapter](custom-server-adapter.md) | Support another application server through the `ServerAdapter` SPI |

## Conventions

- Demo credentials are public: password `demo-password-123`, token
  `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY`. Never reuse them.
- `auth.allowInsecureTransport: true` appears only in local Docker Compose demos and is always
  marked "LOCAL DEMO ONLY - never use in production". Without it Rocoto answers credentials sent
  over plain HTTP with `403 Credentials are only accepted over TLS`.
- Pages state what was actually executed. The Docker Compose, WildFly CLI, Keycloak and Kubernetes
  parts were written without Docker or a cluster at hand; the Rocoto requests and responses were
  captured from `rocoto.war` running in an embedded Tomcat 10.1.
- Reference: [configuration](../reference/configuration.md). Background: [user guide](../guide/README.md).

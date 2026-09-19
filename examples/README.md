# Rocoto examples

Runnable projects. Every directory has its own README with prerequisites, commands and expected
output. The matching step-by-step pages live in the [cookbook](../docs/examples/README.md).

Rocoto is not on Maven Central. Before anything else run this in the repository root:

```sh
mvn install -DskipTests      # builds rocoto-war/target/rocoto.war and rocoto-tools/target/rocoto-tools.jar
```

| Example | What it shows |
|---------|---------------|
| [demo-app](demo-app/README.md) | A tiny Jakarta EE 10 WAR that registers its own MXBean `com.example.demo:type=Orders`; used by most other examples |
| [tomcat-builtin](tomcat-builtin/README.md) | Tomcat 10.1 + Rocoto with built-in users and API tokens, policy rules, audit file, custom metric rules (Docker Compose) |
| [wildfly-elytron](wildfly-elytron/README.md) | WildFly with an Elytron filesystem realm, BASIC authentication and `auth.mode: chain` (Docker Compose + CLI script) |
| [wildfly-oidc-keycloak](wildfly-oidc-keycloak/README.md) | Keycloak issues JWTs, Rocoto validates them with `auth.builtin.jwt` (Docker Compose) |
| [prometheus-grafana](prometheus-grafana/README.md) | Prometheus scraping `/rocoto/metrics` with a token file, Grafana with a provisioned dashboard (Docker Compose) |
| [mtls-ocsp](mtls-ocsp/README.md) | Client certificates with OCSP revocation checking (hard-fail) and OCSP stapling over real TLS (Docker Compose + `gen-certs.sh`) |
| [clients](clients/README.md) | REST, bulk, exec, SSE and WebSocket clients in Java, Python and Node.js |
| [custom-adapter](custom-adapter/README.md) | A `ServerAdapter` SPI implementation for a fictional server, added to `rocoto.war` as a jar |
| [embedded](embedded/README.md) | Rocoto mounted into an Undertow (core) and a Netty application - servers without a servlet container |
| [kubernetes](kubernetes/README.md) | Deployment, ConfigMap, Secrets, Service and a Prometheus Operator ServiceMonitor |

## Conventions

- The Docker Compose examples have a `Makefile` with `up`, `down` and `test`. `make test` runs the
  documented `curl` commands and fails on unexpected status codes.
- **Demo credentials are public.** All demo users share the password `demo-password-123`; the demo
  API token is `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY`. Every file that
  contains or enables them is marked "DEMO ONLY".
- Files that set `auth.allowInsecureTransport: true` carry the comment
  "LOCAL DEMO ONLY - never use in production". Rocoto rejects credentials over plain HTTP unless
  this flag is set; [mtls-ocsp](mtls-ocsp/README.md) and [kubernetes](kubernetes/README.md) show
  the proper way with TLS.
- The examples are standalone projects. They are not modules of the Rocoto build.

## What was verified

Everything below was executed (Docker 29, September 2026):

- Compiled with `mvn -q package` (JDK 21, `release` 17): `demo-app`, `clients/java`, `custom-adapter`.
- `make up && make test` of every Docker Compose example: `tomcat-builtin` (Tomcat 10.1.60),
  `wildfly-elytron` (image build with `configure-rocoto.cli`, WildFly 40.0.1.Final),
  `wildfly-oidc-keycloak` (Keycloak 26.0 realm import, password grant, JWT validation),
  `prometheus-grafana` (target `up`, provisioned dashboard, every dashboard query returns data)
  and `mtls-ocsp` (`alpine/openssl` responder, revocation, stapling).
- `kubernetes`: the image build and all manifests in a single-node k3s (v1.34) cluster - rollout,
  HTTPS probes, the README checks through `kubectl port-forward`, audit lines in the pod log; the
  `ServiceMonitor` was accepted by the Prometheus Operator CRD (v0.76). No Prometheus Operator
  scraped it.
- The three clients and the custom adapter ran against `rocoto.war` + `demo-app.war` in an
  embedded Tomcat 10.1.


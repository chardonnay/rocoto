# WildFly with Elytron (BASIC) and `auth.mode: chain`

WildFly authenticates people against an Elytron filesystem realm; Rocoto takes the principal and
the roles `rocoto-reader` / `rocoto-operator` / `rocoto-admin` from the container. Because the mode
is `chain`, Rocoto's own API tokens keep working next to it - handy for Prometheus.

> **LOCAL DEMO ONLY - never use in production.** Plain HTTP with
> `auth.allowInsecureTransport: true`, public passwords and a public token.

| File | Purpose |
|------|---------|
| `configure-rocoto.cli` | filesystem realm + identities, security domain, `http-authentication-factory` (BASIC), `application-security-domain`, datasource and Undertow statistics, deployment overlay for `jboss-web.xml` |
| `jboss-web.xml` | overlay content: maps the deployment to the application security domain `rocoto` |
| `Dockerfile` | runs the CLI script offline on top of `quay.io/wildfly/wildfly:latest-jdk17` |
| `docker-compose.yaml` | mounts `rocoto.war`, `demo-app.war`, `rocoto.yaml`, `rocoto-users.yaml` |
| `conf/rocoto.yaml` | `auth.mode: chain`, policy, audit file, metrics |
| `conf/rocoto-users.yaml` | only the `prometheus` token |

## Demo credentials (demo only)

| Principal | Secret | Roles | Authenticated by |
|-----------|--------|-------|------------------|
| `walt` | `demo-password-123` | `rocoto-reader` | Elytron |
| `olga` | `demo-password-123` | `rocoto-operator` | Elytron |
| `wilma` | `demo-password-123` | `rocoto-admin` | Elytron |
| `nora` | `demo-password-123` | none that Rocoto knows | Elytron |
| token `prometheus` | `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY` | `rocoto-reader` | Rocoto |

## Prerequisites

Docker with the compose plugin, `make`, `curl`; both WARs built (`mvn install -DskipTests` in the
repository root - Rocoto is not on Maven Central - and `mvn -q package` in `examples/demo-app`).

## Run

```sh
make up      # builds the image, starts WildFly, waits for /rocoto/api/v1/health/live
make test
make down
```

## What to try

```sh
B=http://localhost:8080/rocoto

curl -s -u wilma:demo-password-123 $B/api/v1/server
# {"rocoto":{"version":"0.1.0-SNAPSHOT","authMode":"chain"},
#  "server":{"adapter":"wildfly","product":"WildFly ...","version":"...","serverState":"running","launchType":"STANDALONE"},
#  "caller":{"name":"wilma","roles":["rocoto-admin"],"authenticatedBy":"container"}}

curl -s -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" $B/api/v1/server
# ... "caller":{"name":"token:prometheus","roles":["rocoto-reader"],"authenticatedBy":"token"}

# Normalized view of the data sources (ExampleDS, statistics enabled by the CLI script)
curl -s -u walt:demo-password-123 $B/api/v1/resources/datasources

# The management model through the JMX facade of WildFly (domain jboss.as)
curl -s -u walt:demo-password-123 -H 'Content-Type: application/json' \
     -d '{"mbean":"jboss.as:subsystem=datasources,data-source=ExampleDS,statistics=pool","attribute":"InUseCount"}' \
     $B/api/v1/read

# nora is a valid Elytron user without a Rocoto role
curl -s -u nora:demo-password-123 "$B/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/Verbose"
# {"status":403,"error":"Access denied by policy"}
curl -s -u nora:demo-password-123 $B/api/v1/mbeans
# {"pattern":"*:*","mbeans":[]}

curl -s -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" $B/metrics | grep '^wildfly_' | head
```

## Status of this example

Docker was not available when this example was written: **neither the image build nor the CLI
script were executed.** The Rocoto side (chain mode, container roles, 401/403 behavior) is covered
by Rocoto's integration tests against a Tomcat realm; the CLI script follows the WildFly 27+
management model and must be reviewed before use. Things to check first if something fails:

- `deployment-overlay add` in an embedded (offline) server - if your version refuses it, run the
  command against the running server instead (`jboss-cli.sh -c`) and add `--redeploy-affected`.
- The data source `ExampleDS` exists in the default `standalone.xml`; remove that line for other
  configurations.
- Attribute names of `jboss.as` MBeans are the camel-cased management attribute names.

## Notes

- In `chain` mode Basic credentials of a user Rocoto does not know are passed on to the container.
  A user that exists in both stores is authenticated by Rocoto.
- `auth.allowInsecureTransport` is also needed for the Elytron users in this demo: Rocoto inspects
  the `Authorization` header first and refuses credentials over plain HTTP with `403`.
- The roles Rocoto asks the container for are the three standard roles plus every role named in
  `policy.readRoles` and `policy.rules`.
- With `auth.mode: container` Rocoto's own user store is ignored completely; the token above
  would stop working.

## What to look at next

Cookbook: [wildfly-basic-elytron](../../docs/examples/wildfly-basic-elytron.md),
[wildfly-oidc-keycloak](../wildfly-oidc-keycloak/README.md).

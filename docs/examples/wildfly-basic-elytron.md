# WildFly with an Elytron security domain (BASIC)

## Goal

People authenticate against an Elytron security domain (here a filesystem realm; LDAP or a database
realm work the same way) with HTTP Basic. Rocoto runs in `auth.mode: chain`: its own API tokens
are checked first, all other credentials are handed to Elytron. Roles come from the identity
attribute `Roles`.

Runnable project: [`examples/wildfly-elytron`](../../examples/wildfly-elytron/README.md).

## Prerequisites

- Docker with the compose plugin, `make`, `curl`
- `mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central),
  `mvn -q package` in `examples/demo-app`

## Files

The CLI script does the server side in six steps: realm and identities, role decoder and security
domain, `http-authentication-factory` with BASIC, `application-security-domain`, statistics and a
deployment overlay that maps `rocoto.war` to the new domain.

[`examples/wildfly-elytron/configure-rocoto.cli`](../../examples/wildfly-elytron/configure-rocoto.cli)

```text
# =====================================================================================
# Review and adapt before use; written against the WildFly 27+ management model.
#
# DEMO ONLY: the passwords below are public (see README.md). For real users run the
# add-identity / set-password commands interactively or use your LDAP / database realm.
#
# Run offline while building the image:
#   $JBOSS_HOME/bin/jboss-cli.sh --file=configure-rocoto.cli
# =====================================================================================
embed-server --std-out=echo --server-config=standalone.xml

batch

# ---- 1. Elytron filesystem realm with four identities ---------------------------------
/subsystem=elytron/filesystem-realm=rocotoRealm:add(path=rocoto-realm, relative-to=jboss.server.config.dir)

/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity(identity=walt)
/subsystem=elytron/filesystem-realm=rocotoRealm:set-password(identity=walt, clear={password="demo-password-123"})
/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity-attribute(identity=walt, name=Roles, value=["rocoto-reader"])

/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity(identity=olga)
/subsystem=elytron/filesystem-realm=rocotoRealm:set-password(identity=olga, clear={password="demo-password-123"})
/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity-attribute(identity=olga, name=Roles, value=["rocoto-operator"])

/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity(identity=wilma)
/subsystem=elytron/filesystem-realm=rocotoRealm:set-password(identity=wilma, clear={password="demo-password-123"})
/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity-attribute(identity=wilma, name=Roles, value=["rocoto-admin"])

# nora can log in but has no Rocoto role: she sees nothing
/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity(identity=nora)
/subsystem=elytron/filesystem-realm=rocotoRealm:set-password(identity=nora, clear={password="demo-password-123"})
/subsystem=elytron/filesystem-realm=rocotoRealm:add-identity-attribute(identity=nora, name=Roles, value=["some-unrelated-role"])

# ---- 2. Security domain: the identity attribute "Roles" becomes the roles ----------------
/subsystem=elytron/simple-role-decoder=rocoto-roles:add(attribute=Roles)
/subsystem=elytron/security-domain=rocotoDomain:add(default-realm=rocotoRealm, permission-mapper=default-permission-mapper, realms=[{realm=rocotoRealm, role-decoder=rocoto-roles}])

# ---- 3. HTTP authentication factory offering BASIC --------------------------------------
/subsystem=elytron/http-authentication-factory=rocoto-http-auth:add(http-server-mechanism-factory=global, security-domain=rocotoDomain, mechanism-configurations=[{mechanism-name=BASIC, mechanism-realm-configurations=[{realm-name=Rocoto}]}])

# ---- 4. Make the factory available to deployments under the name "rocoto" ---------------
/subsystem=undertow/application-security-domain=rocoto:add(http-authentication-factory=rocoto-http-auth)

# ---- 5. Statistics, otherwise the pool and web metrics stay at zero ----------------------
/subsystem=datasources/data-source=ExampleDS:write-attribute(name=statistics-enabled, value=true)
/subsystem=undertow:write-attribute(name=statistics-enabled, value=true)

run-batch

# ---- 6. rocoto.war ships WEB-INF/jboss-web.xml with <security-domain>other</security-domain>.
#         A deployment overlay points it to the domain created above without touching the WAR.
deployment-overlay add --name=rocoto-security-domain --content=/WEB-INF/jboss-web.xml=/tmp/rocoto/jboss-web.xml --deployments=rocoto.war

stop-embedded-server
```

[`examples/wildfly-elytron/jboss-web.xml`](../../examples/wildfly-elytron/jboss-web.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Replaces WEB-INF/jboss-web.xml of rocoto.war through a deployment overlay (configure-rocoto.cli). -->
<jboss-web xmlns="http://www.jboss.com/xml/ns/javaee">
  <context-root>/rocoto</context-root>
  <security-domain>rocoto</security-domain>
  <!-- Must be kept (the overlay replaces the whole file): otherwise Elytron evaluates Basic
       credentials before Rocoto's filter and rejects Rocoto's built-in users with 401. -->
  <proactive-authentication>false</proactive-authentication>
</jboss-web>
```

[`examples/wildfly-elytron/Dockerfile`](../../examples/wildfly-elytron/Dockerfile)

```dockerfile
# LOCAL DEMO ONLY - never use in production: configure-rocoto.cli creates users with public passwords.
# Pin a release tag (quay.io/wildfly/wildfly:<version>-jdk17) for reproducible builds.
FROM quay.io/wildfly/wildfly:latest-jdk17

COPY --chown=jboss:root configure-rocoto.cli jboss-web.xml /tmp/rocoto/

RUN $JBOSS_HOME/bin/jboss-cli.sh --file=/tmp/rocoto/configure-rocoto.cli \
 && rm -rf $JBOSS_HOME/standalone/configuration/standalone_xml_history/current /tmp/rocoto

# rocoto.war, demo-app.war, rocoto.yaml and rocoto-users.yaml are mounted by docker-compose.yaml.
# To bake them into the image instead:
#   COPY rocoto.war demo-app.war $JBOSS_HOME/standalone/deployments/
#   COPY conf/rocoto.yaml conf/rocoto-users.yaml $JBOSS_HOME/standalone/configuration/
```

[`examples/wildfly-elytron/docker-compose.yaml`](../../examples/wildfly-elytron/docker-compose.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# WildFly is published on plain HTTP and conf/rocoto.yaml sets auth.allowInsecureTransport.
# All passwords and the API token are published in the README.
# =====================================================================================
services:
  wildfly:
    build: .
    ports:
      - "8080:8080"
    volumes:
      # Build both WARs first - see README.md. The deployment scanner picks them up.
      - ../../rocoto-war/target/rocoto.war:/opt/jboss/wildfly/standalone/deployments/rocoto.war:ro
      - ../demo-app/target/demo-app.war:/opt/jboss/wildfly/standalone/deployments/demo-app.war:ro
      # ${jboss.server.config.dir}/rocoto.yaml is one of the locations Rocoto searches.
      - ./conf/rocoto.yaml:/opt/jboss/wildfly/standalone/configuration/rocoto.yaml:ro
      - ./conf/rocoto-users.yaml:/opt/jboss/wildfly/standalone/configuration/rocoto-users.yaml:ro
```

[`examples/wildfly-elytron/conf/rocoto.yaml`](../../examples/wildfly-elytron/conf/rocoto.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# auth.allowInsecureTransport makes Rocoto accept passwords and tokens over plain HTTP.
# In production leave it at its default (false) and use the HTTPS listener of WildFly.
# =====================================================================================
auth:
  # chain = Rocoto's own credentials first (here: the API token for Prometheus), everything
  # Rocoto does not know - e.g. the Basic credentials of "wilma" - is handed to Elytron.
  mode: chain
  allowInsecureTransport: true      # LOCAL DEMO ONLY - never use in production
  builtin:
    usersFile: rocoto-users.yaml    # contains only the token; people live in the Elytron realm

policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "com.example.demo:type=Orders"
      operations: [resetCounters, drainQueue]
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]
    - roles: [rocoto-admin]
      allow: [write]
      mbean: "com.example.demo:type=Orders"
      attributes: [MaintenanceMode]

audit:
  file: /opt/jboss/wildfly/standalone/log/rocoto-audit.log

health:
  allowAnonymous: true

metrics:
  profiles: [jvm, auto]             # auto = wildfly
  rules:
    - mbean: "com.example.demo:type=Orders"
      attribute: OrdersProcessed
      name: demo_orders_processed_total
      type: counter
      help: "Orders processed by the demo application"
    - mbean: "com.example.demo:type=Orders"
      attribute: QueueDepth
      name: demo_orders_queue_depth
      help: "Orders waiting in the demo application's queue"
```

[`examples/wildfly-elytron/conf/rocoto-users.yaml`](../../examples/wildfly-elytron/conf/rocoto-users.yaml)

```yaml
# DEMO ONLY - the prometheus token is published in the README of this example.
# Rocoto built-in credentials. Managed with rocoto-tools; contains only hashes.
tokens:
  - id: prometheus
    hash: sha256$IFn54hbJrL/j2yObb5xTynOJcp67SMRZ4cNmhUZtt3Y
    roles:
      - rocoto-reader
```

[`examples/wildfly-elytron/test.sh`](../../examples/wildfly-elytron/test.sh)

```sh
#!/usr/bin/env bash
# Runs the requests documented in README.md and fails on the first unexpected status code.
# DEMO credentials only - see README.md.
set -euo pipefail

ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
PASSWORD="demo-password-123"
TOKEN="rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY"
ORDERS="com.example.demo%3Atype%3DOrders"
failures=0

expect() {
  local want="$1" what="$2" got
  shift 2
  got="$(curl -s -o /dev/null -w '%{http_code}' "$@" || true)"
  if [ "$got" = "$want" ]; then
    printf 'ok    %s  %s\n' "$got" "$what"
  else
    printf 'FAIL  %s  %s (expected %s)\n' "$got" "$what" "$want"
    failures=$((failures + 1))
  fi
}

json=(-H 'Content-Type: application/json')

expect 200 "liveness probe is anonymous"                 "$ROCOTO_URL/api/v1/health/live"
expect 401 "anonymous request"                           "$ROCOTO_URL/api/v1/server"
expect 401 "Elytron user, wrong password"                -u "wilma:wrong-password-000" "$ROCOTO_URL/api/v1/server"
expect 200 "Elytron user wilma"                          -u "wilma:$PASSWORD" "$ROCOTO_URL/api/v1/server"
expect 200 "built-in API token still works (chain)"      -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/api/v1/server"
expect 200 "reader: normalized data sources"             -u "walt:$PASSWORD" "$ROCOTO_URL/api/v1/resources/datasources"
expect 403 "reader: exec is denied"                      -u "walt:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"com.example.demo:type=Orders","operation":"resetCounters"}' "$ROCOTO_URL/api/v1/exec"
expect 200 "operator: exec drainQueue(5)"                -u "olga:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' "$ROCOTO_URL/api/v1/exec"
expect 200 "admin: write MaintenanceMode=false"          -u "wilma:$PASSWORD" "${json[@]}" -X PUT \
  -d '{"value":false}' "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/MaintenanceMode"
expect 403 "authenticated user without a Rocoto role"    -u "nora:$PASSWORD" \
  "$ROCOTO_URL/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/Verbose"
expect 200 "token: metrics"                              -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics"

caller="$(curl -s -u "wilma:$PASSWORD" "$ROCOTO_URL/api/v1/server")"
case "$caller" in
  *'"authenticatedBy":"container"'*) echo "ok         wilma was authenticated by the container (Elytron)" ;;
  *) echo "FAIL       unexpected caller: $caller"; failures=$((failures + 1)) ;;
esac
case "$caller" in
  *'"adapter":"wildfly"'*) echo "ok         the WildFly adapter is active" ;;
  *) echo "FAIL       unexpected adapter: $caller"; failures=$((failures + 1)) ;;
esac
metrics="$(curl -s -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics")"
for metric in jvm_memory_heap_used_bytes wildfly_undertow_requests_total wildfly_datasource_connections_active demo_orders_processed_total; do
  if printf '%s\n' "$metrics" | grep -q "^$metric"; then
    echo "ok         metric $metric"
  else
    echo "FAIL       metric $metric missing"; failures=$((failures + 1))
  fi
done

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
```

Demo credentials (demo only): Elytron users `walt` (reader), `olga` (operator), `wilma` (admin)
and `nora` (no Rocoto role), all with `demo-password-123`; Rocoto token
`rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY`.

## Commands

```sh
cd examples/wildfly-elytron
make up       # docker compose up -d --build, then waits for /rocoto/api/v1/health/live
make test
make down
```

On an existing server run the script against the stopped server
(`$JBOSS_HOME/bin/jboss-cli.sh --file=configure-rocoto.cli`, after adjusting the path of
`jboss-web.xml` in the last command), copy the two YAML files to `standalone/configuration/` and
the WAR to `standalone/deployments/`.

## Expected output

```sh
B=http://localhost:8080/rocoto

curl -s -u wilma:demo-password-123 $B/api/v1/server
# {"rocoto":{"version":"0.1.0-SNAPSHOT","authMode":"chain"},
#  "server":{"adapter":"wildfly","product":"WildFly ...",...},
#  "caller":{"name":"wilma","roles":["rocoto-admin"],"authenticatedBy":"container"}}

curl -s -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" $B/api/v1/server
# ... "caller":{"name":"token:prometheus","roles":["rocoto-reader"],"authenticatedBy":"token"}

curl -s -u nora:demo-password-123 $B/api/v1/mbeans
# {"pattern":"*:*","mbeans":[]}
```

**Executed** (Docker 29, September 2026, WildFly 40.0.1.Final): the image build with the CLI script, `make up` and
`make test` of the example. The guide's `setup-basic.cli` and `rocoto-overlay.cli` are executed by
`ci/wildfly/cli-scripts-test.sh`.

## Notes

- `auth.mode: container` instead of `chain` ignores `rocoto-users.yaml` completely.
- When WildFly does not send a challenge for a request without credentials, Rocoto sends the value
  of `auth.container.challenge` (default `Basic realm="Rocoto", charset="UTF-8"`).
- Other mechanisms (`CLIENT_CERT`, `BEARER_TOKEN`) are a matter of the
  `http-authentication-factory`; Rocoto only consumes the resulting principal and roles.
- Rocoto reads the management model through WildFly's JMX facade (`jboss.as:*`), in-process. Write
  and exec on `jboss.as:subsystem=elytron,*` and `jboss.as:core-service=management,*` are on the
  built-in deny list and cannot be allowed by policy.

## What to look at next

- [wildfly-mtls-ocsp](wildfly-mtls-ocsp.md), [wildfly-oidc-keycloak](wildfly-oidc-keycloak.md)
- [tomcat-realm](tomcat-realm.md) - the same idea on Tomcat
- User guide: [WildFly and Elytron](../guide/10-wildfly-elytron.md)

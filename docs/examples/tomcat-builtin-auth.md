# Tomcat with built-in authentication

## Goal

Run Rocoto on Tomcat 10.1 with its own user store: three users with the standard roles, one API
token for Prometheus, policy rules for an application MBean, an audit file and custom metrics.
Tomcat's realm is not involved (`auth.mode: builtin`, the default).

Runnable project: [`examples/tomcat-builtin`](../../examples/tomcat-builtin/README.md).

## Prerequisites

- JDK 17+, Maven, Docker with the compose plugin, `make`, `curl`
- `mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central)
- `mvn -q package` in [`examples/demo-app`](../../examples/demo-app/README.md)

## Files

[`examples/tomcat-builtin/docker-compose.yaml`](../../examples/tomcat-builtin/docker-compose.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# Tomcat is published on plain HTTP and conf/rocoto.yaml sets auth.allowInsecureTransport.
# The credentials in conf/rocoto-users.yaml are published in the README.
# =====================================================================================
services:
  tomcat:
    image: tomcat:10.1-jdk17
    ports:
      - "8080:8080"
    volumes:
      # Build both WARs first - see README.md. A missing source file would be created as a directory.
      - ../../rocoto-war/target/rocoto.war:/usr/local/tomcat/webapps/rocoto.war:ro
      - ../demo-app/target/demo-app.war:/usr/local/tomcat/webapps/demo-app.war:ro
      # ${catalina.base}/conf/rocoto.yaml is one of the locations Rocoto searches.
      - ./conf/rocoto.yaml:/usr/local/tomcat/conf/rocoto.yaml:ro
      - ./conf/rocoto-users.yaml:/usr/local/tomcat/conf/rocoto-users.yaml:ro
```

[`examples/tomcat-builtin/conf/rocoto.yaml`](../../examples/tomcat-builtin/conf/rocoto.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# auth.allowInsecureTransport makes Rocoto accept passwords and tokens over plain HTTP.
# In production leave it at its default (false) and serve Rocoto over HTTPS.
# =====================================================================================
auth:
  mode: builtin
  allowInsecureTransport: true      # LOCAL DEMO ONLY - never use in production
  builtin:
    usersFile: rocoto-users.yaml    # relative to the directory of this file

policy:
  rules:
    # Operators (and admins) may run the two maintenance operations of the demo MBean ...
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "com.example.demo:type=Orders"
      operations: [resetCounters, drainQueue]
    # ... and trigger a garbage collection.
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]
    # Only admins may switch the maintenance mode.
    - roles: [rocoto-admin]
      allow: [write]
      mbean: "com.example.demo:type=Orders"
      attributes: [MaintenanceMode]

audit:
  file: /usr/local/tomcat/logs/rocoto-audit.log

health:
  allowAnonymous: true              # GET /rocoto/api/v1/health/live without credentials

metrics:
  profiles: [jvm, auto]             # auto = the profile of the detected server (tomcat)
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
    - mbean: "com.example.demo:type=Orders"
      attribute: MaintenanceMode
      name: demo_maintenance_mode
      help: "1 while the demo application is in maintenance mode"

stream:
  minIntervalMillis: 500
```

The user store contains only hashes. All three users have the demo password `demo-password-123`;
the token is `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY` (both demo only):

[`examples/tomcat-builtin/conf/rocoto-users.yaml`](../../examples/tomcat-builtin/conf/rocoto-users.yaml)

```yaml
# DEMO ONLY - the password of all three users (demo-password-123) and the prometheus token are
# published in the README of this example. Never deploy this file anywhere else.
# Rocoto built-in credentials. Managed with rocoto-tools; contains only hashes.
tokens:
  - id: prometheus
    hash: sha256$IFn54hbJrL/j2yObb5xTynOJcp67SMRZ4cNmhUZtt3Y
    roles:
      - rocoto-reader
users:
  - name: rita
    password: pbkdf2-sha256$600000$kZDIpomyMrgTrUj8op6mzQ$QtBQ227/KFDMz/TAcA1gt4cmsQsEQGjYoEmDPQFck7M
    roles:
      - rocoto-reader
  - name: otto
    password: pbkdf2-sha256$600000$p7vzqSjj6ZrQTNVbcosBXQ$89Uuw2gz3ak8F1BU22r+GSWK9Ew6YB9JSBiBHJmyDz8
    roles:
      - rocoto-operator
  - name: ada
    password: pbkdf2-sha256$600000$CyJvHrNzgc2bbZ+x8NIfjA$RNaX/KUf16YqnmuV0I0i5BRt/xlNAYE22oXlL/XsXPM
    roles:
      - rocoto-admin
```

It was created with `rocoto-tools`; passwords are never given on the command line:

```sh
java -jar rocoto-tools/target/rocoto-tools.jar user add rita --roles rocoto-reader --file rocoto-users.yaml
# Password: / Repeat password:          (at least 12 characters; --password-stdin for scripts)
java -jar rocoto-tools/target/rocoto-tools.jar token create prometheus --roles rocoto-reader --file rocoto-users.yaml
# Token prometheus written to rocoto-users.yaml
# The token is shown only once - store it now:
#
#   rct_prometheus_...
```

[`examples/tomcat-builtin/Makefile`](../../examples/tomcat-builtin/Makefile)

```makefile
ROCOTO_WAR := ../../rocoto-war/target/rocoto.war
DEMO_WAR   := ../demo-app/target/demo-app.war
ROCOTO_URL ?= http://localhost:8080/rocoto

.PHONY: up down test logs wait check-artifacts

check-artifacts:
	@test -f $(ROCOTO_WAR) || { echo "missing $(ROCOTO_WAR): run 'mvn install -DskipTests' in the repository root first"; exit 1; }
	@test -f $(DEMO_WAR) || { echo "missing $(DEMO_WAR): run 'mvn -q package' in examples/demo-app first"; exit 1; }

up: check-artifacts
	docker compose up -d
	@$(MAKE) --no-print-directory wait

wait:
	@echo "waiting for $(ROCOTO_URL) ..."
	@for i in $$(seq 1 60); do \
	  code=$$(curl -s -o /dev/null -w '%{http_code}' $(ROCOTO_URL)/api/v1/health/live || true); \
	  if [ "$$code" = "200" ]; then echo "Rocoto is up"; exit 0; fi; sleep 2; \
	done; echo "Rocoto did not start - see 'make logs'"; exit 1

test:
	ROCOTO_URL=$(ROCOTO_URL) ./test.sh

logs:
	docker compose logs --tail=100 tomcat

down:
	docker compose down -v
```

[`examples/tomcat-builtin/test.sh`](../../examples/tomcat-builtin/test.sh)

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

# expect <status> <description> <curl arguments...>
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

expect 200 "liveness probe is anonymous (health.allowAnonymous)" "$ROCOTO_URL/api/v1/health/live"
expect 401 "everything else needs credentials"                   "$ROCOTO_URL/api/v1/server"
expect 401 "wrong password"                                      -u "rita:wrong-password-000" "$ROCOTO_URL/api/v1/server"
expect 200 "reader: server info"                                 -u "rita:$PASSWORD" "$ROCOTO_URL/api/v1/server"
expect 200 "reader: read all attributes of the demo MBean"       -u "rita:$PASSWORD" "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes"
expect 200 "reader: normalized thread pools"                     -u "rita:$PASSWORD" "$ROCOTO_URL/api/v1/resources/threadpools"
expect 404 "reader: unknown attribute"                           -u "rita:$PASSWORD" "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/Nope"
expect 403 "reader: exec is denied"                              -u "rita:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"com.example.demo:type=Orders","operation":"resetCounters"}' "$ROCOTO_URL/api/v1/exec"
expect 200 "operator: exec drainQueue(5)"                        -u "otto:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' "$ROCOTO_URL/api/v1/exec"
expect 403 "operator: write MaintenanceMode is denied"           -u "otto:$PASSWORD" "${json[@]}" -X PUT \
  -d '{"value":true}' "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/MaintenanceMode"
expect 200 "admin: write MaintenanceMode=true"                   -u "ada:$PASSWORD" "${json[@]}" -X PUT \
  -d '{"value":true}' "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/MaintenanceMode"
expect 200 "admin: write MaintenanceMode=false"                  -u "ada:$PASSWORD" "${json[@]}" -X PUT \
  -d '{"value":false}' "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/MaintenanceMode"
expect 403 "admin: no allow rule for Threading.resetPeakThreadCount" -u "ada:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"java.lang:type=Threading","operation":"resetPeakThreadCount"}' "$ROCOTO_URL/api/v1/exec"
expect 415 "POST without application/json"                       -u "ada:$PASSWORD" \
  -d 'mbean=java.lang:type=Memory&operation=gc' "$ROCOTO_URL/api/v1/exec"
expect 200 "token: metrics"                                      -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics"
expect 200 "Jolokia protocol: version"                           -u "rita:$PASSWORD" "$ROCOTO_URL/jolokia/version"

# Content checks
body="$(curl -s -u "rita:$PASSWORD" "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/DbPassword")"
case "$body" in
  *'"value":"********"'*) echo "ok         DbPassword is masked" ;;
  *) echo "FAIL       DbPassword is not masked: $body"; failures=$((failures + 1)) ;;
esac
metrics="$(curl -s -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics")"
for metric in jvm_memory_heap_used_bytes tomcat_threads_busy demo_orders_processed_total demo_orders_queue_depth; do
  if grep -q "^$metric" <<< "$metrics"; then   # no pipe: with pipefail an early grep exit would fail the test
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

## Commands

```sh
cd examples/tomcat-builtin
make up
make test
```

Without Docker: copy `rocoto.war` and `demo-app.war` to `$CATALINA_BASE/webapps/`, both YAML files
to `$CATALINA_BASE/conf/`, change `audit.file` to a path that exists, and - instead of
`allowInsecureTransport` - configure an HTTPS connector
(see [`examples/mtls-ocsp/conf/server.xml`](../../examples/mtls-ocsp/conf/server.xml)).

## Expected output

`make test`:

```text
ok    200  liveness probe is anonymous (health.allowAnonymous)
ok    401  everything else needs credentials
ok    401  wrong password
ok    200  reader: server info
ok    200  reader: read all attributes of the demo MBean
ok    200  reader: normalized thread pools
ok    404  reader: unknown attribute
ok    403  reader: exec is denied
ok    200  operator: exec drainQueue(5)
ok    403  operator: write MaintenanceMode is denied
ok    200  admin: write MaintenanceMode=true
ok    200  admin: write MaintenanceMode=false
ok    403  admin: no allow rule for Threading.resetPeakThreadCount
ok    415  POST without application/json
ok    200  token: metrics
ok    200  Jolokia protocol: version
ok         DbPassword is masked
ok         metric jvm_memory_heap_used_bytes
ok         metric tomcat_threads_busy
ok         metric demo_orders_processed_total
ok         metric demo_orders_queue_depth
all checks passed
```

Single requests:

```sh
B=http://localhost:8080/rocoto

curl -i $B/api/v1/server
# HTTP/1.1 401
# WWW-Authenticate: Basic realm="Rocoto", charset="UTF-8"
# WWW-Authenticate: Bearer realm="Rocoto"
# {"status":401,"error":"Authentication required"}

curl -s -u rita:demo-password-123 $B/api/v1/server
# {"rocoto":{"version":"0.1.1","authMode":"builtin"},
#  "server":{"adapter":"tomcat","product":"Apache Tomcat","version":"Apache Tomcat/10.1.x","jmxDomain":"Catalina"},
#  "caller":{"name":"rita","roles":["rocoto-reader"],"authenticatedBy":"basic"}}

curl -s -u rita:demo-password-123 $B/api/v1/health
# {"status":"WARN","version":"0.1.1","adapter":"tomcat","visibleMBeans":77,
#  "activeSubscriptions":0,"warnings":["auth.allowInsecureTransport is enabled"]}
```

The `WARN` is intended: Rocoto reports the demo-only transport setting. After five wrong passwords for one user within five minutes (`auth.builtin.lockout`) further
attempts are answered with `429` and a `Retry-After` header.

This output was produced with the same WARs and configuration in an embedded Tomcat 10.1.34
(there `jmxDomain` is `Tomcat`). The Docker Compose project passes `make test` with `tomcat:10.1-jdk17` (Tomcat 10.1.60).

## What to look at next

- [policy-recipes](policy-recipes.md) - more rules
- [expose-your-own-mxbean](expose-your-own-mxbean.md) - the demo MBean in detail
- [prometheus-grafana](prometheus-grafana.md) - scrape the metrics
- [tomcat-realm](tomcat-realm.md) - use Tomcat's users instead

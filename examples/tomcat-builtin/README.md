# Tomcat with Rocoto's built-in authentication

Tomcat 10.1 running `rocoto.war` and the [demo application](../demo-app/README.md). Users and an
API token come from `conf/rocoto-users.yaml`; Tomcat's own realm is not involved
(`auth.mode: builtin`).

> **LOCAL DEMO ONLY - never use in production.** The container speaks plain HTTP, so
> `conf/rocoto.yaml` sets `auth.allowInsecureTransport: true`. The credentials below are public.

| File | Purpose |
|------|---------|
| `docker-compose.yaml` | `tomcat:10.1-jdk17` with both WARs and the configuration mounted |
| `conf/rocoto.yaml` | built-in auth, policy rules for the demo MBean, audit file, metric rules |
| `conf/rocoto-users.yaml` | pre-generated demo users and token (hashes only) |
| `test.sh` | runs the requests below and checks the status codes |

## Demo credentials (demo only)

| Principal | Secret | Role |
|-----------|--------|------|
| `rita` | `demo-password-123` | `rocoto-reader` |
| `otto` | `demo-password-123` | `rocoto-operator` |
| `ada` | `demo-password-123` | `rocoto-admin` |
| token `prometheus` | `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY` | `rocoto-reader` |

They were created with:

```sh
printf 'demo-password-123\n' | java -jar rocoto-tools/target/rocoto-tools.jar \
    user add ada --roles rocoto-admin --file examples/tomcat-builtin/conf/rocoto-users.yaml --password-stdin
java -jar rocoto-tools/target/rocoto-tools.jar \
    token create prometheus --roles rocoto-reader --file examples/tomcat-builtin/conf/rocoto-users.yaml
```

## Prerequisites

- Docker with the compose plugin, `make`, `curl`
- JDK 17+ and Maven to build the artifacts. Rocoto is not on Maven Central: run
  `mvn install -DskipTests` in the repository root first, then `mvn -q package` in
  `examples/demo-app`.

## Run

```sh
make up      # starts Tomcat and waits for /rocoto/api/v1/health/live
make test    # runs test.sh
make down
```

## What to try

```sh
B=http://localhost:8080/rocoto

# Nothing is reachable anonymously (except the liveness probe enabled in rocoto.yaml)
curl -i $B/api/v1/server
# HTTP/1.1 401
# WWW-Authenticate: Basic realm="Rocoto", charset="UTF-8"
# WWW-Authenticate: Bearer realm="Rocoto"

curl -s -u rita:demo-password-123 $B/api/v1/server
# {"rocoto":{"version":"0.1.0","authMode":"builtin"},
#  "server":{"adapter":"tomcat","product":"Apache Tomcat","version":"Apache Tomcat/10.1.x","jmxDomain":"Catalina"},
#  "caller":{"name":"rita","roles":["rocoto-reader"],"authenticatedBy":"basic"}}

# All attributes of the demo MBean - DbPassword is masked on the server
curl -s -u rita:demo-password-123 "$B/api/v1/mbeans/com.example.demo%3Atype%3DOrders/attributes"
# {"mbean":"com.example.demo:type=Orders","values":{"OrdersProcessed":17,"QueueDepth":4,
#  "LastOrder":{"amountCents":14075,"customer":"customer-27","id":"order-17","processedAt":"..."},
#  "DbPassword":"********","MaintenanceMode":false}}

# Readers must not execute operations
curl -s -u rita:demo-password-123 -H 'Content-Type: application/json' \
     -d '{"mbean":"com.example.demo:type=Orders","operation":"resetCounters"}' $B/api/v1/exec
# {"status":403,"error":"Access denied by policy"}

# Operators may - the policy names the two operations explicitly
curl -s -u otto:demo-password-123 -H 'Content-Type: application/json' \
     -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' $B/api/v1/exec
# {"mbean":"com.example.demo:type=Orders","operation":"drainQueue","value":4}

# Only admins may write MaintenanceMode (otto gets 403)
curl -s -u ada:demo-password-123 -X PUT -H 'Content-Type: application/json' -d '{"value":true}' \
     "$B/api/v1/mbeans/com.example.demo%3Atype%3DOrders/attributes/MaintenanceMode"
# {"mbean":"com.example.demo:type=Orders","attribute":"MaintenanceMode","previousValue":false}

# Metrics with the API token
curl -s -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" $B/metrics | grep demo_
# demo_orders_processed_total 22
# demo_orders_queue_depth 0
# demo_maintenance_mode 0

# Normalized view of Tomcat's connector thread pools
curl -s -u rita:demo-password-123 $B/api/v1/resources/threadpools

# The audit trail (JSON lines)
docker compose exec tomcat tail -n 3 /usr/local/tomcat/logs/rocoto-audit.log
# {"ts":"...","user":"otto","auth":"basic","remote":"...","channel":"api","action":"WRITE",
#  "mbean":"com.example.demo:type=Orders","member":"MaintenanceMode","outcome":"DENIED",
#  "reason":"write/exec requires an explicit allow rule","args":true}
```

The response bodies above were captured from the same WARs and configuration running in an
embedded Tomcat 10.1 (there the JMX domain is `Tomcat` instead of `Catalina`); the Docker
Compose setup itself was written without a Docker daemon at hand.

## What to look at next

- [prometheus-grafana](../prometheus-grafana/README.md) adds Prometheus and Grafana to this setup.
- [clients](../clients/README.md) talk to this server from Java, Python and Node.js.
- Cookbook: [docs/examples/tomcat-builtin-auth.md](../../docs/examples/tomcat-builtin-auth.md),
  [docs/examples/policy-recipes.md](../../docs/examples/policy-recipes.md).

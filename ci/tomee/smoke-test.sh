#!/usr/bin/env bash
# Deploys the freshly built rocoto.war on a real Apache TomEE 10 (Jakarta EE 10) and checks the
# configuration in ${catalina.base}/conf, built-in authentication, container authentication with
# tomcat-users.xml (auth.mode chain), the tomcat adapter including TomEE's container managed data
# sources (openejb.management:*), metrics, Jolokia compatibility, SSE and WebSocket.
#
# CI ONLY: TomEE's default server.xml has no HTTPS connector, therefore the test sets
# auth.allowInsecureTransport. Never do that in production.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TOMEE_IMAGE="${TOMEE_IMAGE:-tomee:10.1-jre17-plume}"
NAME="rocoto-tomee-smoke"
PORT="${PORT:-18087}"
BASE="http://localhost:$PORT/rocoto"
PASSWORD='Builtin-Secret-12345'
REALM_PASSWORD='Container-Secret-1!'
WORK="$(mktemp -d)"
chmod 755 "$WORK"
FAILED=0

cleanup() {
  if [ "$FAILED" = "1" ]; then
    echo "---- server log ----"
    docker logs "$NAME" 2>&1 | grep -E "SEVERE|WARNING|Exception|Caused by|rocoto" | grep -v "io.rocoto.audit" | tail -n 40 || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

check() { if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@" || true; }

cat > "$WORK/rocoto.yaml" <<YAML
# CI ONLY - never use allowInsecureTransport in production.
auth:
  mode: chain
  allowInsecureTransport: true
  builtin:
    usersFile: rocoto-users.yaml
policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]
YAML
printf '%s\n' "$PASSWORD" | java -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" \
  user add rita --roles rocoto-reader --file "$WORK/rocoto-users.yaml" --password-stdin >/dev/null
TOKEN="$(java -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" token create ci --roles rocoto-reader \
  --file "$WORK/rocoto-users.yaml" | grep -o 'rct_ci_[A-Za-z0-9_-]*' | head -1)"
cat > "$WORK/tomcat-users.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<tomcat-users xmlns="http://tomcat.apache.org/xml">
  <role rolename="rocoto-operator"/>
  <user username="carl" password="$REALM_PASSWORD" roles="rocoto-operator"/>
</tomcat-users>
XML
# A container managed data source; TomEE ships HSQLDB.
cat > "$WORK/tomee.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<tomee>
  <Resource id="demoDS" type="DataSource">
    JdbcDriver = org.hsqldb.jdbcDriver
    JdbcUrl = jdbc:hsqldb:mem:demo
    UserName = sa
    MaxActive = 12
  </Resource>
</tomee>
XML
chmod 644 "$WORK"/*

docker run -d --name "$NAME" -p "$PORT:8080" \
  -v "$ROOT/rocoto-war/target/rocoto.war:/usr/local/tomee/webapps/rocoto.war:ro" \
  -v "$WORK/rocoto.yaml:/usr/local/tomee/conf/rocoto.yaml:ro" \
  -v "$WORK/rocoto-users.yaml:/usr/local/tomee/conf/rocoto-users.yaml:ro" \
  -v "$WORK/tomcat-users.xml:/usr/local/tomee/conf/tomcat-users.xml:ro" \
  -v "$WORK/tomee.xml:/usr/local/tomee/conf/tomee.xml:ro" \
  "$TOMEE_IMAGE" >/dev/null

echo "Waiting for TomEE and the Rocoto deployment ($TOMEE_IMAGE) ..."
for _ in $(seq 1 90); do
  [ "$(status "$BASE/api/v1/server")" = "401" ] && break
  sleep 2
done

echo "Authentication"
check "anonymous is rejected" 401 "$(status "$BASE/api/v1/server")"
check "wrong password" 401 "$(status -u rita:wrong "$BASE/api/v1/server")"
SERVER="$(curl -s -u "rita:$PASSWORD" "$BASE/api/v1/server")"
check "built-in user (rocoto.yaml found in \${catalina.base}/conf)" basic "$(echo "$SERVER" | jq -r .caller.authenticatedBy)"
check "API token" token "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/server" | jq -r .caller.authenticatedBy)"
CARL="$(curl -s -u "carl:$REALM_PASSWORD" "$BASE/api/v1/server")"
check "tomcat-users.xml user is authenticated by the container" container "$(echo "$CARL" | jq -r .caller.authenticatedBy)"
check "realm role becomes a Rocoto role" rocoto-operator "$(echo "$CARL" | jq -r '.caller.roles[0]')"
check "wrong realm password" 401 "$(status -u carl:wrong "$BASE/api/v1/server")"
GC='{"mbean":"java.lang:type=Memory","operation":"gc"}'
check "container operator may exec gc" 200 "$(status -u "carl:$REALM_PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"
check "reader may not exec" 403 "$(status -u "rita:$PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"

echo "Server adapter"
check "the tomcat adapter is used" tomcat "$(echo "$SERVER" | jq -r .server.adapter)"
check "product is Apache TomEE" "Apache TomEE" "$(echo "$SERVER" | jq -r .server.product)"
res() { curl -s -u "rita:$PASSWORD" "$BASE/api/v1/resources/$1"; }
check "thread pool of the connector" true "$(res threadpools | jq -r '.items[0].maxThreads != null')"
check "deployments contain /rocoto" true "$(res deployments | jq -r '[.items[].contextPath] | index("/rocoto") != null')"
check "sessions of /rocoto" true "$(res sessions | jq -r '[.items[].name] | index("/rocoto") != null')"
check "TomEE data source demoDS (openejb.management)" 12 "$(res datasources | jq -r '.items[] | select(.name == "demoDS") | .maxConnections')"
check "data source password is masked" '********' "$(curl -s -u "rita:$PASSWORD" -H 'Content-Type: application/json' \
  -d '{"mbean":"openejb.management:ObjectType=datasources,DataSource=demoDS","attribute":"Password"}' "$BASE/api/v1/read" | jq -r .value)"
check "ObjectName in the URL path" 200 "$(status -u "rita:$PASSWORD" "$BASE/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage")"

echo "Metrics, Jolokia compatibility, streaming"
METRICS="$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/metrics")"
for metric in jvm_memory_heap_used_bytes tomcat_threads_busy 'tomcat_datasource_connections_max{datasource="demoDS"'; do
  check "metric $metric" true "$(echo "$METRICS" | grep -cF "$metric" >/dev/null && echo true || echo false)"
done
check "Jolokia read" 200 "$(curl -s -u "rita:$PASSWORD" "$BASE/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used" | jq -r .status)"
check "WebSocket handshake" 101 "$(status --http1.1 --max-time 5 -u "rita:$PASSWORD" \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "$BASE/api/v1/ws")"
SSE="$(curl -s -N --max-time 5 -u "rita:$PASSWORD" "$BASE/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=500" || true)"
check "SSE delivers values" true "$(echo "$SSE" | grep -c '^event: value' >/dev/null && echo true || echo false)"

if [ "$FAILED" = "1" ]; then echo "TomEE smoke test FAILED ($TOMEE_IMAGE)"; exit 1; fi
echo "TomEE smoke test passed ($TOMEE_IMAGE)"

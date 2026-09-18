#!/usr/bin/env bash
# Builds a WildFly image with the freshly built rocoto.war and checks Rocoto's behaviour on a
# real server: adapter detection, Elytron (container) and built-in authentication, policy,
# normalized resources, metrics, Jolokia compatibility.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
NAME="rocoto-wildfly-smoke"
BASE="http://localhost:18080/rocoto"
BUILTIN_PASSWORD='Builtin-Secret-12345'
ELYTRON_PASSWORD='Container-Secret-1!'

cleanup() {
  docker logs "$NAME" > "$WORK/server.log" 2>&1 || true
  if [ "${FAILED:-0}" = "1" ]; then tail -n 150 "$WORK/server.log" || true; fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

cp "$ROOT/rocoto-war/target/rocoto.war" "$ROOT/ci/wildfly/Dockerfile" "$ROOT/ci/wildfly/rocoto.yaml" "$WORK/"
printf '%s\n' "$BUILTIN_PASSWORD" | java -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" \
  user add rita --roles rocoto-reader --file "$WORK/rocoto-users.yaml" --password-stdin
TOKEN="$(java -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" token create ci --roles rocoto-reader \
  --file "$WORK/rocoto-users.yaml" | grep -o 'rct_ci_[A-Za-z0-9_-]*' | head -1)"
chmod 644 "$WORK/rocoto-users.yaml"

docker build -q -t rocoto-wildfly-smoke "$WORK" >/dev/null
docker run -d --name "$NAME" -p 18080:8080 rocoto-wildfly-smoke >/dev/null

echo "Waiting for WildFly and the Rocoto deployment ..."
for i in $(seq 1 90); do
  code="$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/server" || true)"
  [ "$code" = "401" ] && break
  sleep 2
done

FAILED=0
check() { # description, expected, actual
  if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

echo "Authentication"
check "anonymous is rejected" 401 "$(status "$BASE/api/v1/server")"
check "wrong password is rejected" 401 "$(status -u rita:wrong "$BASE/api/v1/server")"
SERVER_BUILTIN="$(curl -s -u "rita:$BUILTIN_PASSWORD" "$BASE/api/v1/server")"
check "built-in user" basic "$(echo "$SERVER_BUILTIN" | jq -r .caller.authenticatedBy)"
SERVER_ELYTRON="$(curl -s -u "carl:$ELYTRON_PASSWORD" "$BASE/api/v1/server")"
check "Elytron user is authenticated by the container" container "$(echo "$SERVER_ELYTRON" | jq -r .caller.authenticatedBy)"
check "Elytron group becomes a Rocoto role" rocoto-admin "$(echo "$SERVER_ELYTRON" | jq -r '.caller.roles[0]')"
check "API token" token "$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/server" | jq -r .caller.authenticatedBy)"

echo "Server adapter"
check "WildFly is detected" wildfly "$(echo "$SERVER_BUILTIN" | jq -r .server.adapter)"
check "product version is read from jboss.as:management-root=server" true \
  "$(echo "$SERVER_BUILTIN" | jq -r '.server.version != null')"
check "deployments contain rocoto.war" true \
  "$(curl -s -u "rita:$BUILTIN_PASSWORD" "$BASE/api/v1/resources/deployments" | jq -r '[.items[].name] | index("rocoto.war") != null')"
check "datasources contain ExampleDS" true \
  "$(curl -s -u "rita:$BUILTIN_PASSWORD" "$BASE/api/v1/resources/datasources" | jq -r '[.items[].name] | index("ExampleDS") != null')"
check "sessions resource answers" 200 "$(status -u "rita:$BUILTIN_PASSWORD" "$BASE/api/v1/resources/sessions")"
check "thread pools contain the default io worker" true \
  "$(curl -s -u "rita:$BUILTIN_PASSWORD" "$BASE/api/v1/resources/threadpools" | jq -r '[.items[].name] | index("default") != null')"

echo "Policy"
GC='{"mbean":"java.lang:type=Memory","operation":"gc"}'
check "reader may not exec" 403 "$(status -u "rita:$BUILTIN_PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"
check "Elytron admin may exec gc" 200 "$(status -u "carl:$ELYTRON_PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"
ELYTRON_WRITE='{"mbean":"jboss.as:subsystem=elytron","operation":"anything"}'
check "Elytron subsystem is on the deny list" 403 "$(status -u "carl:$ELYTRON_PASSWORD" -H 'Content-Type: application/json' -d "$ELYTRON_WRITE" "$BASE/api/v1/exec")"
check "form posts are rejected" 415 "$(status -u "carl:$ELYTRON_PASSWORD" -d 'x=y' "$BASE/api/v1/exec")"

echo "Metrics and Jolokia compatibility"
METRICS="$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/metrics")"
check "JVM metrics" true "$(echo "$METRICS" | grep -q '^jvm_memory_heap_used_bytes ' && echo true || echo false)"
check "WildFly metrics" true "$(echo "$METRICS" | grep -q '^wildfly_' && echo true || echo false)"
check "Jolokia read" 200 "$(curl -s -u "rita:$BUILTIN_PASSWORD" "$BASE/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used" | jq -r .status)"
check "Jolokia proxy mode does not exist" 403 "$(curl -s -u "rita:$BUILTIN_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"type":"read","mbean":"java.lang:type=Memory","attribute":"Verbose","target":{"url":"service:jmx:rmi:///jndi/ldap://x/y"}}' "$BASE/jolokia" | jq -r .status)"

echo "Streaming"
SSE="$(curl -s -N --max-time 5 -u "rita:$BUILTIN_PASSWORD" "$BASE/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=500" || true)"
check "SSE delivers values" true "$(echo "$SSE" | grep -q '^event: value' && echo true || echo false)"

if [ "$FAILED" = "1" ]; then echo "WildFly smoke test FAILED"; exit 1; fi
echo "WildFly smoke test passed"

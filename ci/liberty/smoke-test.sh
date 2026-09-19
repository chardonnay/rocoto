#!/usr/bin/env bash
# Deploys the freshly built rocoto.war on a real Open Liberty and checks the configuration in
# ${server.config.dir}, built-in authentication over HTTPS, container authentication with a
# Liberty basicRegistry (auth.mode chain), the liberty adapter, the liberty metrics profile,
# Jolokia compatibility, SSE and WebSocket.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LIBERTY_IMAGE="${LIBERTY_IMAGE:-open-liberty:full-java17-openj9}"
NAME="rocoto-liberty-smoke"
PORT="${PORT:-19443}"
HTTP_PORT="${HTTP_PORT:-19080}"
BASE="https://localhost:$PORT/rocoto"
PASSWORD='Builtin-Secret-12345'
REALM_PASSWORD='Container-Secret-1!'
WORK="$(mktemp -d)"
chmod 755 "$WORK"
FAILED=0

cleanup() {
  if [ "$FAILED" = "1" ]; then
    echo "---- server log ----"
    docker logs "$NAME" 2>&1 | grep -E "ERROR|WARNING|Exception|Caused by|rocoto" | grep -v "io.rocoto.audit" | tail -n 40 || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

check() { if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi; }
status() { curl -sk -o /dev/null -w '%{http_code}' "$@" || true; }

cat > "$WORK/rocoto.yaml" <<YAML
auth:
  mode: chain
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
chmod 644 "$WORK/rocoto-users.yaml"

# monitor-1.0 publishes the WebSphere:* statistics MBeans. Without an <application-bnd>, Liberty
# maps a security role to the registry group of the same name.
cat > "$WORK/server.xml" <<XML
<server description="Rocoto smoke test">
  <featureManager>
    <feature>servlet-6.0</feature>
    <feature>websocket-2.1</feature>
    <feature>appSecurity-5.0</feature>
    <feature>transportSecurity-1.0</feature>
    <feature>monitor-1.0</feature>
  </featureManager>
  <httpEndpoint id="defaultHttpEndpoint" host="*" httpPort="9080" httpsPort="9443"/>
  <keyStore id="defaultKeyStore" password="ci-only-changeit-123"/>
  <basicRegistry id="basic" realm="Rocoto">
    <user name="carl" password="$REALM_PASSWORD"/>
    <group name="rocoto-operator"><member name="carl"/></group>
  </basicRegistry>
  <webApplication location="rocoto.war" contextRoot="/rocoto"/>
</server>
XML

docker run -d --name "$NAME" -p "$PORT:9443" -p "$HTTP_PORT:9080" \
  -v "$WORK/server.xml:/config/server.xml:ro" \
  -v "$WORK/rocoto.yaml:/config/rocoto.yaml:ro" \
  -v "$WORK/rocoto-users.yaml:/config/rocoto-users.yaml:ro" \
  -v "$ROOT/rocoto-war/target/rocoto.war:/config/apps/rocoto.war:ro" \
  "$LIBERTY_IMAGE" >/dev/null

echo "Waiting for Open Liberty and the Rocoto deployment ($LIBERTY_IMAGE) ..."
for _ in $(seq 1 90); do
  [ "$(status "$BASE/api/v1/server")" = "401" ] && break
  sleep 2
done

echo "Authentication"
check "anonymous is rejected" 401 "$(status "$BASE/api/v1/server")"
check "plain HTTP endpoint refuses credentials" 403 "$(status -u "rita:$PASSWORD" "http://localhost:$HTTP_PORT/rocoto/api/v1/server")"
check "wrong password" 401 "$(status -u rita:wrong "$BASE/api/v1/server")"
SERVER="$(curl -sk -u "rita:$PASSWORD" "$BASE/api/v1/server")"
check "built-in user (rocoto.yaml found in \${server.config.dir})" basic "$(echo "$SERVER" | jq -r .caller.authenticatedBy)"
check "API token" token "$(curl -sk -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/server" | jq -r .caller.authenticatedBy)"
CARL="$(curl -sk -u "carl:$REALM_PASSWORD" "$BASE/api/v1/server")"
check "registry user is authenticated by the container" container "$(echo "$CARL" | jq -r .caller.authenticatedBy)"
check "registry group becomes a Rocoto role" rocoto-operator "$(echo "$CARL" | jq -r '.caller.roles[0]')"
check "wrong registry password" 401 "$(status -u carl:wrong "$BASE/api/v1/server")"
GC='{"mbean":"java.lang:type=Memory","operation":"gc"}'
check "container operator may exec gc" 200 "$(status -u "carl:$REALM_PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"
check "reader may not exec" 403 "$(status -u "rita:$PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"

echo "Server adapter"
check "Liberty is detected" liberty "$(echo "$SERVER" | jq -r .server.adapter)"
check "product and version are read from lib/versions" true "$(echo "$SERVER" | jq -r '(.server.product | test("Liberty")) and (.server.version != null)')"
check "monitor feature is seen" true "$(echo "$SERVER" | jq -r .server.monitorFeature)"
res() { curl -sk -u "rita:$PASSWORD" "$BASE/api/v1/resources/$1"; }
check "thread pool Default Executor" true "$(res threadpools | jq -r '[.items[] | select(.name == "Default Executor") | .busyThreads != null] | any')"
check "deployments contain rocoto" true "$(res deployments | jq -r '[.items[] | select(.name == "rocoto") | .enabled] | any')"
check "ObjectName in the URL path" 200 "$(status -u "rita:$PASSWORD" "$BASE/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage")"

echo "Metrics, Jolokia compatibility, streaming"
METRICS="$(curl -sk -H "Authorization: Bearer $TOKEN" "$BASE/metrics")"
for metric in jvm_memory_heap_used_bytes liberty_threads_busy liberty_servlet_requests_total; do
  check "metric $metric" true "$(echo "$METRICS" | grep -q "^$metric" && echo true || echo false)"
done
check "Jolokia read" 200 "$(curl -sk -u "rita:$PASSWORD" "$BASE/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used" | jq -r .status)"
check "WebSocket handshake" 101 "$(status --http1.1 --max-time 5 -u "rita:$PASSWORD" \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "$BASE/api/v1/ws")"
SSE="$(curl -sk -N --max-time 5 -u "rita:$PASSWORD" "$BASE/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=500" || true)"
check "SSE delivers values" true "$(echo "$SSE" | grep -q '^event: value' && echo true || echo false)"

# Liberty registers WebSphere:type=SessionStats lazily, with the first HTTP session of the
# application (here: the WebSocket handshake above).
echo "Sessions"
check "sessions of /rocoto" true "$(res sessions | jq -r '[.items[] | select(.contextPath == "/rocoto") | .activeSessions != null] | any')"
check "metric liberty_sessions_live" true \
  "$(curl -sk -H "Authorization: Bearer $TOKEN" "$BASE/metrics" | grep -q '^liberty_sessions_live' && echo true || echo false)"

if [ "$FAILED" = "1" ]; then echo "Open Liberty smoke test FAILED ($LIBERTY_IMAGE)"; exit 1; fi
echo "Open Liberty smoke test passed ($LIBERTY_IMAGE)"

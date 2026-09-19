#!/usr/bin/env bash
# Deploys the freshly built rocoto.war on a real Eclipse Jetty 12 (EE10) and checks deployment
# (WEB-INF/jetty-ee10-web.xml), the configuration in $JETTY_BASE/etc, built-in authentication over
# HTTPS, container authentication with a Jetty LoginService (auth.mode chain), the jetty adapter,
# the jetty metrics profile, Jolokia compatibility and SSE.
#
#   ci/jetty/smoke-test.sh                 with a LoginService "Rocoto" (chain)
#   LOGIN_SERVICE=none ci/jetty/smoke-test.sh      without one: the fallback of the WAR is used
#   WEBSOCKET=off ci/jetty/smoke-test.sh           without Jetty's WebSocket module: SSE only
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JETTY_IMAGE="${JETTY_IMAGE:-jetty:12.0-jdk17}"
LOGIN_SERVICE="${LOGIN_SERVICE:-hash}"
WEBSOCKET="${WEBSOCKET:-on}"
WS_MODULE=",ee10-websocket-jakarta"
[ "$WEBSOCKET" = "on" ] || WS_MODULE=""
NAME="rocoto-jetty-smoke"
PORT="${PORT:-18445}"
HTTP_PORT="${HTTP_PORT:-18086}"
BASE="https://localhost:$PORT/rocoto"
PASSWORD='Builtin-Secret-12345'
REALM_PASSWORD='Container-Secret-1!'
WORK="$(mktemp -d)"
chmod 755 "$WORK"
FAILED=0

cleanup() {
  if [ "$FAILED" = "1" ]; then
    echo "---- server log ----"
    docker logs "$NAME" 2>&1 | grep -E "WARN|ERROR|Exception|Caused by|rocoto" | grep -v "io.rocoto.audit" | tail -n 40 || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker rmi rocoto-jetty-smoke >/dev/null 2>&1 || true
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
cp "$ROOT/rocoto-war/target/rocoto.war" "$WORK/"

# A Jetty LoginService with the realm name of web.xml ("Rocoto"); user carl, role rocoto-operator.
cat > "$WORK/rocoto-realm.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "https://jetty.org/configure_10_0.dtd">
<Configure id="Server" class="org.eclipse.jetty.server.Server">
  <Call class="org.eclipse.jetty.util.resource.ResourceFactory" name="of">
    <Arg><Ref refid="Server"/></Arg>
    <Call id="rocotoRealmResource" name="newResource">
      <Arg><Property name="jetty.base"/>/etc/rocoto-realm.properties</Arg>
    </Call>
  </Call>
  <Call name="addBean">
    <Arg>
      <New class="org.eclipse.jetty.security.HashLoginService">
        <Set name="name">Rocoto</Set>
        <Set name="config"><Ref refid="rocotoRealmResource"/></Set>
      </New>
    </Arg>
  </Call>
</Configure>
XML
echo "carl: $REALM_PASSWORD,rocoto-operator" > "$WORK/rocoto-realm.properties"

{
  echo "FROM $JETTY_IMAGE"
  echo "RUN java -jar \"\$JETTY_HOME/start.jar\" --add-modules=http,ssl,https,test-keystore,ee10-deploy,ee10-annotations,jmx,statistics$WS_MODULE"
  echo 'COPY --chown=jetty:jetty rocoto.yaml rocoto-users.yaml /var/lib/jetty/etc/'
  echo 'COPY --chown=jetty:jetty rocoto.war /var/lib/jetty/webapps/'
  if [ "$LOGIN_SERVICE" = "hash" ]; then
    echo 'COPY --chown=jetty:jetty rocoto-realm.xml rocoto-realm.properties /var/lib/jetty/etc/'
    echo 'RUN echo "etc/rocoto-realm.xml" >> /var/lib/jetty/start.d/rocoto-realm.ini'
  fi
} > "$WORK/Dockerfile"
docker build -q -t rocoto-jetty-smoke "$WORK" >/dev/null
docker run -d --name "$NAME" -p "$PORT:8443" -p "$HTTP_PORT:8080" rocoto-jetty-smoke >/dev/null

echo "Waiting for Jetty and the Rocoto deployment ($JETTY_IMAGE, LoginService: $LOGIN_SERVICE) ..."
for _ in $(seq 1 60); do
  [ "$(status "$BASE/api/v1/server")" = "401" ] && break
  sleep 2
done

echo "Authentication"
check "anonymous is rejected" 401 "$(status "$BASE/api/v1/server")"
check "plain HTTP listener refuses credentials" 403 "$(status -u "rita:$PASSWORD" "http://localhost:$HTTP_PORT/rocoto/api/v1/server")"
check "wrong password" 401 "$(status -u rita:wrong "$BASE/api/v1/server")"
SERVER="$(curl -sk -u "rita:$PASSWORD" "$BASE/api/v1/server")"
check "built-in user" basic "$(echo "$SERVER" | jq -r .caller.authenticatedBy)"
check "API token" token "$(curl -sk -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/server" | jq -r .caller.authenticatedBy)"
if [ "$LOGIN_SERVICE" = "hash" ]; then
  CARL="$(curl -sk -u "carl:$REALM_PASSWORD" "$BASE/api/v1/server")"
  check "LoginService user is authenticated by the container" container "$(echo "$CARL" | jq -r .caller.authenticatedBy)"
  check "LoginService role becomes a Rocoto role" rocoto-operator "$(echo "$CARL" | jq -r '.caller.roles[0]')"
  check "wrong LoginService password" 401 "$(status -u carl:wrong "$BASE/api/v1/server")"
  GC='{"mbean":"java.lang:type=Memory","operation":"gc"}'
  check "container operator may exec gc" 200 "$(status -u "carl:$REALM_PASSWORD" -H 'Content-Type: application/json' -d "$GC" "$BASE/api/v1/exec")"
else
  check "nobody can log in through the fallback LoginService" 401 "$(status -u "carl:$REALM_PASSWORD" "$BASE/api/v1/server")"
fi
check "reader may not exec" 403 "$(status -u "rita:$PASSWORD" -H 'Content-Type: application/json' \
  -d '{"mbean":"java.lang:type=Memory","operation":"gc"}' "$BASE/api/v1/exec")"

echo "Server adapter"
check "Jetty is detected" jetty "$(echo "$SERVER" | jq -r .server.adapter)"
check "version is read from the server MBean" true "$(echo "$SERVER" | jq -r '.server.version | startswith("12.")')"
res() { curl -sk -u "rita:$PASSWORD" "$BASE/api/v1/resources/$1"; }
check "thread pool" true "$(res threadpools | jq -r '.items[0].maxThreads != null and .items[0].busyThreads != null')"
check "deployments contain /rocoto" true "$(res deployments | jq -r '[.items[].contextPath] | index("/rocoto") != null')"
check "sessions of the rocoto context" true "$(res sessions | jq -r '[.items[] | select(.name == "rocoto") | .activeSessions != null] | any')"
check "ObjectName in the URL path" 200 "$(status -u "rita:$PASSWORD" "$BASE/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage")"

echo "Metrics, Jolokia compatibility, streaming"
METRICS="$(curl -sk -H "Authorization: Bearer $TOKEN" "$BASE/metrics")"
for metric in jvm_memory_heap_used_bytes jetty_threads_busy jetty_threads_max jetty_sessions_active jetty_requests_total; do
  check "metric $metric" true "$(echo "$METRICS" | grep -c "^$metric" >/dev/null && echo true || echo false)"
done
check "Jolokia read" 200 "$(curl -sk -u "rita:$PASSWORD" "$BASE/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used" | jq -r .status)"
SSE="$(curl -sk -N --max-time 5 -u "rita:$PASSWORD" "$BASE/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=500" || true)"
WS_EXPECTED=101
[ "$WEBSOCKET" = "on" ] || WS_EXPECTED=404
check "WebSocket handshake (404 without Jetty's WebSocket module)" "$WS_EXPECTED" "$(status --http1.1 --max-time 5 -u "rita:$PASSWORD" \
  -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' \
  "$BASE/api/v1/ws")"
check "SSE delivers values" true "$(echo "$SSE" | grep -c '^event: value' >/dev/null && echo true || echo false)"

if [ "$FAILED" = "1" ]; then echo "Jetty smoke test FAILED ($JETTY_IMAGE)"; exit 1; fi
echo "Jetty smoke test passed ($JETTY_IMAGE, LoginService: $LOGIN_SERVICE)"

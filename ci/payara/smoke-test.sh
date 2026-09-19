#!/usr/bin/env bash
# Deploys the freshly built rocoto.war on a real Payara Server (6 or 7) or Eclipse GlassFish 7 and
# checks adapter detection,
# built-in and container (file realm) authentication over the HTTPS listener, the AMX based normalized resources and the
# payara metrics profile.
#
#   ci/payara/smoke-test.sh                                         Payara (PAYARA_IMAGE)
#   FLAVOR=glassfish ci/payara/smoke-test.sh                        GlassFish (GLASSFISH_IMAGE)
#
# GlassFish has no set-amx-enabled / set-monitoring-level commands: AMX is started through the
# MBean amx-support:type=boot-amx (here via Rocoto, which needs a policy rule) and the levels are
# set with "asadmin set".
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PAYARA_IMAGE="${PAYARA_IMAGE:-payara/server-full:6.2025.4-jdk17}"
MONITORING_LEVEL="${MONITORING_LEVEL:-HIGH}"
NAME="rocoto-payara-smoke"
PORT="${PORT:-18181}"
HTTP_PORT="${HTTP_PORT:-18082}"
BASE="https://localhost:$PORT/rocoto"
PASSWORD='Builtin-Secret-12345'
REALM_PASSWORD='Container-Secret-1!'
FLAVOR="${FLAVOR:-payara}"
GLASSFISH_IMAGE="${GLASSFISH_IMAGE:-ghcr.io/eclipse-ee4j/glassfish:7.1.0}"
image_env() { docker image inspect "$IMAGE" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n "s/^$1=//p"; }
if [ "$FLAVOR" = "glassfish" ]; then
  IMAGE="$GLASSFISH_IMAGE"
  # The install directory and the admin password file differ between the image generations.
  docker image inspect "$IMAGE" >/dev/null 2>&1 || docker pull -q "$IMAGE" >/dev/null
  GF_HOME="$(image_env PATH_GF_HOME)"
  DOMAIN="$GF_HOME/glassfish/domains/domain1"
  DEPLOY="$DOMAIN/autodeploy"
  # The GlassFish 8 images create the domain on the first start (nothing may be mounted into it)
  # and link autodeploy to /deploy.
  [ -z "$(image_env PATH_GF_DOMAIN)" ] || DEPLOY=/deploy
  CONFIG_MOUNT=/etc/rocoto
  ASADMIN=asadmin
  ADMIN_PASSWORDFILE="$(image_env AS_ADMIN_PASSWORDFILE)"
  POOL=DerbyPool
else
  IMAGE="$PAYARA_IMAGE"
  DOMAIN=/opt/payara/appserver/glassfish/domains/domain1
  DEPLOY=/opt/payara/deployments
  ASADMIN=/opt/payara/appserver/bin/asadmin
  ADMIN_PASSWORDFILE=/opt/payara/passwordFile
  POOL=H2Pool
fi
# Payara: <domain>/config/rocoto.yaml is found without further settings. GlassFish: ROCOTO_CONFIG.
ROCOTO_CONFIG_ENV=""
[ -z "${CONFIG_MOUNT:-}" ] || ROCOTO_CONFIG_ENV="ROCOTO_CONFIG=$CONFIG_MOUNT/rocoto.yaml"
CONFIG_MOUNT="${CONFIG_MOUNT:-$DOMAIN/config}"
WORK="$(mktemp -d)"
chmod 755 "$WORK"
FAILED=0

cleanup() {
  if [ "$FAILED" = "1" ]; then
    echo "---- Rocoto related server log ----"
    docker logs "$NAME" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -A1 -E "rocoto|SEVERE" | tail -n 60 || true
  fi
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

check() { # description, expected, actual
  if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi
}
status() { curl -sk -o /dev/null -w '%{http_code}' "$@" || true; }
asadmin() { docker exec "$NAME" "$ASADMIN" --user admin --passwordfile "$ADMIN_PASSWORDFILE" "$@"; }

cat > "$WORK/rocoto.yaml" <<YAML
auth:
  mode: chain
  builtin:
    usersFile: rocoto-users.yaml
policy:
  rules:
    # GlassFish only: lets an admin start AMX through Rocoto
    - roles: [rocoto-admin]
      allow: [exec]
      mbean: "amx-support:type=boot-amx"
      operations: [bootAMX]
metrics:
  profiles: [jvm, auto]
YAML
printf '%s\n' "$PASSWORD" | java -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" \
  user add ada --roles rocoto-admin --file "$WORK/rocoto-users.yaml" --password-stdin >/dev/null
chmod 644 "$WORK/rocoto-users.yaml"

docker run -d --name "$NAME" -p "$PORT:8181" -p "$HTTP_PORT:8080" \
  -v "$ROOT/rocoto-war/target/rocoto.war:$DEPLOY/rocoto.war:ro" \
  -v "$WORK/rocoto.yaml:$CONFIG_MOUNT/rocoto.yaml:ro" \
  -v "$WORK/rocoto-users.yaml:$CONFIG_MOUNT/rocoto-users.yaml:ro" \
  ${ROCOTO_CONFIG_ENV:+-e "$ROCOTO_CONFIG_ENV"} \
  "$IMAGE" >/dev/null

echo "Waiting for $IMAGE and the Rocoto deployment ..."
if [ "$DEPLOY" = "/deploy" ]; then
  # The GlassFish 8 image starts the domain twice on the first run (create, stop, start for real);
  # only the second start counts.
  for _ in $(seq 1 150); do
    case "$(docker logs "$NAME" 2>&1)" in *'STARTING DOMAIN!'*) break ;; esac
    sleep 2
  done
fi
for _ in $(seq 1 150); do
  [ "$(status "$BASE/api/v1/server")" = "401" ] && break
  sleep 2
done

echo "Without AMX"
check "plain HTTP listener refuses credentials" 403 \
  "$(status -u "ada:$PASSWORD" "http://localhost:$HTTP_PORT/rocoto/api/v1/server")"
SERVER="$(curl -sk -u "ada:$PASSWORD" "$BASE/api/v1/server")"
check "Payara / GlassFish is detected" payara "$(echo "$SERVER" | jq -r .server.adapter)"
check "built-in user" basic "$(echo "$SERVER" | jq -r .caller.authenticatedBy)"
check "amxLoaded is false" false "$(echo "$SERVER" | jq -r .server.amxLoaded)"
check "wrong password" 401 "$(status -u ada:wrong "$BASE/api/v1/server")"
check "JVM MBeans are readable (POST /api/v1/read)" 200 "$(status -u "ada:$PASSWORD" -H 'Content-Type: application/json' \
  -d '{"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage"}' "$BASE/api/v1/read")"
# GlassFish 7 answers 503 for every URL with a ":" in the path, before Rocoto's filter runs
# (IllegalArgumentException: invalid URLPatternSpec in its Jakarta Authorization integration).
# Fixed in GlassFish 8; Payara is not affected.
PATH_STATUS=200
case "$FLAVOR:$(image_env GLASSFISH_VERSION 2>/dev/null || true)" in glassfish:7.*) PATH_STATUS=503 ;; esac
check "ObjectName in the URL path (503 = known GlassFish 7 defect)" "$PATH_STATUS" \
  "$(status -u "ada:$PASSWORD" "$BASE/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage")"
check "resources are empty, not an error" 0 "$(curl -sk -u "ada:$PASSWORD" "$BASE/api/v1/resources/threadpools" | jq -r '.items | length')"

echo "Container authentication (file realm, auth.mode chain)"
docker exec "$NAME" sh -c "cat $ADMIN_PASSWORDFILE > /tmp/passwords.txt; echo 'AS_ADMIN_USERPASSWORD=$REALM_PASSWORD' >> /tmp/passwords.txt"
docker exec "$NAME" "$ASADMIN" --user admin --passwordfile /tmp/passwords.txt \
  create-file-user --groups rocoto-operator carl >/dev/null
CARL="$(curl -sk -u "carl:$REALM_PASSWORD" "$BASE/api/v1/server")"
check "realm user is authenticated by the container" container "$(echo "$CARL" | jq -r .caller.authenticatedBy)"
check "realm group becomes a Rocoto role (glassfish-web.xml)" rocoto-operator "$(echo "$CARL" | jq -r '.caller.roles[0]')"
check "wrong realm password" 401 "$(status -u carl:wrong "$BASE/api/v1/server")"

echo "With AMX and monitoring level $MONITORING_LEVEL"
L="$MONITORING_LEVEL"
if [ "$FLAVOR" = "glassfish" ]; then
  M=configs.config.server-config.monitoring-service.module-monitoring-levels
  asadmin set "$M.jdbc-connection-pool=$L" "$M.thread-pool=$L" "$M.web-container=$L" "$M.http-service=$L" >/dev/null
  check "bootAMX through Rocoto" 200 "$(status -u "ada:$PASSWORD" -H 'Content-Type: application/json' \
    -d '{"mbean":"amx-support:type=boot-amx","operation":"bootAMX"}' "$BASE/api/v1/exec")"
else
  asadmin set-amx-enabled --enabled=true --dynamic=true >/dev/null
  asadmin set-monitoring-level --module=jdbc-connection-pool,thread-pool,web-container,http-service --level="$L,$L,$L,$L" >/dev/null
fi
# Payara: pool statistics appear once the pool is initialized. (GlassFish's DerbyPool has no
# database behind it in the image; its monitoring MBean exists anyway.)
asadmin ping-connection-pool "$POOL" >/dev/null 2>&1 || true
sleep 2
curl -sk -o /dev/null -u "ada:$PASSWORD" "$BASE/api/v1/server"
check "amxLoaded is true" true "$(curl -sk -u "ada:$PASSWORD" "$BASE/api/v1/server" | jq -r .server.amxLoaded)"
res() { curl -sk -u "ada:$PASSWORD" "$BASE/api/v1/resources/$1"; }
check "deployments contain rocoto" true "$(res deployments | jq -r '[.items[].name] | index("rocoto") != null')"
check "thread pool of http-listener-2" true \
  "$(res threadpools | jq -r '[.items[] | select(.name == "network/http-listener-2/thread-pool") | .maxThreads != null] | any')"
check "sessions are named after the application" true "$(res sessions | jq -r '[.items[].name] | index("rocoto/server") != null')"
check "data source $POOL" true "$(res datasources | jq -r --arg p "$POOL" '[.items[] | select(.name | endswith($p)) | .activeConnections != null] | any')"
METRICS="$(curl -sk -u "ada:$PASSWORD" "$BASE/metrics")"
for metric in jvm_memory_heap_used_bytes payara_threads_busy payara_jdbc_connections_used payara_sessions_active \
              payara_requests_total payara_request_errors_total; do
  check "metric $metric" true "$(echo "$METRICS" | grep -q "^$metric" && echo true || echo false)"
done
check "one payara_sessions_active series per application" true \
  "$(echo "$METRICS" | grep -q '^payara_sessions_active{app="rocoto/server"}' && echo true || echo false)"

if [ "$FAILED" = "1" ]; then echo "$FLAVOR smoke test FAILED ($IMAGE)"; exit 1; fi
echo "$FLAVOR smoke test passed ($IMAGE)"

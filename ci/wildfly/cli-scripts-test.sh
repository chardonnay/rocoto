#!/usr/bin/env bash
# Executes the Elytron CLI scripts of docs/guide/scripts/wildfly against a real WildFly and checks
# the result through Rocoto: setup-basic.cli (+ rocoto-overlay.cli), setup-mtls.cli,
# setup-tls-ocsp.cli, setup-oidc.cli (against Keycloak) and setup-rbac-and-statistics.cli, plus the
# WildFly listener commands of docs/examples/wildfly-mtls-ocsp.md (Rocoto's own mTLS authentication).
#
# The scripts are used as they are; only their documented placeholders are replaced (passwords,
# /path/to/jboss-web.xml, the certificate CN, the OIDC provider URL).
#
# Usage: ci/wildfly/cli-scripts-test.sh [basic] [mtls] [builtin-mtls] [stapling] [oidc]     (default: all)
# Needs: docker, openssl, curl, jq, java (rocoto-tools.jar) and a built rocoto.war.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPTS="$ROOT/docs/guide/scripts/wildfly"
WILDFLY_IMAGE="${WILDFLY_IMAGE:-quay.io/wildfly/wildfly:latest-jdk17}"
KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.0}"
NET="rocoto-cli-test"
WF="rocoto-cli-test-wildfly"
HTTP_PORT="${HTTP_PORT:-18081}"
HTTPS_PORT="${HTTPS_PORT:-18443}"
STORE_PASSWORD="demo-changeit-123"
BUILTIN_PASSWORD='Builtin-Secret-12345'
WORK="$(mktemp -d)"
chmod 755 "$WORK"
FAILED=0

[ $# -gt 0 ] || set -- basic mtls builtin-mtls stapling oidc

cleanup() {
  docker rm -f "$WF" rocoto-cli-test-ocsp rocoto-cli-test-keycloak >/dev/null 2>&1 || true
  docker rmi rocoto-cli-test-listener >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

check() { # description, expected, actual
  if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi
}
status() { curl -s -o /dev/null -w '%{http_code}' "$@" || true; }

server_log() {
  echo "---- server log ($1) ----"
  docker logs "$WF" 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E "ERROR|WARN|Caused by|rocoto" | grep -v "io.rocoto.audit" | tail -n 40 || true
}

# start_wildfly <auth.mode> [docker run arguments...]
start_wildfly() {
  local mode="$1"; shift
  docker rm -f "$WF" >/dev/null 2>&1 || true
  cat > "$WORK/rocoto.yaml" <<YAML
# CI ONLY - never use allowInsecureTransport in production.
auth:
  mode: $mode
  allowInsecureTransport: true
YAML
  docker run -d --name "$WF" --network "$NET" -p "$HTTP_PORT:8080" -p "$HTTPS_PORT:8443" "$@" \
    -v "$ROOT/rocoto-war/target/rocoto.war:/opt/jboss/wildfly/standalone/deployments/rocoto.war:ro" \
    -v "$WORK/rocoto.yaml:/opt/jboss/wildfly/standalone/configuration/rocoto.yaml:ro" \
    -v "$WORK/rocoto-users.yaml:/opt/jboss/wildfly/standalone/configuration/rocoto-users.yaml:ro" \
    -v "$WORK:/work:ro" \
    "$WILDFLY_IMAGE" >/dev/null
  wait_for_rocoto
}

wait_for_rocoto() {
  for _ in $(seq 1 90); do
    [ "$(status "http://localhost:$HTTP_PORT/rocoto/api/v1/server")" = "401" ] && return 0
    sleep 2
  done
  echo "Rocoto did not start"; server_log start; exit 1
}

# cli <file below $WORK>: runs a CLI script against the running server, fails on the first error
cli() {
  echo "  running $1"
  if ! docker exec "$WF" /opt/jboss/wildfly/bin/jboss-cli.sh --connect --file="/work/$1" > "$WORK/cli.out" 2>&1; then
    sed 's/^/    /' "$WORK/cli.out"
    echo "  FAIL  $1 did not run cleanly"; FAILED=1
  fi
  sleep 3            # the scripts end with "reload"
  wait_for_rocoto
}

prepare_overlay() {
  cp "$SCRIPTS/jboss-web-rocoto-domain.xml" "$WORK/jboss-web.xml"
  sed 's#/path/to/jboss-web.xml#/work/jboss-web.xml#' "$SCRIPTS/rocoto-overlay.cli" > "$WORK/rocoto-overlay.cli"
}

prepare_pki() {
  [ -d "$WORK/certs" ] && return 0
  echo "Creating the demo PKI and the OCSP responder"
  cp "$ROOT/examples/mtls-ocsp/gen-certs.sh" "$WORK/gen-certs.sh"
  OCSP_URL="http://ocsp:2560" STORE_PASSWORD="$STORE_PASSWORD" "$WORK/gen-certs.sh" > "$WORK/gen-certs.log" 2>&1 \
    || { cat "$WORK/gen-certs.log"; exit 1; }
  chmod 755 "$WORK/certs"
  docker run -d --name rocoto-cli-test-ocsp --network "$NET" --network-alias ocsp \
    -v "$WORK/certs:/certs:ro" -w /certs alpine/openssl:latest \
    ocsp -port 2560 -index index.txt -CA ca.crt -rsigner ocsp.crt -rkey ocsp.key -nmin 5 >/dev/null
}

# The key stores are expected in standalone/configuration (relative-to=jboss.server.config.dir).
copy_stores() {
  docker cp "$WORK/certs/server.p12" "$WF:/opt/jboss/wildfly/standalone/configuration/server.p12"
  docker cp "$WORK/certs/ca.p12" "$WF:/opt/jboss/wildfly/standalone/configuration/client-ca.p12"
}

docker network create "$NET" >/dev/null
printf '%s\n' "$BUILTIN_PASSWORD" | java -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" \
  user add bert --roles rocoto-reader --file "$WORK/rocoto-users.yaml" --password-stdin >/dev/null
chmod 644 "$WORK/rocoto-users.yaml"

for scenario in "$@"; do
  case "$scenario" in
    basic)
      echo "setup-basic.cli + rocoto-overlay.cli + setup-rbac-and-statistics.cli (auth.mode chain)"
      BASE="http://localhost:$HTTP_PORT/rocoto"
      prepare_overlay
      cp "$SCRIPTS/setup-basic.cli" "$SCRIPTS/setup-rbac-and-statistics.cli" "$WORK/"
      start_wildfly chain
      cli setup-basic.cli
      cli rocoto-overlay.cli
      cli setup-rbac-and-statistics.cli
      ADA="$(curl -s -u 'ada:CHANGE-ME-admin-passphrase' "$BASE/api/v1/server")"
      check "ada is authenticated by the new Elytron domain" container "$(echo "$ADA" | jq -r .caller.authenticatedBy)"
      check "identity attribute Roles becomes the Rocoto role" rocoto-admin "$(echo "$ADA" | jq -r '.caller.roles[0]')"
      check "rita is a reader" rocoto-reader \
        "$(curl -s -u 'rita:CHANGE-ME-reader-passphrase' "$BASE/api/v1/server" | jq -r '.caller.roles[0]')"
      check "wrong Elytron password" 401 "$(status -u ada:wrong "$BASE/api/v1/server")"
      check "user of the default ApplicationRealm is unknown to the new domain" 401 "$(status -u 'carl:x' "$BASE/api/v1/server")"
      check "built-in user still works (proactive-authentication=false is kept)" basic \
        "$(curl -s -u "bert:$BUILTIN_PASSWORD" "$BASE/api/v1/server" | jq -r .caller.authenticatedBy)"
      check "statistics: data source pool is visible" true \
        "$(curl -s -u 'rita:CHANGE-ME-reader-passphrase' "$BASE/api/v1/resources/datasources" | jq -r '.items[0].availableConnections != null')"
      [ "$FAILED" = "0" ] || server_log basic
      ;;
    mtls)
      echo "setup-mtls.cli + rocoto-overlay.cli (auth.mode container)"
      BASE="https://localhost:$HTTPS_PORT/rocoto"
      prepare_pki
      prepare_overlay
      # ops-mallory (revoked) gets an identity as well: only the OCSP check may keep her out.
      sed -e "s/CHANGE-ME/$STORE_PASSWORD/g" -e 's/ops-gateway/ops-alice/g' "$SCRIPTS/setup-mtls.cli" | grep -v '^reload' > "$WORK/setup-mtls.cli"
      cat >> "$WORK/setup-mtls.cli" <<'CLI'
/subsystem=elytron/filesystem-realm=rocotoCertRealm:add-identity(identity=ops-mallory)
/subsystem=elytron/filesystem-realm=rocotoCertRealm:add-identity-attribute(identity=ops-mallory, name=Roles, value=[rocoto-operator])
reload
CLI
      start_wildfly container
      copy_stores
      cli setup-mtls.cli
      cli rocoto-overlay.cli
      C="$WORK/certs"
      ALICE="$(curl -s --cacert "$C/ca.crt" --cert "$C/client.crt" --key "$C/client.key" "$BASE/api/v1/server")"
      check "valid client certificate: principal is the CN" ops-alice "$(echo "$ALICE" | jq -r .caller.name)"
      check "authenticated by the container" container "$(echo "$ALICE" | jq -r .caller.authenticatedBy)"
      check "role from the filesystem realm" rocoto-operator "$(echo "$ALICE" | jq -r '.caller.roles[0]')"
      check "no client certificate" 401 "$(status --cacert "$C/ca.crt" "$BASE/api/v1/server")"
      REVOKED="$(status --cacert "$C/ca.crt" --cert "$C/revoked.crt" --key "$C/revoked.key" "$BASE/api/v1/server")"
      check "revoked client certificate is rejected (handshake failure or 401)" true \
        "$([ "$REVOKED" = "000" ] || [ "$REVOKED" = "401" ] && echo true || echo false)"
      docker stop rocoto-cli-test-ocsp >/dev/null
      NO_OCSP="$(status --cacert "$C/ca.crt" --cert "$C/client.crt" --key "$C/client.key" "$BASE/api/v1/server")"
      check "hard-fail: valid certificate is rejected while the responder is down (soft-fail=false)" true \
        "$([ "$NO_OCSP" = "000" ] || [ "$NO_OCSP" = "401" ] && echo true || echo false)"
      docker start rocoto-cli-test-ocsp >/dev/null
      [ "$FAILED" = "0" ] || server_log mtls
      ;;
    builtin-mtls)
      echo "docs/examples/wildfly-mtls-ocsp.md: WildFly listener + Rocoto's own mTLS authentication (OCSP hard-fail)"
      BASE="https://localhost:$HTTPS_PORT/rocoto"
      prepare_pki
      grep -E '^(/subsystem=|reload)' "$ROOT/docs/examples/wildfly-mtls-ocsp.md" > "$WORK/listener.cli"
      start_wildfly chain -e "ROCOTO_TRUSTSTORE_PASSWORD=$STORE_PASSWORD"
      docker cp "$WORK/certs/server.p12" "$WF:/opt/jboss/wildfly/standalone/configuration/server.p12"
      docker cp "$WORK/certs/ca.p12" "$WF:/opt/jboss/wildfly/standalone/configuration/ca.p12"
      cli listener.cli
      # Same Rocoto settings as examples/mtls-ocsp, with the WildFly paths.
      sed -e 's#/usr/local/tomcat/conf/certs/ca.p12#/opt/jboss/wildfly/standalone/configuration/ca.p12#' \
          -e 's#/usr/local/tomcat/logs/#/opt/jboss/wildfly/standalone/log/#' \
          -e '/^tls:/,$d' "$ROOT/examples/mtls-ocsp/conf/rocoto.yaml" > "$WORK/rocoto-mtls.yaml"
      docker cp "$WORK/rocoto-mtls.yaml" "$WF:/tmp/rocoto.yaml"
      docker cp "$ROOT/examples/mtls-ocsp/conf/rocoto-users.yaml" "$WF:/tmp/rocoto-users.yaml"
      docker commit "$WF" rocoto-cli-test-listener >/dev/null
      docker rm -f "$WF" >/dev/null
      docker run -d --name "$WF" --network "$NET" -p "$HTTP_PORT:8080" -p "$HTTPS_PORT:8443" \
        -e "ROCOTO_TRUSTSTORE_PASSWORD=$STORE_PASSWORD" -e ROCOTO_CONFIG=/tmp/rocoto.yaml \
        -v "$ROOT/rocoto-war/target/rocoto.war:/opt/jboss/wildfly/standalone/deployments/rocoto.war:ro" \
        rocoto-cli-test-listener >/dev/null
      for _ in $(seq 1 90); do
        [ "$(status --cacert "$WORK/certs/ca.crt" "$BASE/api/v1/server")" = "401" ] && break
        sleep 2
      done
      C="$WORK/certs"
      ALICE="$(curl -s --cacert "$C/ca.crt" --cert "$C/client.crt" --key "$C/client.key" "$BASE/api/v1/server")"
      check "valid client certificate" ops-alice "$(echo "$ALICE" | jq -r .caller.name)"
      check "authenticated by Rocoto (mtls)" mtls "$(echo "$ALICE" | jq -r .caller.authenticatedBy)"
      check "revoked client certificate" 401 "$(status --cacert "$C/ca.crt" --cert "$C/revoked.crt" --key "$C/revoked.key" "$BASE/api/v1/server")"
      check "no client certificate" 401 "$(status --cacert "$C/ca.crt" "$BASE/api/v1/server")"
      [ "$FAILED" = "0" ] || server_log builtin-mtls
      docker rm -f "$WF" >/dev/null 2>&1 || true
      docker rmi rocoto-cli-test-listener >/dev/null 2>&1 || true
      ;;
    stapling)
      echo "setup-tls-ocsp.cli (OCSP stapling of the server certificate)"
      prepare_pki
      sed -e "s/CHANGE-ME/$STORE_PASSWORD/g" "$SCRIPTS/setup-tls-ocsp.cli" > "$WORK/setup-tls-ocsp.cli"
      # JAVA_OPTS replaces the defaults of bin/standalone.conf, therefore they are repeated here.
      start_wildfly chain -e "JAVA_OPTS=-Xms64m -Xmx512m -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=256m \
-Djava.net.preferIPv4Stack=true -Djboss.modules.system.pkgs=org.jboss.byteman -Djava.awt.headless=true \
-Djdk.tls.server.enableStatusRequestExtension=true"
      copy_stores
      cli setup-tls-ocsp.cli
      HANDSHAKE="$(openssl s_client -connect "localhost:$HTTPS_PORT" -servername localhost -status \
        -CAfile "$WORK/certs/ca.crt" < /dev/null 2>&1 || true)"
      check "the listener presents the new server certificate" true \
        "$(echo "$HANDSHAKE" | grep -c 'CN *= *localhost' >/dev/null && echo true || echo false)"
      check "the server staples an OCSP response" true \
        "$(echo "$HANDSHAKE" | grep -c 'OCSP Response Status: successful' >/dev/null && echo true || echo false)"
      check "tls check of rocoto-tools sees the stapled response" true \
        "$(java -Djavax.net.ssl.trustStore="$WORK/certs/ca.p12" -Djavax.net.ssl.trustStorePassword="$STORE_PASSWORD" \
            -jar "$ROOT/rocoto-tools/target/rocoto-tools.jar" tls check "localhost:$HTTPS_PORT" 2>&1 | grep -c 'OCSP stapling: YES' >/dev/null && echo true || echo false)"
      [ "$FAILED" = "0" ] || server_log stapling
      ;;
    oidc)
      echo "setup-oidc.cli against Keycloak (auth.mode container)"
      BASE="http://localhost:$HTTP_PORT/rocoto"
      docker run -d --name rocoto-cli-test-keycloak --network "$NET" --network-alias keycloak \
        -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e "KC_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 16)" -e KC_HTTP_ENABLED=true \
        -v "$ROOT/examples/wildfly-oidc-keycloak/keycloak/rocoto-demo-realm.json:/opt/keycloak/data/import/rocoto-demo-realm.json:ro" \
        "$KEYCLOAK_IMAGE" start-dev --import-realm >/dev/null
      # Lab without TLS: Keycloak is reached over plain HTTP inside the Docker network, hence
      # ssl-required=EXTERNAL instead of ALL.
      sed -e 's#https://idp.example.com/realms/ops#http://keycloak:8080/realms/rocoto-demo#' \
          -e 's/ssl-required=ALL/ssl-required=EXTERNAL/' "$SCRIPTS/setup-oidc.cli" > "$WORK/setup-oidc.cli"
      start_wildfly container
      echo "  waiting for Keycloak"
      for _ in $(seq 1 90); do
        docker exec "$WF" curl -sf -o /dev/null http://keycloak:8080/realms/rocoto-demo/.well-known/openid-configuration && break
        sleep 2
      done
      cli setup-oidc.cli
      # The token is requested inside the Docker network so that "iss" matches the provider-url.
      # the public demo password of kate and ken, taken from the realm file of the example
      REALM_USER_PASSWORD="$(jq -r '.users[0].credentials[0].value' "$ROOT/examples/wildfly-oidc-keycloak/keycloak/rocoto-demo-realm.json")"
      jwt() {
        docker exec "$WF" curl -s -d grant_type=password -d client_id=rocoto-cli -d "username=$1" \
          --data-urlencode "password=$REALM_USER_PASSWORD" http://keycloak:8080/realms/rocoto-demo/protocol/openid-connect/token | jq -r .access_token
      }
      KATE="$(curl -s -H "Authorization: Bearer $(jwt kate)" "$BASE/api/v1/server")"
      check "kate: principal-attribute=preferred_username" kate "$(echo "$KATE" | jq -r .caller.name)"
      check "authenticated by the container (elytron-oidc-client)" container "$(echo "$KATE" | jq -r .caller.authenticatedBy)"
      check "realm role becomes the Rocoto role" true "$(echo "$KATE" | jq -r '.caller.roles | index("rocoto-admin") != null')"
      check "ken is a reader" true \
        "$(curl -s -H "Authorization: Bearer $(jwt ken)" "$BASE/api/v1/server" | jq -r '.caller.roles | index("rocoto-reader") != null')"
      check "anonymous" 401 "$(status "$BASE/api/v1/server")"
      check "garbage instead of a JWT" 401 "$(status -H 'Authorization: Bearer not.a.jwt' "$BASE/api/v1/server")"
      [ "$FAILED" = "0" ] || server_log oidc
      docker rm -f rocoto-cli-test-keycloak >/dev/null 2>&1 || true
      ;;
    *) echo "unknown scenario: $scenario"; exit 2 ;;
  esac
done

if [ "$FAILED" = "1" ]; then echo "CLI script test FAILED"; exit 1; fi
echo "CLI script test passed"

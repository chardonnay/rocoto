#!/usr/bin/env bash
# Rocoto without a servlet container, on Undertow (core) and on Netty - each in three forms:
#   1. mounted into an application's own server   (examples/embedded: UndertowApp / NettyApp)
#   2. standalone                                 (java -jar rocoto-<server>-<version>-all.jar, TLS)
#   3. as a Java agent inside another JVM         (-javaagent:rocoto-<server>-<version>-all.jar=...)
# Needs: java, keytool, curl, jq and the built modules (mvn install -DskipTests). No Docker.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | head -1)"
PORT="${PORT:-18790}"
PASSWORD="demo-password-123"                     # examples/embedded/conf/rocoto-users.yaml (public demo users)
TOKEN="$(cat "$ROOT/examples/prometheus-grafana/prometheus/rocoto-token")"   # the public demo API token of the examples
WORK="$(mktemp -d)"
FAILED=0
PID=""

stop() { if [ -n "$PID" ]; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; PID=""; fi; }
cleanup() { stop; rm -rf "$WORK"; }
trap cleanup EXIT

check() { if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi; }
status() { curl -sk --max-time 10 -o /dev/null -w '%{http_code}' "$@" || true; }

wait_for() { # base url
  for _ in $(seq 1 60); do
    [ "$(status "$1/api/v1/server")" = "401" ] && return 0
    sleep 1
  done
  echo "  FAIL  Rocoto did not start"; FAILED=1; cat "$WORK/server.log" | tail -n 30; return 0
}

# The checks every form has to pass. $1 = base URL
common_checks() {
  local base="$1"
  check "anonymous is rejected" 401 "$(status "$base/api/v1/server")"
  check "wrong password" 401 "$(status -u rita:wrong "$base/api/v1/server")"
  check "built-in user" basic "$(curl -sk -u "rita:$PASSWORD" "$base/api/v1/server" | jq -r .caller.authenticatedBy)"
  check "API token" token "$(curl -sk -H "Authorization: Bearer $TOKEN" "$base/api/v1/server" | jq -r .caller.authenticatedBy)"
  check "ObjectName in the URL path" 200 "$(status -u "rita:$PASSWORD" "$base/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage")"
  check "reader may not exec" 403 "$(status -u "rita:$PASSWORD" -H 'Content-Type: application/json' \
    -d '{"mbean":"java.lang:type=Memory","operation":"gc"}' "$base/api/v1/exec")"
  check "operator may exec gc" 200 "$(status -u "otto:$PASSWORD" -H 'Content-Type: application/json' \
    -d '{"mbean":"java.lang:type=Memory","operation":"gc"}' "$base/api/v1/exec")"
  check "form posts are rejected" 415 "$(status -u "otto:$PASSWORD" -d 'x=y' "$base/api/v1/exec")"
  check "unknown path" 404 "$(status -u "rita:$PASSWORD" "$base/nope")"
  check "JVM metrics" true "$(curl -sk -H "Authorization: Bearer $TOKEN" "$base/metrics" | grep -c '^jvm_memory_heap_used_bytes ' >/dev/null && echo true || echo false)"
  check "metrics as InfluxDB line protocol" true "$(curl -sk -H "Authorization: Bearer $TOKEN" "$base/metrics?format=influx" | grep -c '^jvm_threads_current value=' >/dev/null && echo true || echo false)"
  check "table output" true "$(curl -sk -u "rita:$PASSWORD" "$base/api/v1/server?format=table" | grep -c '│ name' >/dev/null && echo true || echo false)"
  check "HTML output carries the HTML CSP" true "$(curl -sk -D - -o /dev/null -u "rita:$PASSWORD" "$base/api/v1/server?format=html" | grep -ic "^content-security-policy: default-src 'none'; style-src 'self'" >/dev/null && echo true || echo false)"
  check "stylesheet" 200 "$(status -u "rita:$PASSWORD" "$base/api/v1/style.css")"
  check "unknown format" 406 "$(status -u "rita:$PASSWORD" "$base/api/v1/server?format=pdf")"
  check "Jolokia read" 200 "$(curl -sk -u "rita:$PASSWORD" "$base/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used" | jq -r .status)"
  local sse
  sse="$(curl -sk -N --max-time 4 -u "rita:$PASSWORD" "$base/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=500" || true)"
  check "SSE delivers values" true "$(echo "$sse" | grep -c '^event: value' >/dev/null && echo true || echo false)"
  check "three requests share one connection (keep-alive)" "1 0 0" \
    "$(curl -sk -u "rita:$PASSWORD" -o /dev/null -o /dev/null -o /dev/null -w '%{num_connects} ' "$base/api/v1/server" "$base/api/v1/server" "$base/api/v1/server" | xargs)"
}

EX="$ROOT/examples/embedded"
[ -d "$EX/target/lib" ] || mvn -B -ntp -q -f "$EX/pom.xml" package
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 -validity 2 -dname CN=localhost \
  -ext SAN=dns:localhost,ip:127.0.0.1 -storetype PKCS12 -keystore "$WORK/server.p12" -storepass ci-changeit-123 2>/dev/null
cp "$EX/conf/rocoto-users.yaml" "$WORK/"
sed -e '/allowInsecureTransport/d' "$EX/conf/rocoto.yaml" > "$WORK/rocoto-tls.yaml"
cat >> "$WORK/rocoto-tls.yaml" <<YAML
embedded:
  host: 127.0.0.1
  port: $PORT
  tls:
    keystore: $WORK/server.p12
    keystorePassword: ci-changeit-123
YAML
sed -e '/^  tls:/,$d' "$WORK/rocoto-tls.yaml" > "$WORK/rocoto-plain.yaml"
cat > "$WORK/SleepingApp.java" <<'JAVA'
public class SleepingApp {
    public static void main(String[] args) throws Exception {
        System.out.println("application is running");
        Thread.sleep(600_000);
    }
}
JAVA

for server in undertow netty; do
  ALL="$ROOT/rocoto-$server/target/rocoto-$server-$VERSION-all.jar"
  APP="$([ "$server" = undertow ] && echo UndertowApp || echo NettyApp)"

  echo "$server: mounted into an application (examples/embedded, $APP)"
  java -Drocoto.config="$EX/conf/rocoto.yaml" -cp "$EX/target/classes:$EX/target/lib/*" "com.example.embedded.$APP" "$PORT" > "$WORK/server.log" 2>&1 &
  PID=$!
  wait_for "http://localhost:$PORT/rocoto"
  common_checks "http://localhost:$PORT/rocoto"
  check "the application still answers next to Rocoto" "Hello from the" "$(curl -s --max-time 10 "http://localhost:$PORT/hello" | cut -c1-14)"
  stop

  echo "$server: standalone with TLS (java -jar rocoto-$server-$VERSION-all.jar)"
  java -Drocoto.config="$WORK/rocoto-tls.yaml" -jar "$ALL" > "$WORK/server.log" 2>&1 &
  PID=$!
  wait_for "https://localhost:$PORT/rocoto"
  common_checks "https://localhost:$PORT/rocoto"
  check "outside of the context path" 404 "$(status "https://localhost:$PORT/other")"
  stop

  echo "$server: Java agent in another JVM, plain HTTP listener"
  java "-javaagent:$ALL=config=$WORK/rocoto-plain.yaml,port=$PORT" "$WORK/SleepingApp.java" > "$WORK/server.log" 2>&1 &
  PID=$!
  wait_for "http://localhost:$PORT/rocoto"
  check "the application runs" true "$(grep -q 'application is running' "$WORK/server.log" && echo true || echo false)"
  check "credentials over plain HTTP are refused" 403 "$(status -u "rita:$PASSWORD" "http://localhost:$PORT/rocoto/api/v1/server")"
  check "the server of the agent is relocated (no clash with the application's own $server)" true \
    "$([ "$(unzip -Z1 "$ALL" | grep -c "^io/rocoto/shaded/" || true)" -gt 0 ] && echo true || echo false)"
  # Also the multi-release copies (META-INF/versions/N/...): the shade plugin does not relocate them.
  check "no server class left in its original package" "" \
    "$(unzip -Z1 "$ALL" | grep '\.class$' | sed 's#^META-INF/versions/[0-9]*/##' \
      | grep -E '^(io/undertow|io/netty|org/xnio|org/jboss|org/wildfly|io/smallrye)/' | head -3 || true)"
  stop
done

echo "auth.mode other than builtin is refused (there is no container)"
printf 'auth:\n  mode: chain\n' > "$WORK/rocoto-chain.yaml"
OUT="$(java -Drocoto.config="$WORK/rocoto-chain.yaml" -jar "$ROOT/rocoto-netty/target/rocoto-netty-$VERSION-all.jar" 2>&1 || true)"
check "clear error message" true "$(echo "$OUT" | grep -c 'set auth.mode: builtin' >/dev/null && echo true || echo false)"

if [ "$FAILED" = "1" ]; then echo "embedded smoke test FAILED"; exit 1; fi
echo "embedded smoke test passed"

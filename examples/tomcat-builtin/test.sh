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

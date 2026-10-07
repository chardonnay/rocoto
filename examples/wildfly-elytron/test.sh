#!/usr/bin/env bash
# Runs the requests documented in README.md and fails on the first unexpected status code.
# DEMO credentials only - see README.md.
set -euo pipefail

ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
PASSWORD="demo-password-123"
TOKEN="rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY"
ORDERS="com.example.demo%3Atype%3DOrders"
failures=0

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

expect 200 "liveness probe is anonymous"                 "$ROCOTO_URL/api/v1/health/live"
expect 401 "anonymous request"                           "$ROCOTO_URL/api/v1/server"
expect 401 "Elytron user, wrong password"                -u "wilma:wrong-password-000" "$ROCOTO_URL/api/v1/server"
expect 200 "Elytron user wilma"                          -u "wilma:$PASSWORD" "$ROCOTO_URL/api/v1/server"
expect 200 "built-in API token still works (chain)"      -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/api/v1/server"
expect 200 "reader: normalized data sources"             -u "walt:$PASSWORD" "$ROCOTO_URL/api/v1/resources/datasources"
expect 403 "reader: exec is denied"                      -u "walt:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"com.example.demo:type=Orders","operation":"resetCounters"}' "$ROCOTO_URL/api/v1/exec"
expect 200 "operator: exec drainQueue(5)"                -u "olga:$PASSWORD" "${json[@]}" \
  -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' "$ROCOTO_URL/api/v1/exec"
expect 200 "admin: write MaintenanceMode=false"          -u "wilma:$PASSWORD" "${json[@]}" -X PUT \
  -d '{"value":false}' "$ROCOTO_URL/api/v1/mbeans/$ORDERS/attributes/MaintenanceMode"
expect 403 "authenticated user without a Rocoto role"    -u "nora:$PASSWORD" \
  "$ROCOTO_URL/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/Verbose"
expect 200 "token: metrics"                              -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics"

caller="$(curl -s -u "wilma:$PASSWORD" "$ROCOTO_URL/api/v1/server")"
case "$caller" in
  *'"authenticatedBy":"container"'*) echo "ok         wilma was authenticated by the container (Elytron)" ;;
  *) echo "FAIL       unexpected caller: $caller"; failures=$((failures + 1)) ;;
esac
case "$caller" in
  *'"adapter":"wildfly"'*) echo "ok         the WildFly adapter is active" ;;
  *) echo "FAIL       unexpected adapter: $caller"; failures=$((failures + 1)) ;;
esac
metrics="$(curl -s -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics")"
for metric in jvm_memory_heap_used_bytes wildfly_undertow_requests_total wildfly_datasource_connections_active demo_orders_processed_total; do
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

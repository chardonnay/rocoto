#!/usr/bin/env bash
# Runs the requests documented in README.md and fails on the first unexpected status code.
# DEMO credentials only - see README.md.
set -euo pipefail

cd "$(dirname "$0")"
ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
export KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
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

ADMIN_JWT="$(./get-token.sh kate)"
READER_JWT="$(./get-token.sh ken)"
json=(-H 'Content-Type: application/json')
drain='{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}'

expect 401 "anonymous request"                     "$ROCOTO_URL/api/v1/server"
expect 401 "garbage instead of a JWT"              -H "Authorization: Bearer not.a.jwt" "$ROCOTO_URL/api/v1/server"
expect 401 "Basic is disabled (auth.builtin.basic: false)" -u "kate:demo-password-123" "$ROCOTO_URL/api/v1/server"
expect 200 "kate (rocoto-admin)"                   -H "Authorization: Bearer $ADMIN_JWT" "$ROCOTO_URL/api/v1/server"
expect 200 "ken (rocoto-reader) may read"          -H "Authorization: Bearer $READER_JWT" \
  "$ROCOTO_URL/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage"
expect 403 "ken may not exec"                      -H "Authorization: Bearer $READER_JWT" "${json[@]}" -d "$drain" "$ROCOTO_URL/api/v1/exec"
expect 200 "kate may exec"                         -H "Authorization: Bearer $ADMIN_JWT" "${json[@]}" -d "$drain" "$ROCOTO_URL/api/v1/exec"
expect 200 "API token next to JWT"                 -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" "$ROCOTO_URL/metrics"

caller="$(curl -s -H "Authorization: Bearer $ADMIN_JWT" "$ROCOTO_URL/api/v1/server")"
case "$caller" in
  *'"name":"kate"'*'"authenticatedBy":"jwt"'*) echo "ok         caller is kate, authenticated by jwt" ;;
  *) echo "FAIL       unexpected caller: $caller"; failures=$((failures + 1)) ;;
esac

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"

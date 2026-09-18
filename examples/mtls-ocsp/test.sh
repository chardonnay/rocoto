#!/usr/bin/env bash
# Runs the requests documented in README.md and fails on the first unexpected result.
set -euo pipefail

ROCOTO_URL="${ROCOTO_URL:-https://localhost:8443/rocoto}"
CERTS="$(cd "$(dirname "$0")" && pwd)/certs"
failures=0

expect() {
  local want="$1" what="$2" got
  shift 2
  got="$(curl -s -o /dev/null -w '%{http_code}' --cacert "$CERTS/ca.crt" "$@" || true)"
  if [ "$got" = "$want" ]; then
    printf 'ok    %s  %s\n' "$got" "$what"
  else
    printf 'FAIL  %s  %s (expected %s)\n' "$got" "$what" "$want"
    failures=$((failures + 1))
  fi
}

valid=(--cert "$CERTS/client.crt" --key "$CERTS/client.key")
revoked=(--cert "$CERTS/revoked.crt" --key "$CERTS/revoked.key")

expect 401 "no client certificate"                 "$ROCOTO_URL/api/v1/server"
expect 200 "valid client certificate (ops-alice)"  "${valid[@]}" "$ROCOTO_URL/api/v1/server"
expect 401 "revoked client certificate"            "${revoked[@]}" "$ROCOTO_URL/api/v1/server"
expect 200 "ops-alice is an operator: exec gc"     "${valid[@]}" -H 'Content-Type: application/json' \
  -d '{"mbean":"java.lang:type=Memory","operation":"gc"}' "$ROCOTO_URL/api/v1/exec"

caller="$(curl -s --cacert "$CERTS/ca.crt" "${valid[@]}" "$ROCOTO_URL/api/v1/server")"
case "$caller" in
  *'"name":"ops-alice"'*'"authenticatedBy":"mtls"'*) echo "ok         caller is ops-alice, authenticated by mtls" ;;
  *) echo "FAIL       unexpected caller: $caller"; failures=$((failures + 1)) ;;
esac

# OCSP stapling as seen by a TLS client
host_port="${ROCOTO_URL#https://}"
host_port="${host_port%%/*}"
if echo | openssl s_client -connect "$host_port" -status -CAfile "$CERTS/ca.crt" 2>/dev/null \
    | grep -q "OCSP Response Status: successful"; then
  echo "ok         the server staples an OCSP response"
else
  echo "FAIL       no stapled OCSP response"; failures=$((failures + 1))
fi

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"

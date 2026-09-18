#!/usr/bin/env bash
# Checks the scrape chain Rocoto -> Prometheus -> Grafana. DEMO credentials only - see README.md.
set -euo pipefail

ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
TOKEN="$(cat "$(dirname "$0")/prometheus/rocoto-token")"
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

expect 401 "metrics without a token"  "$ROCOTO_URL/metrics"
expect 200 "metrics with the token"   -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL/metrics"
expect 200 "Prometheus is ready"      "$PROMETHEUS_URL/-/ready"
expect 200 "Grafana is healthy"       "$GRAFANA_URL/api/health"
expect 200 "dashboard is provisioned" "$GRAFANA_URL/api/dashboards/uid/rocoto-demo"

# The first scrape happens up to 15 s after Prometheus started.
up=""
for _ in $(seq 1 15); do
  up="$(curl -s "$PROMETHEUS_URL/api/v1/query" --data-urlencode 'query=up{job="rocoto"}' || true)"
  case "$up" in *'"1"]'*) break ;; esac
  sleep 2
done
case "$up" in
  *'"1"]'*) echo 'ok         Prometheus reports up{job="rocoto"} = 1' ;;
  *) echo "FAIL       target is not up: $up"; failures=$((failures + 1)) ;;
esac

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"

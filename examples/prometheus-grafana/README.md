# Prometheus and Grafana

Adds Prometheus and Grafana to the [tomcat-builtin](../tomcat-builtin/README.md) setup. Prometheus
scrapes `GET /rocoto/metrics` with an API token read from a file; Grafana gets a provisioned
datasource and a small dashboard (heap, GC, threads, Tomcat busy threads, demo application order
rate and queue depth).

> **LOCAL DEMO ONLY - never use in production.** Plain HTTP, `auth.allowInsecureTransport: true`,
> a published token and anonymous Grafana access.

| File | Purpose |
|------|---------|
| `docker-compose.yaml` | Tomcat (configuration reused from `../tomcat-builtin/conf`), Prometheus, Grafana |
| `prometheus/prometheus.yml` | scrape job with `authorization.credentials_file` and `metrics_path: /rocoto/metrics` |
| `prometheus/rocoto-token` | the demo token `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY` (demo only, role `rocoto-reader`) |
| `grafana/provisioning/...` | datasource and dashboard provider |
| `grafana/dashboards/rocoto-demo.json` | the dashboard |

## Prerequisites

Docker with the compose plugin, `make`, `curl`; both WARs built (`mvn install -DskipTests` in the
repository root - Rocoto is not on Maven Central - and `mvn -q package` in `examples/demo-app`).

## Run

```sh
make up
make test
make down
```

- Prometheus: <http://localhost:9090/targets> - the job `rocoto` must be `UP`
- Grafana: <http://localhost:3000/d/rocoto-demo> (anonymous viewer; admin login `admin` /
  `demo-grafana-123`, demo only)

## What to try

```sh
TOKEN=$(cat prometheus/rocoto-token)

curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/rocoto/metrics
# 401

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/rocoto/metrics | grep -E '^(demo_|tomcat_threads_busy)'
# tomcat_threads_busy{connector="http-nio-8080"} 1
# demo_orders_processed_total 22
# demo_orders_queue_depth 0
# demo_maintenance_mode 0

# OpenMetrics by content negotiation (ends with "# EOF")
curl -s -H "Authorization: Bearer $TOKEN" -H 'Accept: application/openmetrics-text' \
     http://localhost:8080/rocoto/metrics | tail -n 1

curl -s http://localhost:9090/api/v1/query --data-urlencode 'query=up{job="rocoto"}'
# {"status":"success","data":{"resultType":"vector","result":[{"metric":{...,"job":"rocoto"},"value":[...,"1"]}]}}
```

Switch the demo application into maintenance mode (as `ada`, see tomcat-builtin) and watch the
order rate drop to zero and the queue grow on the dashboard.

A scraper only receives samples its identity may read. Create a narrower token with
`token create prometheus-jvm --roles rocoto-reader --mbeans "java.lang:*"` and the `tomcat_*` and
`demo_*` families disappear from its scrape.

The `/metrics` output shown above was captured from the same WARs and configuration in an embedded
Tomcat; the Compose file, the Prometheus configuration and the Grafana provisioning were written
without a Docker daemon at hand.

## What to look at next

Cookbook: [prometheus-grafana](../../docs/examples/prometheus-grafana.md),
[custom-metrics-profile](../../docs/examples/custom-metrics-profile.md),
[otlp-collector](../../docs/examples/otlp-collector.md).

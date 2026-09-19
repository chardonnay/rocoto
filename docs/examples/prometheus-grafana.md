# Prometheus and Grafana

## Goal

Scrape `GET /rocoto/metrics` with Prometheus using an API token stored in a file, and show JVM,
Tomcat and application metrics on a provisioned Grafana dashboard.

Runnable project: [`examples/prometheus-grafana`](../../examples/prometheus-grafana/README.md)
(on top of [tomcat-builtin-auth](tomcat-builtin-auth.md)).

## Prerequisites

- Docker with the compose plugin, `make`, `curl`
- `mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central),
  `mvn -q package` in `examples/demo-app`

## Files

[`examples/prometheus-grafana/docker-compose.yaml`](../../examples/prometheus-grafana/docker-compose.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# Tomcat speaks plain HTTP and ../tomcat-builtin/conf/rocoto.yaml sets
# auth.allowInsecureTransport. The API token in prometheus/rocoto-token is published in
# the README. Grafana allows anonymous viewers and uses a demo admin password.
# =====================================================================================
services:
  tomcat:
    image: tomcat:10.1-jdk17
    ports:
      - "8080:8080"
    volumes:
      - ../../rocoto-war/target/rocoto.war:/usr/local/tomcat/webapps/rocoto.war:ro
      - ../demo-app/target/demo-app.war:/usr/local/tomcat/webapps/demo-app.war:ro
      - ../tomcat-builtin/conf/rocoto.yaml:/usr/local/tomcat/conf/rocoto.yaml:ro
      - ../tomcat-builtin/conf/rocoto-users.yaml:/usr/local/tomcat/conf/rocoto-users.yaml:ro

  prometheus:
    image: prom/prometheus:v2.53.2
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/rocoto-token:/etc/prometheus/rocoto-token:ro
    depends_on:
      - tomcat

  grafana:
    image: grafana/grafana-oss:11.2.0
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: demo-grafana-123   # DEMO ONLY
      GF_AUTH_ANONYMOUS_ENABLED: "true"              # DEMO ONLY
      GF_AUTH_ANONYMOUS_ORG_ROLE: Viewer
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    depends_on:
      - prometheus
```

The scrape job. `/metrics` is authenticated like every other endpoint; the scraper only receives
samples its identity may read:

[`examples/prometheus-grafana/prometheus/prometheus.yml`](../../examples/prometheus-grafana/prometheus/prometheus.yml)

```yaml
# LOCAL DEMO ONLY - never use in production: the target is scraped over plain HTTP.
# For a real server use "scheme: https" (plus tls_config.ca_file for a private CA).
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: rocoto
    metrics_path: /rocoto/metrics
    scheme: http
    authorization:
      type: Bearer
      # The file contains nothing but the API token (rct_...). Prometheus re-reads it on every scrape.
      credentials_file: /etc/prometheus/rocoto-token
    static_configs:
      - targets: ["tomcat:8080"]
        labels:
          server: tomcat-demo
```

`prometheus/rocoto-token` contains nothing but the token (demo only):

[`examples/prometheus-grafana/prometheus/rocoto-token`](../../examples/prometheus-grafana/prometheus/rocoto-token)

```text
rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY
```

The metric rules for the demo application are part of the Rocoto configuration shared with
tomcat-builtin ([`examples/tomcat-builtin/conf/rocoto.yaml`](../../examples/tomcat-builtin/conf/rocoto.yaml),
section `metrics`).

[`examples/prometheus-grafana/grafana/provisioning/datasources/prometheus.yaml`](../../examples/prometheus-grafana/grafana/provisioning/datasources/prometheus.yaml)

```yaml
apiVersion: 1
datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

[`examples/prometheus-grafana/grafana/provisioning/dashboards/dashboards.yaml`](../../examples/prometheus-grafana/grafana/provisioning/dashboards/dashboards.yaml)

```yaml
apiVersion: 1
providers:
  - name: rocoto
    type: file
    disableDeletion: true
    allowUiUpdates: false
    options:
      path: /var/lib/grafana/dashboards
```

[`examples/prometheus-grafana/grafana/dashboards/rocoto-demo.json`](../../examples/prometheus-grafana/grafana/dashboards/rocoto-demo.json)

```json
{
  "uid": "rocoto-demo",
  "title": "Rocoto demo: JVM, Tomcat and demo application",
  "tags": [
    "rocoto"
  ],
  "schemaVersion": 39,
  "version": 1,
  "editable": false,
  "timezone": "browser",
  "refresh": "15s",
  "time": {
    "from": "now-15m",
    "to": "now"
  },
  "templating": {
    "list": []
  },
  "annotations": {
    "list": []
  },
  "panels": [
    {
      "id": 1,
      "type": "timeseries",
      "title": "Heap memory",
      "datasource": {
        "type": "prometheus",
        "uid": "prometheus"
      },
      "gridPos": {
        "h": 8,
        "w": 12,
        "x": 0,
        "y": 0
      },
      "fieldConfig": {
        "defaults": {
          "unit": "bytes",
          "custom": {
            "lineWidth": 1,
            "fillOpacity": 10,
            "showPoints": "never"
          }
        },
        "overrides": []
      },
      "options": {
        "legend": {
          "displayMode": "list",
          "placement": "bottom",
          "showLegend": true
        },
        "tooltip": {
          "mode": "multi",
          "sort": "none"
        }
      },
      "targets": [
        {
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "jvm_memory_heap_used_bytes",
          "legendFormat": "used"
        },
        {
          "refId": "B",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "jvm_memory_heap_committed_bytes",
          "legendFormat": "committed"
        },
        {
          "refId": "C",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "jvm_memory_heap_max_bytes",
          "legendFormat": "max"
        }
      ]
    },
    {
      "id": 2,
      "type": "timeseries",
      "title": "Garbage collection time per second",
      "datasource": {
        "type": "prometheus",
        "uid": "prometheus"
      },
      "gridPos": {
        "h": 8,
        "w": 12,
        "x": 12,
        "y": 0
      },
      "fieldConfig": {
        "defaults": {
          "unit": "s",
          "custom": {
            "lineWidth": 1,
            "fillOpacity": 10,
            "showPoints": "never"
          }
        },
        "overrides": []
      },
      "options": {
        "legend": {
          "displayMode": "list",
          "placement": "bottom",
          "showLegend": true
        },
        "tooltip": {
          "mode": "multi",
          "sort": "none"
        }
      },
      "targets": [
        {
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "rate(jvm_gc_collection_seconds_total[5m])",
          "legendFormat": "{{gc}}"
        }
      ]
    },
    {
      "id": 3,
      "type": "timeseries",
      "title": "JVM threads",
      "datasource": {
        "type": "prometheus",
        "uid": "prometheus"
      },
      "gridPos": {
        "h": 8,
        "w": 12,
        "x": 0,
        "y": 8
      },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "custom": {
            "lineWidth": 1,
            "fillOpacity": 10,
            "showPoints": "never"
          }
        },
        "overrides": []
      },
      "options": {
        "legend": {
          "displayMode": "list",
          "placement": "bottom",
          "showLegend": true
        },
        "tooltip": {
          "mode": "multi",
          "sort": "none"
        }
      },
      "targets": [
        {
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "jvm_threads_current",
          "legendFormat": "live"
        },
        {
          "refId": "B",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "jvm_threads_daemon",
          "legendFormat": "daemon"
        },
        {
          "refId": "C",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "jvm_threads_peak",
          "legendFormat": "peak"
        }
      ]
    },
    {
      "id": 4,
      "type": "timeseries",
      "title": "Tomcat connector threads",
      "datasource": {
        "type": "prometheus",
        "uid": "prometheus"
      },
      "gridPos": {
        "h": 8,
        "w": 12,
        "x": 12,
        "y": 8
      },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "custom": {
            "lineWidth": 1,
            "fillOpacity": 10,
            "showPoints": "never"
          }
        },
        "overrides": []
      },
      "options": {
        "legend": {
          "displayMode": "list",
          "placement": "bottom",
          "showLegend": true
        },
        "tooltip": {
          "mode": "multi",
          "sort": "none"
        }
      },
      "targets": [
        {
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "tomcat_threads_busy",
          "legendFormat": "busy {{connector}}"
        },
        {
          "refId": "B",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "tomcat_threads_current",
          "legendFormat": "current {{connector}}"
        }
      ]
    },
    {
      "id": 5,
      "type": "timeseries",
      "title": "Demo application: orders processed per second",
      "datasource": {
        "type": "prometheus",
        "uid": "prometheus"
      },
      "gridPos": {
        "h": 8,
        "w": 12,
        "x": 0,
        "y": 16
      },
      "fieldConfig": {
        "defaults": {
          "unit": "ops",
          "custom": {
            "lineWidth": 1,
            "fillOpacity": 10,
            "showPoints": "never"
          }
        },
        "overrides": []
      },
      "options": {
        "legend": {
          "displayMode": "list",
          "placement": "bottom",
          "showLegend": true
        },
        "tooltip": {
          "mode": "multi",
          "sort": "none"
        }
      },
      "targets": [
        {
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "rate(demo_orders_processed_total[1m])",
          "legendFormat": "orders/s"
        }
      ]
    },
    {
      "id": 6,
      "type": "timeseries",
      "title": "Demo application: queue depth",
      "datasource": {
        "type": "prometheus",
        "uid": "prometheus"
      },
      "gridPos": {
        "h": 8,
        "w": 12,
        "x": 12,
        "y": 16
      },
      "fieldConfig": {
        "defaults": {
          "unit": "short",
          "custom": {
            "lineWidth": 1,
            "fillOpacity": 10,
            "showPoints": "never"
          }
        },
        "overrides": []
      },
      "options": {
        "legend": {
          "displayMode": "list",
          "placement": "bottom",
          "showLegend": true
        },
        "tooltip": {
          "mode": "multi",
          "sort": "none"
        }
      },
      "targets": [
        {
          "refId": "A",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "demo_orders_queue_depth",
          "legendFormat": "queued orders"
        },
        {
          "refId": "B",
          "datasource": {
            "type": "prometheus",
            "uid": "prometheus"
          },
          "expr": "demo_maintenance_mode",
          "legendFormat": "maintenance mode"
        }
      ]
    }
  ]
}
```

[`examples/prometheus-grafana/test.sh`](../../examples/prometheus-grafana/test.sh)

```sh
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
```

## Commands

```sh
cd examples/prometheus-grafana
make up
make test
# Prometheus targets: http://localhost:9090/targets
# Grafana dashboard:  http://localhost:3000/d/rocoto-demo
make down
```

## Expected output

```sh
curl -s -H "Authorization: Bearer $(cat prometheus/rocoto-token)" http://localhost:8080/rocoto/metrics
```

```text
# HELP jvm_memory_heap_used_bytes Used heap memory
# TYPE jvm_memory_heap_used_bytes gauge
jvm_memory_heap_used_bytes 51805568
...
# HELP tomcat_threads_busy Busy connector threads
# TYPE tomcat_threads_busy gauge
tomcat_threads_busy{connector="http-nio-8080"} 1
...
# HELP demo_orders_processed_total Orders processed by the demo application
# TYPE demo_orders_processed_total counter
demo_orders_processed_total 22
# HELP demo_orders_queue_depth Orders waiting in the demo application's queue
# TYPE demo_orders_queue_depth gauge
demo_orders_queue_depth 0
```

With `Accept: application/openmetrics-text` the same endpoint answers in OpenMetrics format,
terminated by `# EOF`. Without a token the answer is `401`.

The metrics output was captured from the same WARs and configuration in an embedded Tomcat. The
Compose project was executed with Docker (Docker 29, September 2026, Prometheus 2.53.2, Grafana 11.2.0): `make test`
passes and every query of the provisioned dashboard returns data.

## For a real server

```yaml
scrape_configs:
  - job_name: rocoto
    metrics_path: /rocoto/metrics
    scheme: https
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/secrets/rocoto-token
    tls_config:
      ca_file: /etc/prometheus/secrets/internal-ca.crt
    static_configs:
      - targets: ["app1.example.com:8443", "app2.example.com:8443"]
```

Give the scraper a narrow token:

```sh
java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader --days 365 \
     --mbeans "java.lang:*;java.nio:*;Catalina:*;com.example.demo:*" --file rocoto-users.yaml
```

With `tls.selfProbe.url` configured, `/metrics` also exports `rocoto_tls_cert_expiry_seconds` and
`rocoto_tls_ocsp_stapled` - useful for certificate expiry alerts.

## What to look at next

- [custom-metrics-profile](custom-metrics-profile.md), [otlp-collector](otlp-collector.md)
- [kubernetes-deployment](kubernetes-deployment.md) - the same with a ServiceMonitor

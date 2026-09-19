# Push metrics to an OpenTelemetry Collector

## Goal

Instead of (or in addition to) being scraped, Rocoto pushes the same metrics every
`intervalSeconds` to an OpenTelemetry Collector using OTLP/HTTP with JSON encoding
(`POST /v1/metrics`, `Content-Type: application/json`). No OpenTelemetry SDK or Java agent is
involved.

## Prerequisites

- A running Rocoto, for example [tomcat-builtin-auth](tomcat-builtin-auth.md)
- An OpenTelemetry Collector (`otel/opentelemetry-collector-contrib`) reachable from the
  application server

## Files

`otel-collector.yaml`:

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318        # Rocoto posts to /v1/metrics on this port

processors:
  batch: {}

exporters:
  debug:
    verbosity: detailed               # prints the received metrics to the collector log
  prometheus:                         # optional: re-expose them for a Prometheus scrape
    endpoint: 0.0.0.0:8889

service:
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [batch]
      exporters: [debug, prometheus]
```

The `metrics.otlp` section of `rocoto.yaml` for production - HTTPS endpoint, credentials from the
environment:

```yaml
metrics:
  enabled: true
  profiles: [jvm, auto]
  otlp:
    enabled: true
    endpoint: https://otel-collector.example.com:4318/v1/metrics
    intervalSeconds: 60
    serviceName: orders-tomcat               # resource attribute service.name
    resourceAttributes:
      deployment.environment: production
      host.name: app1.example.com
    headers:
      Authorization: "${env:OTLP_AUTH}"      # e.g. "Bearer ..." checked by the collector's auth extension
    roles: [rocoto-reader]                   # roles of the internal identity "otlp-exporter"

tls:
  outbound:                                  # applies to the OTLP push (and the JWKS download)
    revocation: soft-fail
    truststore: /etc/rocoto/internal-ca.p12
    truststorePassword: ${env:ROCOTO_OUTBOUND_TRUSTSTORE_PASSWORD}
```

For a local demo next to the tomcat-builtin compose project - add the service and the settings:

```yaml
# docker-compose.yaml (additional service) - LOCAL DEMO ONLY - never use in production
  otel-collector:
    image: otel/opentelemetry-collector-contrib:latest
    command: ["--config=/etc/otelcol/config.yaml"]
    volumes:
      - ./otel-collector.yaml:/etc/otelcol/config.yaml:ro
    ports:
      - "8889:8889"
```

```yaml
# conf/rocoto.yaml (additional keys below "metrics:") - LOCAL DEMO ONLY - never use in production
  otlp:
    enabled: true
    endpoint: http://otel-collector:4318/v1/metrics
    allowInsecure: true                      # http:// endpoints are refused without it
    intervalSeconds: 15
    serviceName: tomcat-demo
    resourceAttributes: { deployment.environment: demo }
```

## Commands

```sh
docker compose up -d
docker compose logs -f otel-collector
```

## Expected output

The `debug` exporter prints each received batch, similar to:

```text
ResourceMetrics #0
Resource attributes:
     -> service.name: Str(tomcat-demo)
     -> deployment.environment: Str(demo)
ScopeMetrics #0
InstrumentationScope io.rocoto
Metric #0
Descriptor:
     -> Name: jvm_memory_heap_used_bytes
     -> Description: Used heap memory
     -> DataType: Gauge
NumberDataPoints #0
Value: 157960624.000000
```

What Rocoto actually sends was captured with a small HTTP sink instead of a collector
(`metrics.otlp` as in the demo snippet, embedded Tomcat):

```text
POST /v1/metrics
Content-Type: application/json

{"resourceMetrics":[{"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"tomcat-demo"}},
 {"key":"deployment.environment","value":{"stringValue":"demo"}}]},
 "scopeMetrics":[{"scope":{"name":"io.rocoto"},"metrics":[
  {"name":"jvm_memory_heap_used_bytes","description":"Used heap memory",
   "gauge":{"dataPoints":[{"attributes":[],"startTimeUnixNano":"1789762451674000000","timeUnixNano":"1789762461738000000","asDouble":1.57960624E8}]}}, ...
```

The local demo was executed with Docker (Docker 29, September 2026, `otelcol-contrib` 0.161.0): the collector accepts
Rocoto's OTLP/HTTP JSON and re-exposes `jvm_*` and `tomcat_*` on port 8889 with
`job="tomcat-demo"`. The `otlp:` keys belong directly below `metrics:`.

## Notes

- The exporter has no HTTP request to take an identity from. It runs as the internal identity
  `otlp-exporter` with `metrics.otlp.roles` and goes through the same policy and masking as any
  caller. Remove MBeans from its view with `deny` rules for that role, or give it a role of its own.
- `headers` values may use `${env:...}`, `${sys:...}` or `${file:...}`; each value must be a
  single reference.
- The metric names, help texts and labels are those of the Prometheus endpoint; both can be
  active at the same time.

## What to look at next

- [custom-metrics-profile](custom-metrics-profile.md), [prometheus-grafana](prometheus-grafana.md)
- [Configuration reference](../reference/configuration.md): `metrics.otlp`, `tls.outbound`

# 06 - Metrics

Rocoto turns MBean attributes into metrics. `GET /metrics` serves them for scraping - in
Prometheus text, OpenMetrics, InfluxDB line protocol, Graphite plaintext, StatsD or OTLP/JSON -
and optional exporters push them to OpenTelemetry (OTLP), Graphite, StatsD and InfluxDB. Mapping rules decide which attribute becomes which metric; bundled profiles
cover the JVM, Tomcat, WildFly and Payara.

Configuration keys are listed in the [configuration reference](../reference/configuration.md)
under `metrics`.

## Every sample is read through the pipeline

`/metrics` is not a side door. The scraper authenticates like any other client, and the collector
reads every value through the `RequestPipeline` with the scraper's identity:

- A sample only appears if the identity may `search` the MBean and `read` the attribute.
- An API token with an MBean scope only yields samples from MBeans inside the scope.
- Denied, masked, missing, failing and non-numeric values are **skipped silently**. A restricted
  scraper gets a smaller, valid exposition - never an error and never a `403` for the whole scrape.
- With `audit.reads: true` every sample read is an audit record. Leave it `false` on scraped
  instances or expect one line per sample per scrape.

If a metric you expect is missing, check in this order: does the MBean exist
(`GET /api/v1/mbeans?pattern=...`), may the scraping identity read it, is the value numeric, and -
on WildFly and Payara - are statistics enabled.

## Scraping

```bash
# Prometheus text format 0.0.4
curl -s -H "Authorization: Bearer $TOKEN" https://server:8443/rocoto/metrics

# OpenMetrics 1.0 (selected by the Accept header, ends with "# EOF")
curl -s -H "Authorization: Bearer $TOKEN" -H "Accept: application/openmetrics-text" \
  https://server:8443/rocoto/metrics
```

```text
# HELP jvm_memory_heap_used_bytes Used heap memory
# TYPE jvm_memory_heap_used_bytes gauge
jvm_memory_heap_used_bytes 73400320
# HELP jvm_gc_collections_total Garbage collections
# TYPE jvm_gc_collections_total counter
jvm_gc_collections_total{gc="G1 Young Generation"} 12
# HELP tomcat_threads_busy Busy connector threads
# TYPE tomcat_threads_busy gauge
tomcat_threads_busy{connector="https-jsse-nio-8443"} 1
```

| `Accept` header | Response `Content-Type` |
|-----------------|-------------------------|
| contains `application/openmetrics-text` | `application/openmetrics-text; version=1.0.0; charset=utf-8` |
| anything else | `text/plain; version=0.0.4; charset=utf-8` |

In OpenMetrics output the family name of a counter is written without the `_total` suffix in
`# HELP` and `# TYPE`, while the samples keep it, as the specification requires.

With `metrics.enabled: false` the endpoint answers `404`.

### Other formats

`?format=` selects one of the time series line protocols. The samples are the same; only the
syntax differs.

| `format` | Content type | Typical consumer |
|----------|--------------|------------------|
| `prometheus` (default) | `text/plain; version=0.0.4` | Prometheus, VictoriaMetrics, Grafana Agent |
| `openmetrics` | `application/openmetrics-text` | Prometheus with OpenMetrics negotiation |
| `influx` | `text/plain` | Telegraf `inputs.http` with `data_format = "influx"`, InfluxDB tooling |
| `graphite` | `text/plain` | scripts that forward to Carbon |
| `statsd` | `text/plain` | debugging the StatsD export (StatsD itself is push-only) |
| `otlp` | `application/json` | inspecting what the OTLP exporter sends, tools that read OTLP/JSON |

An unknown `format` is answered with `406`.

```bash
curl -s -H "Authorization: Bearer $TOKEN" "https://server:8443/rocoto/metrics?format=influx"
```

```text
jvm_memory_heap_used_bytes value=65677152.0 1791366991406000000
jvm_gc_collections_total,gc=G1\ Young\ Generation value=42.0 1791366991406000000
```

- **InfluxDB line protocol**: the metric name is the measurement, labels are tags, the sample is
  the float field `value`. `&precision=ns|us|ms|s` sets the timestamp unit (default `ns`, an
  invalid value is `400`). Commas, blanks and equals signs are escaped; line breaks inside label
  values are replaced, so a label can never start a second line. Empty label values (invalid as
  tags) and NaN/infinite samples are left out.
- **Graphite plaintext**: `prefix.metric.labelValue1.labelValue2 value timestamp` with the
  timestamp in seconds. Every path segment is reduced to letters, digits, `_` and `-`. With
  `metrics.push.graphite.tags: true` Graphite 1.1 tags are used instead:
  `metric;label=value value timestamp`.
- **StatsD**: `prefix.metric.labelValue:value|g`. When pulled, counters are reported as gauges
  (a StatsD counter is an increment and the pull has no memory). With
  `metrics.push.statsd.tags: true` labels become DogStatsD tags (`|#label:value`). A negative gauge
  is sent as `0|g` followed by the value, because StatsD reads `-3|g` as "decrease by three".
- **OTLP/JSON**: the `ExportMetricsServiceRequest` the OTLP exporter sends.

Graphite and StatsD naming (prefix, tags) is taken from the push sections below, so pulled and
pushed series carry the same names. The REST API has its own output formats (XML, YAML, CSV, ...);
see [15 - Output formats](15-output-formats.md).

### A scoped token for the scraper

```bash
java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader --days 365 \
  --mbeans "java.lang:*;java.nio:*;Catalina:*" --file rocoto-users.yaml

# store the printed token where only Prometheus can read it
install -m 600 -o prometheus /dev/stdin /etc/prometheus/secrets/rocoto-token <<< "rct_prometheus_..."
```

On WildFly use `jboss.as:*` instead of `Catalina:*`; on Payara `amx:*`. For embedded Tomcat the
domain is `Tomcat`.

### Prometheus configuration

```yaml
scrape_configs:
  - job_name: rocoto
    scheme: https
    metrics_path: /rocoto/metrics
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/secrets/rocoto-token
    tls_config:
      ca_file: /etc/prometheus/ca.pem        # CA that issued the server certificate
    static_configs:
      - targets: ["app1.example.com:8443", "app2.example.com:8443"]
```

`scheme: https` is not optional: with the default configuration Rocoto answers a bearer token over
plain HTTP with `403`.

## Profiles

```yaml
metrics:
  enabled: true
  profiles: [jvm, auto]     # default
```

`auto` resolves to the profile named like the detected server adapter (`tomcat`, `wildfly`,
`payara`); with the `generic` adapter it adds nothing. Any other value that is not a bundled name
is treated as the path of a custom profile file, relative to the directory of `rocoto.yaml`.

### `jvm`

| Metric | Source |
|--------|--------|
| `jvm_memory_heap_used_bytes`, `jvm_memory_heap_committed_bytes`, `jvm_memory_heap_max_bytes` | `java.lang:type=Memory` `HeapMemoryUsage` |
| `jvm_memory_nonheap_used_bytes`, `jvm_memory_nonheap_committed_bytes` | `java.lang:type=Memory` `NonHeapMemoryUsage` |
| `jvm_memory_pool_used_bytes`, `jvm_memory_pool_max_bytes` {`pool`} | `java.lang:type=MemoryPool,name=*` `Usage` |
| `jvm_buffer_pool_used_bytes`, `jvm_buffer_pool_buffers` {`pool`} | `java.nio:type=BufferPool,name=*` |
| `jvm_gc_collections_total`, `jvm_gc_collection_seconds_total` {`gc`} | `java.lang:type=GarbageCollector,name=*` |
| `jvm_threads_current`, `jvm_threads_daemon`, `jvm_threads_peak`, `jvm_threads_started_total` | `java.lang:type=Threading` |
| `jvm_classes_loaded`, `jvm_classes_loaded_total`, `jvm_classes_unloaded_total` | `java.lang:type=ClassLoading` |
| `process_cpu_load_ratio`, `process_cpu_seconds_total`, `process_open_fds`, `process_max_fds` | `java.lang:type=OperatingSystem` |
| `system_load_average_1m`, `system_cpu_count` | `java.lang:type=OperatingSystem` |
| `process_uptime_seconds`, `process_start_time_seconds` | `java.lang:type=Runtime` |

### `tomcat`

MBean patterns use a wildcard domain (`*:type=ThreadPool,name=*`), so they match both `Catalina`
and the `Tomcat` domain of an embedded server.

| Metric | Source |
|--------|--------|
| `tomcat_threads_current`, `tomcat_threads_busy`, `tomcat_threads_max`, `tomcat_connections_current` {`connector`} | `type=ThreadPool` |
| `tomcat_requests_total`, `tomcat_request_errors_total`, `tomcat_request_processing_seconds_total`, `tomcat_sent_bytes_total`, `tomcat_received_bytes_total` {`connector`} | `type=GlobalRequestProcessor` |
| `tomcat_sessions_active`, `tomcat_sessions_created_total`, `tomcat_sessions_expired_total`, `tomcat_sessions_rejected_total` {`host`, `context`} | `type=Manager` |
| `tomcat_datasource_connections_active`, `tomcat_datasource_connections_idle`, `tomcat_datasource_connections_max` {`datasource`, `context`} | `type=DataSource`; on Apache TomEE also `openejb.management:ObjectType=datasources,*` (empty `context`) |

### `wildfly`

Reads the `jboss.as` domain. Pool, web and transaction statistics need `statistics-enabled=true`
on the respective subsystem (see [04 - The JMX connection](04-jmx-connection.md)).

| Metric | Source |
|--------|--------|
| `wildfly_datasource_connections_active`, `wildfly_datasource_connections_idle` {`datasource`} | `subsystem=datasources,data-source=*,statistics=pool` and `xa-data-source=*` |
| `wildfly_datasource_connections_available`, `wildfly_datasource_waiting_threads`, `wildfly_datasource_timeouts_total` {`datasource`} | `subsystem=datasources,data-source=*,statistics=pool` (non-XA only) |
| `wildfly_undertow_requests_total`, `wildfly_undertow_request_errors_total` {`server`, `listener`} | `subsystem=undertow,server=*,http-listener=*` and `https-listener=*` |
| `wildfly_undertow_sent_bytes_total`, `wildfly_undertow_received_bytes_total` {`server`, `listener`} | `subsystem=undertow,server=*,http-listener=*` only |
| `wildfly_sessions_active`, `wildfly_sessions_created_total`, `wildfly_sessions_expired_total`, `wildfly_sessions_rejected_total` {`deployment`} | `deployment=*,subsystem=undertow` |
| `wildfly_io_worker_threads_busy`, `wildfly_io_worker_queue_size`, `wildfly_io_worker_threads_max` {`worker`} | `subsystem=io,worker=*` |
| `wildfly_transactions_inflight`, `wildfly_transactions_committed_total`, `wildfly_transactions_aborted_total` | `subsystem=transactions` |

### `payara` (experimental)

Reads AMX monitoring MBeans; requires AMX and the monitoring level `HIGH` for
`jdbc-connection-pool`, `thread-pool`, `web-container` and `http-service`.

| Metric | Source |
|--------|--------|
| `payara_jdbc_connections_used`, `payara_jdbc_connections_free`, `payara_jdbc_wait_queue_length` {`pool`} | `amx:type=jdbc-connection-pool-mon,*` |
| `payara_threads_current`, `payara_threads_busy` {`pool`} | `amx:type=thread-pool-mon,*` |
| `payara_sessions_active` {`app`} | `amx:type=session-mon,*` |
| `payara_requests_total`, `payara_request_errors_total` {`server`} | `amx:type=request-mon,*` |

### `jetty`

Reads the MBeans of Jetty 12; requires Jetty's `jmx` module, the request metrics additionally the
`statistics` module (`java -jar $JETTY_HOME/start.jar --add-modules=jmx,statistics`).

| Metric | Source |
|--------|--------|
| `jetty_threads_current`, `jetty_threads_busy`, `jetty_threads_idle`, `jetty_threads_max`, `jetty_threads_queue_size` {`pool`} | `org.eclipse.jetty.util.thread:type=queuedthreadpool,*` |
| `jetty_sessions_active`, `jetty_sessions_created_total` {`context`} | `org.eclipse.jetty.session:type=defaultsessioncache,*` |
| `jetty_requests_total`, `jetty_requests_active`, `jetty_responses_4xx_total`, `jetty_responses_5xx_total` {`handler`} | `org.eclipse.jetty.server.handler:type=statisticshandler,*` |

The `context` label is the last segment of Jetty's context key: `rocoto` on Jetty 12.0, `_rocoto` on 12.1.

### `liberty`

Reads the MBeans of the `monitor-1.0` feature of Open Liberty / WebSphere Liberty.

| Metric | Source |
|--------|--------|
| `liberty_threads_current`, `liberty_threads_busy` {`pool`} | `WebSphere:type=ThreadPoolStats,*` |
| `liberty_sessions_live`, `liberty_sessions_active`, `liberty_sessions_created_total`, `liberty_sessions_expired_total` {`app`} | `WebSphere:type=SessionStats,*` (registered with the first session of an application) |
| `liberty_servlet_requests_total` {`servlet`} | `WebSphere:type=ServletStats,*` |
| `liberty_connectionpool_connections_managed`, `liberty_connectionpool_connections_free`, `liberty_connectionpool_wait_time_milliseconds` {`pool`} | `WebSphere:type=ConnectionPoolStats,*` (not covered by the smoke test) |

## Custom rules and profiles

Rules can be written inline under `metrics.rules` or collected in a profile file. Both use the
same format, and inline rules are added after all profiles.

```yaml
metrics:
  profiles: [jvm, auto, metrics/shop.yaml]      # relative to the directory of rocoto.yaml
  rules:
    - mbean: "com.example.shop:type=Orders"
      attribute: OpenOrders
      name: shop_orders_open
      help: "Orders waiting for fulfilment"
```

```yaml
# metrics/shop.yaml - a profile file has one top-level key: rules
rules:
  - mbean: "com.example.shop:type=Cache,name=*"   # pattern: one sample per matching MBean
    attribute: HitCount
    name: shop_cache_hits_total
    type: counter                                 # counter names must end with _total
    help: "Cache hits"
    labels: { cache: "${name}" }                  # ${key} = ObjectName key property
  - mbean: "com.example.shop:type=Cache,name=*"
    attribute: AverageGetTime
    name: shop_cache_get_seconds
    scale: 0.001                                  # milliseconds -> seconds
    labels: { cache: "${name}" }
  - mbean: "java.lang:type=Memory"
    attribute: HeapMemoryUsage                    # composite value, no path:
    name: shop_heap_bytes                         # one sample per numeric key, label key="used", ...
```

| Key | Required | Meaning |
|-----|----------|---------|
| `mbean` | yes | ObjectName or pattern |
| `attribute` | yes | attribute to read |
| `name` | yes | metric name, `[a-zA-Z_:][a-zA-Z0-9_:]*` |
| `type` | no | `gauge` (default) or `counter`; counter names must end with `_total` |
| `help` | no | help text, default: the metric name |
| `path` | no | key inside a `CompositeData` value, for example `used` |
| `labels` | no | label name to template; `${prop}` is replaced by the ObjectName key property (quotes removed), an unknown property by the empty string; `${prop\|basename}` keeps only the part after the last `/` |
| `scale` | no | multiplier, default `1.0` |

Behaviour worth knowing:

- Numbers and booleans (`1`/`0`) become samples; `NaN`, strings and everything else are skipped.
- A composite value without `path` yields one sample per numeric key with an additional `key`
  label. With `path`, a missing key yields no sample.
- Several rules may share a metric name (the WildFly profile does this for XA and non-XA data
  sources); their samples end up in one family. The type and help text of the first rule win.
- An invalid rule - missing key, bad metric or label name, counter without `_total`, malformed
  ObjectName - aborts start-up.
- Keep label cardinality bounded: a pattern that matches thousands of MBeans yields thousands of
  series.

Check a new rule without waiting for the next scrape:

```bash
curl -s -u rita https://server:8443/rocoto/metrics | grep shop_
```

## Push exporters

For environments without scraping, Rocoto pushes the metrics itself. Each target has its own
section under `metrics.push`; any number of them can run side by side.

```yaml
metrics:
  push:
    otlp:
      enabled: true
      endpoint: https://collector.example.com:4318/v1/metrics
      serviceName: shop-backend           # resource attribute service.name, default "rocoto"
      resourceAttributes: { deployment.environment: production, host.name: app1 }
      headers: { Authorization: "${env:OTLP_AUTH}" }
    graphite:
      enabled: true
      host: carbon.example.com
      port: 2003                          # default
      tls: false                          # true = TLS with host name verification
      prefix: prod.app1                   # default "rocoto"
      tags: false                         # true = Graphite 1.1 tags instead of path segments
    statsd:
      enabled: true
      host: 127.0.0.1                     # default
      port: 8125                          # default
      prefix: prod.app1                   # default "rocoto"
      tags: false                         # true = DogStatsD tags (Datadog agent, Telegraf statsd)
      maxPacketBytes: 1432                # datagram size limit
    influx:
      enabled: true
      url: https://influx.example.com:8086
      version: 2                          # 2 (InfluxDB 2.x, 3.x, Cloud) or 1
      org: ops                            # version 2
      bucket: jvm                         # version 2
      token: "${env:INFLUX_TOKEN}"        # version 2
      # database: jvm                     # version 1
      # username / password               # version 1, optional
      precision: s                        # ns (default), us, ms, s
```

Settings every exporter understands: `enabled`, `intervalSeconds` (default 60, StatsD 10; minimum
1) and `roles`.

| Exporter | Transport | Notes |
|----------|-----------|-------|
| `otlp` | HTTP POST, OTLP/JSON | `endpoint` must be `https` unless `allowInsecure: true`. Counters become monotonic cumulative sums. |
| `graphite` | TCP, one connection per push | Plaintext protocol, optionally TLS (`tls: true`, trust from `tls.outbound`). |
| `statsd` | UDP datagrams | Lines are packed into datagrams of at most `maxPacketBytes`. Counters are sent as the increment since the previous push (`\|c`); the first push only establishes the baseline, a smaller value is treated as a counter reset. |
| `influx` | HTTP POST to the write API | Version 2: `/api/v2/write?org=&bucket=&precision=` with `Authorization: Token`. Version 1: `/write?db=&precision=` with optional Basic credentials. `url` must be `https` unless `allowInsecure: true`. |

- Exporters have no HTTP request to take an identity from. Each runs as the internal identity
  `<exporter>-exporter` (`otlp-exporter`, `graphite-exporter`, ...; source `internal`) with the
  roles from its `roles` setting (default `rocoto-reader`) and reads every value through the
  pipeline. Deny rules and masking apply like to anybody else. Exporters have no MBean scope;
  restrict them with their own role and policy rules if needed.
- HTTPS and TLS connections use the `tls.outbound` settings (trust store, revocation checking,
  optional `requireStapling`); see [13 - TLS and OCSP](13-tls-and-ocsp.md). The TLS context is
  only built when an exporter needs it.
- Graphite plaintext and StatsD have no authentication and, except Graphite with `tls: true`, no
  encryption. Send them to a local agent or over a trusted network only.
- The `rocoto_tls_*` metrics of the TLS watchdog (below) are pushed along.
- Failures (target unreachable, HTTP status 300 or higher) are logged once as a warning, repeats
  at `FINE`, and a recovery is logged again. Nothing is buffered; the next interval sends current
  values.
- Exporters only exist when `metrics.enabled` is `true`. An invalid exporter configuration aborts
  start-up like every other configuration error.
- The former `metrics.otlp` section still works when `metrics.push.otlp` is absent.

## TLS watchdog metrics

When the TLS self probe is configured, `/metrics` additionally contains the state of the server's
own HTTPS listener, as seen by a real handshake:

```yaml
tls:
  selfProbe:
    url: https://localhost:8443
```

| Metric | Meaning |
|--------|---------|
| `rocoto_tls_cert_expiry_seconds` | seconds until the earliest expiry among the certificates of the presented chain (negative when expired) |
| `rocoto_tls_ocsp_stapled{status="..."}` | `1` if the listener staples an OCSP response, else `0`; `status` is the certificate status from the stapled response (`good`, `revoked`, ...), `unknown`, or `none` when nothing is stapled |
| `rocoto_tls_ocsp_next_update_seconds` | seconds until the stapled OCSP response must be refreshed (only if stapled) |

The metrics appear after the first successful probe (about five seconds after start-up, then
every `intervalSeconds`, minimum 30). They are not read from MBeans and therefore do not depend
on the scraper's policy. Push exporters send them along. The same findings show up as warnings
in `GET /api/v1/health`.

Example alert rules:

```yaml
- alert: RocotoCertificateExpiresSoon
  expr: rocoto_tls_cert_expiry_seconds < 14 * 86400
- alert: RocotoOcspStaplingLost
  expr: rocoto_tls_ocsp_stapled == 0
```

Next: [07 - Streaming](07-streaming.md)

# Push metrics to Graphite, StatsD and InfluxDB

**Goal:** send the JVM and server metrics to a time series database that does not scrape
Prometheus endpoints - Graphite/Carbon, a StatsD agent, or InfluxDB - or let Telegraf pull them in
InfluxDB line protocol.

**Prerequisites:** Rocoto deployed with metrics enabled (the default). The push exporters are
described in [06 - Metrics](../guide/06-metrics.md#push-exporters), all keys in the
[configuration reference](../reference/configuration.md).

Every exporter reads the values through Rocoto's pipeline as its own internal identity
(`graphite-exporter`, `statsd-exporter`, `influx-exporter`) with the roles you give it, so policy
rules and masking apply exactly as for a scraper.

## Graphite (Carbon plaintext, TCP 2003)

```yaml
# rocoto.yaml
metrics:
  push:
    graphite:
      enabled: true
      host: carbon.example.com
      port: 2003
      prefix: prod.shop.app1        # Graphite path prefix
      intervalSeconds: 60
```

What Carbon receives (captured with a local TCP listener):

```text
prod.shop.app1.jvm_memory_heap_used_bytes 27928416 1791366981
prod.shop.app1.jvm_memory_heap_committed_bytes 270532608 1791366981
prod.shop.app1.jvm_gc_collections_total.G1_Young_Generation 42 1791366981
```

Label values become path segments. For Graphite 1.1 and later, tags keep them queryable with
`seriesByTag()`:

```yaml
    graphite:
      enabled: true
      host: carbon.example.com
      tags: true                    # jvm_gc_collections_total;gc=G1_Young_Generation 42 1791366981
```

Carbon plaintext has no authentication. Use `tls: true` if your relay terminates TLS
(the trust store comes from `tls.outbound`), otherwise keep the traffic on a trusted network.

## StatsD (UDP 8125)

```yaml
metrics:
  push:
    statsd:
      enabled: true
      host: 127.0.0.1               # usually the local agent
      port: 8125
      prefix: shop.app1
      tags: true                    # DogStatsD tags, for the Datadog agent or Telegraf's statsd input
      intervalSeconds: 10
```

What the agent receives:

```text
shop.app1.jvm_memory_heap_used_bytes:27928416|g
shop.app1.jvm_memory_pool_used_bytes:1393408|g|#pool:CodeHeap_'non-nmethods'
shop.app1.jvm_classes_loaded_total:54|c
```

- Gauges are sent as gauges (`|g`). StatsD counters are increments, so Rocoto sends counters as the
  difference to the previous push (`|c`). The first push after start-up only records the
  baseline; a counter that went down (server restart) is sent with its new value.
- Without `tags: true` the label values become path segments, as for Graphite.
- Lines are packed into datagrams of at most `maxPacketBytes` (default 1432 bytes).

## InfluxDB 2.x / 3.x / Cloud

Create a token with write access to the bucket and pass it through the environment:

```yaml
metrics:
  push:
    influx:
      enabled: true
      url: https://influx.example.com:8086
      org: ops
      bucket: jvm
      token: "${env:INFLUX_TOKEN}"
      precision: s
      intervalSeconds: 60
```

Rocoto posts the line protocol to `/api/v2/write?org=ops&bucket=jvm&precision=s` with
`Authorization: Token ...`:

```text
jvm_memory_heap_used_bytes value=65677152.0 1791366991
jvm_gc_collections_total,gc=G1\ Young\ Generation value=42.0 1791366991
```

The metric name is the measurement, labels are tags, the sample is the field `value`.

### InfluxDB 1.x

```yaml
    influx:
      enabled: true
      version: 1
      url: https://influx1.example.com:8086
      database: jvm
      username: rocoto
      password: "${env:INFLUX_PASSWORD}"
```

`url` must be `https`; for a local test with plain HTTP set `allowInsecure: true`.

## Telegraf pulls instead

If Telegraf runs next to the server anyway, it can pull the line protocol from Rocoto - no push
configuration in Rocoto needed:

```toml
# telegraf.conf
[[inputs.http]]
  urls = ["https://app1.example.com:8443/rocoto/metrics?format=influx&precision=s"]
  data_format = "influx"
  headers = { Authorization = "Bearer ${ROCOTO_TOKEN}" }
  tls_ca = "/etc/telegraf/ca.pem"
  interval = "60s"
```

Create the token with a scope, as for Prometheus:

```bash
java -jar rocoto-tools.jar token create telegraf --roles rocoto-reader --days 365 \
  --mbeans "java.lang:*;java.nio:*;Catalina:*" --file rocoto-users.yaml
```

The same endpoint serves `?format=graphite`, `?format=statsd` and `?format=otlp`, which is handy
to see exactly what an exporter would send before enabling it.

## Check that it works

- The server log shows one line per exporter at start-up:
  `Rocoto pushes metrics to graphite at carbon.example.com:2003 every 60s`.
- A failing target is logged once as a warning (`Metrics push to influx (...) failed: ...`),
  repeats only at level `FINE`, and `... works again` when it recovers.
- An exporter that receives fewer series than expected usually lacks read permission: its
  identity is `<name>-exporter` with the roles from its `roles` setting (default
  `rocoto-reader`); denials are in the audit log.

## Verified

The Graphite and StatsD outputs above were captured from a running standalone Rocoto (Undertow)
pushing to local TCP and UDP listeners; the InfluxDB v2 request was verified against a local HTTP
receiver in the unit tests. The InfluxDB, Telegraf and Carbon servers themselves were not part of
the test.

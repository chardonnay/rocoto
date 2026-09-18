# Custom metrics profile

## Goal

Turn MBean attributes of your own application (or of anything else in the JVM) into Prometheus /
OTLP metrics with a few lines of YAML: counters and gauges, values inside composite data, labels
from ObjectName keys, unit conversion.

## Prerequisites

A running Rocoto with the [demo application](../../examples/demo-app/README.md), for example
[tomcat-builtin-auth](tomcat-builtin-auth.md).

## Files

Rules can be written inline (`metrics.rules`, as the tomcat-builtin example does) or in a profile
file that several servers share. A profile file has one top-level key, `rules`.

`conf/profiles/orders.yaml`:

```yaml
# Custom metrics profile for the demo application. Same format as metrics.rules in rocoto.yaml.
rules:
  - mbean: "com.example.demo:type=Orders"
    attribute: OrdersProcessed
    name: demo_orders_processed_total
    type: counter                       # counter names must end with _total
    help: "Orders processed by the demo application"
  - mbean: "com.example.demo:type=Orders"
    attribute: QueueDepth
    name: demo_orders_queue_depth       # type defaults to gauge
    help: "Orders waiting in the demo application's queue"
  - mbean: "com.example.demo:type=Orders"
    attribute: LastOrder
    path: amountCents                   # one key of the composite value
    scale: 0.01                         # cents -> currency units
    name: demo_last_order_amount
    help: "Amount of the most recently processed order"
  # Composite value without "path": every numeric key becomes a sample with a "key" label
  - mbean: "java.lang:type=Memory"
    attribute: NonHeapMemoryUsage
    name: demo_nonheap_bytes
    help: "Non-heap memory, one sample per key"
  # Pattern + labels taken from ObjectName key properties
  - mbean: "java.lang:type=MemoryPool,name=*"
    attribute: CollectionUsageThresholdCount
    name: demo_pool_threshold_exceeded_total
    type: counter
    help: "How often a pool exceeded its collection usage threshold"
    labels: { pool: "${name}", source: "jmx" }
```

`conf/rocoto.yaml`:

```yaml
metrics:
  # bundled: jvm | tomcat | wildfly | payara; auto = profile of the detected server;
  # anything else is a file, relative to the directory of rocoto.yaml
  profiles: [jvm, auto, profiles/orders.yaml]
```

| Key | Meaning |
|-----|---------|
| `mbean` | ObjectName or pattern (required) |
| `attribute` | attribute to read (required) |
| `path` | key inside a composite value; without it every numeric key becomes a sample with a `key` label |
| `name` | metric name (required), `[a-zA-Z_:][a-zA-Z0-9_:]*` |
| `type` | `gauge` (default) or `counter`; counter names must end with `_total` |
| `help` | help text, defaults to the name |
| `labels` | label name -> template; `${prop}` is replaced by the ObjectName key property `prop` |
| `scale` | multiplier, e.g. `0.001` for milliseconds to seconds |

Numbers and booleans (`true` = 1) become samples; other values are skipped.

## Commands

```sh
# restart or redeploy rocoto.war after changing rocoto.yaml or a profile, then:
curl -s -H "Authorization: Bearer $TOKEN" https://server:8443/rocoto/metrics | grep '^demo_'
```

## Expected output

Captured from an embedded Tomcat with exactly the two files above:

```text
demo_orders_processed_total 12
demo_orders_queue_depth 1
demo_last_order_amount 101.42
demo_nonheap_bytes{key="committed"} 36831232
demo_nonheap_bytes{key="init"} 7667712
demo_nonheap_bytes{key="max"} -1
demo_nonheap_bytes{key="used"} 34007128
demo_pool_threshold_exceeded_total{pool="G1 Eden Space",source="jmx"} 0
demo_pool_threshold_exceeded_total{pool="G1 Old Gen",source="jmx"} 0
demo_pool_threshold_exceeded_total{pool="G1 Survivor Space",source="jmx"} 0
```

## Notes

- Metrics obey the policy of the scraping identity: a token scoped to `java.lang:*` does not get
  `demo_*` samples, and masked attributes are never exported.
- Mistakes fail the start-up (Rocoto then answers `503`), with messages such as
  `/path/to/conf/profiles/orders.yaml.rules[0]: counter names must end with _total (demo_orders_processed)`.
- The bundled profiles are good templates:
  [`rocoto-metrics/src/main/resources/io/rocoto/metrics/profiles/`](../../rocoto-metrics/src/main/resources/io/rocoto/metrics/profiles).
  The Tomcat profile uses a domain wildcard (`*:type=ThreadPool,name=*`) because embedded Tomcat
  registers its MBeans under `Tomcat` instead of `Catalina`.
- A custom [server adapter](custom-server-adapter.md) has no bundled profile; `auto` then adds
  nothing and you list your profile file explicitly.

## What to look at next

- [prometheus-grafana](prometheus-grafana.md), [otlp-collector](otlp-collector.md)
- [expose-your-own-mxbean](expose-your-own-mxbean.md)

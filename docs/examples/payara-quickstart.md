# Payara quickstart (experimental)

> **Payara / GlassFish support is experimental.** The steps on this page are covered by
> `ci/payara/smoke-test.sh`, which passed on Payara Server 6.2023.10, 6.2025.4, 6.2025.11 and 7.2026.9 (JDK 17/21/25) and Eclipse GlassFish 7.0.15, 7.0.25, 7.1.0 and 8.0.4. GlassFish needs different commands and
> GlassFish 7 has a URL restriction - see
> [GlassFish differences](../guide/02-installation.md#glassfish-differences). Plain MBean access, policy, metrics
> of the JVM profile and streaming do not depend on the server. The
> normalized resource view and the `payara` metrics profile rely on AMX monitoring MBeans, which
> only exist after AMX has been started and the monitoring level has been raised.

## Goal

Deploy `rocoto.war` on Payara 6 with built-in authentication and get JVM metrics plus - with AMX -
connection pool and thread pool data.

## Prerequisites

- Payara Server 6 (Jakarta EE 10), JDK 17+
- `rocoto.war`, `rocoto-tools.jar` (`mvn install -DskipTests` in the repository root)
- HTTPS listener (`http-listener-2`, port 8181 in the default domain); credentials require TLS

## Files

`<domain>/config/rocoto.yaml` (`${com.sun.aas.instanceRoot}/config/rocoto.yaml`, e.g.
`glassfish/domains/domain1/config/`):

```yaml
server:
  adapter: auto                # "payara" is detected through com.sun.aas.instanceRoot

auth:
  mode: builtin
  builtin:
    usersFile: rocoto-users.yaml

metrics:
  profiles: [jvm, auto]        # auto = payara (amx:* monitoring MBeans)
```

`rocoto.war` contains `WEB-INF/glassfish-web.xml` with the context root `/rocoto` and a
group-to-role mapping for the three standard roles (group name = role name). It only matters for
`auth.mode: container` / `chain`, where users of a Payara realm need the groups `rocoto-reader`,
`rocoto-operator` or `rocoto-admin`.

## Commands

```sh
CONF=$PAYARA_HOME/glassfish/domains/domain1/config

java -jar rocoto-tools.jar user add ada --roles rocoto-admin --file $CONF/rocoto-users.yaml
chmod 600 $CONF/rocoto-users.yaml

asadmin start-domain
asadmin deploy --contextroot rocoto rocoto.war

# AMX and monitoring data. One level per module; with LOW Payara 6 and 7 register no *-mon MBeans.
asadmin set-amx-enabled --enabled=true --dynamic=true
asadmin set-monitoring-level --module=jdbc-connection-pool,thread-pool,web-container,http-service --level=HIGH,HIGH,HIGH,HIGH
```

Check `amxLoaded` below. The statistics of a JDBC pool appear once the pool has been used
(`asadmin ping-connection-pool H2Pool` is enough).

## Expected output

```sh
curl -s --cacert payara.crt -u ada https://localhost:8181/rocoto/api/v1/server
# {"rocoto":{...,"authMode":"builtin"},
#  "server":{"adapter":"payara","product":"Payara / GlassFish","instanceRoot":"...","amxLoaded":true},
#  "caller":{"name":"ada","roles":["rocoto-admin"],"authenticatedBy":"basic"}}
```

- `amxLoaded: false` - AMX is not started: `GET /api/v1/resources/datasources` returns an empty
  `items` list and no `payara_*` metrics appear. Everything else keeps working.
- `amxLoaded: true` and the monitoring level `HIGH` - rows for JDBC pools, thread pools
  and sessions, plus `payara_jdbc_connections_used`, `payara_threads_busy`,
  `payara_requests_total`, ...

## What to look at next

- [policy-recipes](policy-recipes.md), [prometheus-grafana](prometheus-grafana.md)
- [custom-metrics-profile](custom-metrics-profile.md) - map further `amx:*` attributes yourself

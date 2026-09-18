# Custom server adapter

Rocoto learns about an application server through the `io.rocoto.adapters.ServerAdapter` SPI:
detection, the MBeanServers to expose, product information for `GET /api/v1/server` and the
normalized view behind `GET /api/v1/resources/{datasources|threadpools|deployments|sessions}`.
Adapters are discovered with `java.util.ServiceLoader` through the class loader of the web
application, so an additional server is supported by adding one jar to `WEB-INF/lib`.

This project implements an adapter for a fictional "Example Server" that is detected by the MBean
`com.example.server:type=Runtime`.

| File | Purpose |
|------|---------|
| `src/main/java/com/example/rocoto/adapter/ExampleServerAdapter.java` | the adapter, extends `GenericAdapter` |
| `src/main/resources/META-INF/services/io.rocoto.adapters.ServerAdapter` | the `ServiceLoader` registration |

## Build

```sh
# once, in the Rocoto repository root (Rocoto is not on Maven Central):
mvn install -DskipTests

cd examples/custom-adapter
mvn -q package          # target/rocoto-example-server-adapter.jar
```

## Add the jar to rocoto.war

Work on a copy of the WAR. `jar uf` adds a file under the path given on the command line, so the
jar has to sit in a local `WEB-INF/lib/` directory:

```sh
mkdir -p build/WEB-INF/lib
cp ../../rocoto-war/target/rocoto.war build/rocoto.war
cp target/rocoto-example-server-adapter.jar build/WEB-INF/lib/
(cd build && jar uf rocoto.war WEB-INF/lib/rocoto-example-server-adapter.jar)

# the same with zip instead of the JDK's jar tool:
# (cd build && zip rocoto.war WEB-INF/lib/rocoto-example-server-adapter.jar)

unzip -l build/rocoto.war | grep example-server-adapter
#     5763  ...   WEB-INF/lib/rocoto-example-server-adapter.jar
```

Deploy `build/rocoto.war` as usual. Keep the file name `rocoto.war` on Tomcat, where the file name
determines the context root.

## Expected result

On a server that registers `com.example.server:type=Runtime` the log shows
`Rocoto server adapter: example-server` and:

```sh
curl -s -u rita:demo-password-123 https://server:8443/rocoto/api/v1/server
# {"rocoto":{...},"server":{"adapter":"example-server","product":"Example Server","version":"4.2"},"caller":{...}}

curl -s -u rita:demo-password-123 https://server:8443/rocoto/api/v1/resources/threadpools
# {"kind":"threadpools","server":"example-server","items":[{"name":"http",
#  "mbean":"com.example.server:name=http,type=ThreadPool","currentThreads":8,"busyThreads":2,"maxThreads":32}]}
```

This was verified with an embedded Tomcat whose JVM had two hand-registered
`com.example.server` MBeans: the adapter's priority (300) beats the Tomcat adapter (100).

Notes:

- `server.adapter: example-server` in `rocoto.yaml` forces the adapter without detection;
  `server.adapter: auto` (default) asks all adapters in descending `priority()`.
- `metrics.profiles: [jvm, auto]` - `auto` resolves to a bundled profile named like the adapter
  id. There is none for a custom adapter, so add your own profile file or inline `metrics.rules`
  (see [custom-metrics-profile](../../docs/examples/custom-metrics-profile.md)).
- Always read through the `ResourceReader`. It enforces the caller's policy, masking and audit;
  reading from the `MBeanServer` directly would bypass them.

Cookbook: [docs/examples/custom-server-adapter.md](../../docs/examples/custom-server-adapter.md).

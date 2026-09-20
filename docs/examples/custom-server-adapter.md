# Custom server adapter

## Goal

Teach Rocoto about an application server it does not know: product name and version for
`GET /api/v1/server` and rows for the normalized view `GET /api/v1/resources/{kind}`. This is done
with the `io.rocoto.adapters.ServerAdapter` SPI; the implementation is a jar that you add to
`WEB-INF/lib` of `rocoto.war`. Without an adapter everything else still works - Rocoto falls back
to the generic adapter.

Runnable project: [`examples/custom-adapter`](../../examples/custom-adapter/README.md).

## Prerequisites

- JDK 17+, Maven
- `mvn install -DskipTests` in the repository root: `io.rocoto:rocoto-adapters:0.1.0` is
  not on Maven Central and must be in your local repository

## The SPI

| Method | Purpose |
|--------|---------|
| `String id()` | short identifier; value for `server.adapter`, name of a bundled metrics profile |
| `int priority()` | default 100; higher values are asked first (WildFly 200, Payara 150, Tomcat 100, generic 0) |
| `boolean detect(MBeanServer)` | is this the server we are running in? |
| `List<MBeanServer> mbeanServers(MBeanServer)` | the in-process MBeanServers to expose |
| `Map<String,Object> serverInfo(ResourceReader)` | product and version |
| `List<Map<String,Object>> resources(ResourceKind, ResourceReader)` | rows for `DATASOURCES`, `THREADPOOLS`, `DEPLOYMENTS`, `SESSIONS` |
| `IdentityPropagator captureIdentity()` | default no-op; carries a container security identity to streaming threads |

`ResourceReader` has `search(pattern)`, `read(name, attribute)` and `readFirst(name, attr...)`. It
is backed by the request pipeline: values the caller may not read come back as `null`, masked
values are masked, reads are audited. Extending `GenericAdapter` gives sensible defaults for
everything except detection and the data.

## Files

[`examples/custom-adapter/pom.xml`](../../examples/custom-adapter/pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>com.example</groupId>
  <artifactId>rocoto-example-server-adapter</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>
  <name>Rocoto example: custom server adapter</name>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <rocoto.version>0.1.0</rocoto.version>
  </properties>

  <dependencies>
    <!--
      Not on Maven Central: run `mvn install -DskipTests` in the Rocoto repository root first.
      "provided" because rocoto.war already contains this jar in WEB-INF/lib.
    -->
    <dependency>
      <groupId>io.rocoto</groupId>
      <artifactId>rocoto-adapters</artifactId>
      <version>${rocoto.version}</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>rocoto-example-server-adapter</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
    </plugins>
  </build>
</project>
```

[`examples/custom-adapter/src/main/java/com/example/rocoto/adapter/ExampleServerAdapter.java`](../../examples/custom-adapter/src/main/java/com/example/rocoto/adapter/ExampleServerAdapter.java)

```java
package com.example.rocoto.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.adapters.GenericAdapter;
import io.rocoto.adapters.ResourceKind;
import io.rocoto.adapters.ResourceReader;

/**
 * Adapter for the fictional "Example Server", which announces itself with the MBean
 * {@code com.example.server:type=Runtime} and publishes its resources as
 *
 * <pre>
 * com.example.server:type=Runtime            ProductName, Version
 * com.example.server:type=ThreadPool,name=*  PoolSize, ActiveCount, MaximumPoolSize
 * com.example.server:type=DataSource,name=*  ActiveConnections, IdleConnections, MaxConnections
 * com.example.server:type=Application,name=* ContextPath, State, ActiveSessions
 * </pre>
 *
 * Extending {@link GenericAdapter} inherits {@code mbeanServers()} (platform MBeanServer plus every
 * other MBeanServer of the JVM) and the no-op {@code captureIdentity()}.
 */
public class ExampleServerAdapter extends GenericAdapter {

    private static final String DOMAIN = "com.example.server";

    /** Also the value for {@code server.adapter} in rocoto.yaml to force this adapter. */
    @Override
    public String id() {
        return "example-server";
    }

    /** Asked before the bundled adapters (WildFly 200, Tomcat 100, generic 0). */
    @Override
    public int priority() {
        return 300;
    }

    /** Must be cheap, side-effect free and never throw. Runs once while Rocoto starts. */
    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            return platformServer.isRegistered(new ObjectName(DOMAIN + ":type=Runtime"));
        } catch (MalformedObjectNameException e) {
            return false;
        }
    }

    /**
     * All reads go through the {@link ResourceReader}: it is backed by Rocoto's request pipeline,
     * so policy, masking and audit apply. A value the caller may not read comes back as null.
     */
    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "Example Server");
        for (ObjectName runtime : reader.search(DOMAIN + ":type=Runtime")) {
            put(info, "product", reader.read(runtime, "ProductName"));
            put(info, "version", reader.read(runtime, "Version"));
        }
        return info;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case THREADPOOLS -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=ThreadPool,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "currentThreads", reader.read(n, "PoolSize"));
                    put(row, "busyThreads", reader.read(n, "ActiveCount"));
                    put(row, "maxThreads", reader.read(n, "MaximumPoolSize"));
                    rows.add(row);
                }
            }
            case DATASOURCES -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=DataSource,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "activeConnections", reader.read(n, "ActiveConnections"));
                    put(row, "idleConnections", reader.read(n, "IdleConnections"));
                    // attribute names differ between server versions? Ask for several spellings.
                    put(row, "maxConnections", reader.readFirst(n, "MaxConnections", "MaxPoolSize"));
                    rows.add(row);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=Application,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "contextPath", reader.read(n, "ContextPath"));
                    put(row, "state", reader.read(n, "State"));
                    rows.add(row);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=Application,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "activeSessions", reader.read(n, "ActiveSessions"));
                    rows.add(row);
                }
            }
            default -> {
                // a resource kind added by a newer Rocoto version: nothing to report
            }
        }
        return rows;
    }

    /** Same row layout as the bundled adapters: "name" and the source "mbean" first. */
    private static Map<String, Object> row(ObjectName source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", source.getKeyProperty("name"));
        row.put("mbean", source.getCanonicalName());
        return row;
    }

    /** Leaves out what is missing or denied instead of reporting null. */
    private static void put(Map<String, Object> row, String key, Object value) {
        if (value != null) {
            row.put(key, value);
        }
    }
}
```

The `ServiceLoader` registration:

[`examples/custom-adapter/src/main/resources/META-INF/services/io.rocoto.adapters.ServerAdapter`](../../examples/custom-adapter/src/main/resources/META-INF/services/io.rocoto.adapters.ServerAdapter)

```text
com.example.rocoto.adapter.ExampleServerAdapter
```

## Commands

```sh
cd examples/custom-adapter
mvn -q package

# add the jar to a copy of the WAR ("jar uf" stores the path given on the command line)
mkdir -p build/WEB-INF/lib
cp ../../rocoto-war/target/rocoto.war build/rocoto.war
cp target/rocoto-example-server-adapter.jar build/WEB-INF/lib/
(cd build && jar uf rocoto.war WEB-INF/lib/rocoto-example-server-adapter.jar)
# or: (cd build && zip rocoto.war WEB-INF/lib/rocoto-example-server-adapter.jar)

unzip -l build/rocoto.war | grep example-server-adapter
```

Deploy `build/rocoto.war`.

## Expected output

```text
     5763  09-18-2026 22:02   WEB-INF/lib/rocoto-example-server-adapter.jar
```

Server log: `Rocoto server adapter: example-server`. Then:

```sh
curl -s -u rita:demo-password-123 $B/api/v1/server
# {"rocoto":{...},"server":{"adapter":"example-server","product":"Example Server","version":"4.2"},"caller":{...}}

curl -s -u rita:demo-password-123 $B/api/v1/resources/threadpools
# {"kind":"threadpools","server":"example-server","items":[{"name":"http",
#  "mbean":"com.example.server:name=http,type=ThreadPool","currentThreads":8,"busyThreads":2,"maxThreads":32}]}
```

This was verified: the repackaged WAR ran in an embedded Tomcat whose JVM had
`com.example.server:type=Runtime` and `com.example.server:type=ThreadPool,name=http` registered by
hand. Because of its priority (300) the example adapter won over the Tomcat adapter.

## Notes

- Use the row keys of the bundled adapters so that clients can treat all servers alike:
  `name`, `mbean`, then e.g. `currentThreads`, `busyThreads`, `maxThreads` (thread pools),
  `activeConnections`, `idleConnections`, `maxConnections` (data sources), `contextPath`, `state`
  (deployments), `activeSessions` (sessions). Leave out what is unknown instead of sending `null`.
- `detect()` runs once at start-up on the platform MBeanServer. Keep it cheap and never throw.
- `server.adapter: example-server` forces the adapter; an unknown id aborts the start-up.
- `metrics.profiles: [jvm, auto]`: `auto` looks for a bundled profile named like the adapter id.
  For a custom adapter there is none - add a profile file, see
  [custom-metrics-profile](custom-metrics-profile.md).
- Never read from the `MBeanServer` directly in `serverInfo()`/`resources()`; that would bypass
  policy, masking and audit.
- The SPI is part of a `0.x` release and may still change.

## What to look at next

- The bundled adapters as templates:
  [`TomcatAdapter`](../../rocoto-adapters/src/main/java/io/rocoto/adapters/TomcatAdapter.java),
  [`WildFlyAdapter`](../../rocoto-adapters/src/main/java/io/rocoto/adapters/WildFlyAdapter.java)
- User guide: [The JMX connection](../guide/04-jmx-connection.md)

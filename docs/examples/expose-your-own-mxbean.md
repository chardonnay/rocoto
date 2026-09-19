# Expose your own MXBean

## Goal

Register an MXBean in your application and use it through Rocoto end to end: read (including a
composite value), write, execute operations, see a secret being masked, export metrics and stream
an attribute. Rocoto needs no plugin or annotation for this - every MBean in the JVM's
MBeanServers is visible, subject to the policy.

Runnable projects: [`examples/demo-app`](../../examples/demo-app/README.md) (the application) and
[`examples/tomcat-builtin`](../../examples/tomcat-builtin/README.md) (server and configuration).

## Prerequisites

- JDK 17+, Maven; Docker with the compose plugin for the server
- `mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central)

## Files

The management interface. The suffix `MXBean` makes the JMX runtime map Java types to open types,
so the `record` below travels as `CompositeData`:

[`examples/demo-app/src/main/java/com/example/demo/OrdersMXBean.java`](../../examples/demo-app/src/main/java/com/example/demo/OrdersMXBean.java)

```java
package com.example.demo;

/**
 * Management interface of the demo application. The {@code MXBean} suffix makes the JMX runtime
 * map {@link OrderInfo} (a record) to {@code CompositeData}, so any JMX client - including
 * Rocoto - can read it without having the class.
 */
public interface OrdersMXBean {

    /** Monotonic counter - a good candidate for a Prometheus counter. */
    long getOrdersProcessed();

    /** Orders waiting to be processed - a gauge. */
    int getQueueDepth();

    /** The most recently processed order, or {@code null} before the first one. */
    OrderInfo getLastOrder();

    /** Only here to demonstrate masking: Rocoto replaces the value by {@code ********}. */
    String getDbPassword();

    boolean isMaintenanceMode();

    /** While enabled the background worker pauses: new orders keep arriving and the queue grows. */
    void setMaintenanceMode(boolean maintenanceMode);

    void resetCounters();

    /**
     * Processes up to {@code max} queued orders immediately.
     *
     * @return the number of orders that were processed
     */
    int drainQueue(int max);
}
```

[`examples/demo-app/src/main/java/com/example/demo/OrderInfo.java`](../../examples/demo-app/src/main/java/com/example/demo/OrderInfo.java)

```java
package com.example.demo;

/** Mapped to a JMX {@code CompositeData} with the items {@code id}, {@code customer}, {@code amountCents} and {@code processedAt}. */
public record OrderInfo(String id, String customer, long amountCents, String processedAt) {
}
```

[`examples/demo-app/src/main/java/com/example/demo/Orders.java`](../../examples/demo-app/src/main/java/com/example/demo/Orders.java)

```java
package com.example.demo;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** The "business logic": a queue of orders and a few counters. Thread-safe. */
public final class Orders implements OrdersMXBean {

    private record Pending(String id, String customer, long amountCents) {
    }

    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private volatile OrderInfo lastOrder;
    private volatile boolean maintenanceMode;

    /** Orders are always accepted; in maintenance mode they just wait in the queue. */
    public String submit(String customer, long amountCents) {
        String id = "order-" + sequence.incrementAndGet();
        queue.add(new Pending(id, customer, amountCents));
        return id;
    }

    /** Called by the background worker. */
    void processOne() {
        if (!maintenanceMode) {
            process(1);
        }
    }

    private int process(int max) {
        int done = 0;
        Pending next;
        while (done < max && (next = queue.poll()) != null) {
            lastOrder = new OrderInfo(next.id(), next.customer(), next.amountCents(), Instant.now().toString());
            processed.incrementAndGet();
            done++;
        }
        return done;
    }

    // ---- OrdersMXBean --------------------------------------------------------------------

    @Override
    public long getOrdersProcessed() {
        return processed.get();
    }

    @Override
    public int getQueueDepth() {
        return queue.size();
    }

    @Override
    public OrderInfo getLastOrder() {
        return lastOrder;
    }

    @Override
    public String getDbPassword() {
        return "demo-only-not-a-real-secret";
    }

    @Override
    public boolean isMaintenanceMode() {
        return maintenanceMode;
    }

    @Override
    public void setMaintenanceMode(boolean maintenanceMode) {
        this.maintenanceMode = maintenanceMode;
    }

    @Override
    public void resetCounters() {
        processed.set(0);
        lastOrder = null;
    }

    @Override
    public int drainQueue(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must not be negative");
        }
        return process(max);
    }
}
```

Registration and - just as important - unregistration:

[`examples/demo-app/src/main/java/com/example/demo/OrdersLifecycle.java`](../../examples/demo-app/src/main/java/com/example/demo/OrdersLifecycle.java)

```java
package com.example.demo;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/**
 * Registers the MXBean when the application starts and - just as important - unregisters it on
 * shutdown. Without the unregistration a redeploy fails with InstanceAlreadyExistsException and
 * the old class loader leaks.
 */
@WebListener
public class OrdersLifecycle implements ServletContextListener {

    static final String ORDERS_ATTRIBUTE = Orders.class.getName();
    private static final String OBJECT_NAME = "com.example.demo:type=Orders";

    private ObjectName name;
    private ScheduledExecutorService simulator;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        Orders orders = new Orders();
        try {
            name = new ObjectName(OBJECT_NAME);
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(orders, name);
        } catch (JMException e) {
            throw new IllegalStateException("Cannot register " + OBJECT_NAME, e);
        }
        event.getServletContext().setAttribute(ORDERS_ATTRIBUTE, orders);

        // Simulated traffic so that counters, the queue and metrics move without any client:
        // orders arrive a little faster than they are processed, the queue grows slowly.
        simulator = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "demo-orders-simulator");
            t.setDaemon(true);
            return t;
        });
        simulator.scheduleAtFixedRate(() -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            orders.submit("customer-" + random.nextInt(1, 50), random.nextLong(500, 25_000));
            if (random.nextInt(10) < 8) {
                orders.processOne();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (simulator != null) {
            simulator.shutdownNow();
        }
        if (name != null) {
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
            } catch (JMException e) {
                event.getServletContext().log("Cannot unregister " + name, e);
            }
        }
    }
}
```

[`examples/demo-app/src/main/java/com/example/demo/OrderServlet.java`](../../examples/demo-app/src/main/java/com/example/demo/OrderServlet.java)

```java
package com.example.demo;

import java.io.IOException;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** {@code POST /demo-app/orders?customer=alice&amountCents=1999} queues one order by hand. */
@WebServlet("/orders")
public class OrderServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Orders orders = (Orders) getServletContext().getAttribute(OrdersLifecycle.ORDERS_ATTRIBUTE);
        String customer = request.getParameter("customer") == null ? "anonymous" : request.getParameter("customer");
        long amount;
        try {
            amount = Long.parseLong(request.getParameter("amountCents") == null ? "1000"
                    : request.getParameter("amountCents"));
        } catch (NumberFormatException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "amountCents must be a number");
            return;
        }
        String id = orders.submit(customer, amount);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
        response.getWriter().println(id);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Orders orders = (Orders) getServletContext().getAttribute(OrdersLifecycle.ORDERS_ATTRIBUTE);
        response.setContentType("text/plain");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().println("processed=" + orders.getOrdersProcessed() + " queued=" + orders.getQueueDepth());
    }
}
```

[`examples/demo-app/pom.xml`](../../examples/demo-app/pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <!-- Standalone project: deliberately NOT a module of the Rocoto build. -->
  <groupId>com.example</groupId>
  <artifactId>demo-app</artifactId>
  <version>1.0.0</version>
  <packaging>war</packaging>
  <name>Rocoto example: demo application with its own MXBean</name>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>jakarta.servlet</groupId>
      <artifactId>jakarta.servlet-api</artifactId>
      <version>6.0.0</version>
      <scope>provided</scope>
    </dependency>
  </dependencies>

  <build>
    <finalName>demo-app</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-war-plugin</artifactId>
        <version>3.4.0</version>
        <configuration>
          <failOnMissingWebXml>false</failOnMissingWebXml>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

The Rocoto side - policy rules and metric rules for the MBean:

[`examples/tomcat-builtin/conf/rocoto.yaml`](../../examples/tomcat-builtin/conf/rocoto.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# auth.allowInsecureTransport makes Rocoto accept passwords and tokens over plain HTTP.
# In production leave it at its default (false) and serve Rocoto over HTTPS.
# =====================================================================================
auth:
  mode: builtin
  allowInsecureTransport: true      # LOCAL DEMO ONLY - never use in production
  builtin:
    usersFile: rocoto-users.yaml    # relative to the directory of this file

policy:
  rules:
    # Operators (and admins) may run the two maintenance operations of the demo MBean ...
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "com.example.demo:type=Orders"
      operations: [resetCounters, drainQueue]
    # ... and trigger a garbage collection.
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]
    # Only admins may switch the maintenance mode.
    - roles: [rocoto-admin]
      allow: [write]
      mbean: "com.example.demo:type=Orders"
      attributes: [MaintenanceMode]

audit:
  file: /usr/local/tomcat/logs/rocoto-audit.log

health:
  allowAnonymous: true              # GET /rocoto/api/v1/health/live without credentials

metrics:
  profiles: [jvm, auto]             # auto = the profile of the detected server (tomcat)
  rules:
    - mbean: "com.example.demo:type=Orders"
      attribute: OrdersProcessed
      name: demo_orders_processed_total
      type: counter
      help: "Orders processed by the demo application"
    - mbean: "com.example.demo:type=Orders"
      attribute: QueueDepth
      name: demo_orders_queue_depth
      help: "Orders waiting in the demo application's queue"
    - mbean: "com.example.demo:type=Orders"
      attribute: MaintenanceMode
      name: demo_maintenance_mode
      help: "1 while the demo application is in maintenance mode"

stream:
  minIntervalMillis: 500
```

## Commands

```sh
(cd examples/demo-app && mvn -q package)
(cd examples/tomcat-builtin && make up)

B=http://localhost:8080/rocoto
O=com.example.demo%3Atype%3DOrders
J='Content-Type: application/json'
```

Demo users (password `demo-password-123`, demo only): `rita` reader, `otto` operator, `ada` admin.

## Expected output

**Describe** - attributes, types, operations; `DbPassword` is flagged as masked:

```sh
curl -s -u rita:demo-password-123 $B/api/v1/mbeans/$O
```

```text
{"mbean":"com.example.demo:type=Orders","className":"com.example.demo.Orders",...,
 "attributes":{"OrdersProcessed":{"type":"long","openType":"java.lang.Long",...,"readable":true,"writable":false},
  "QueueDepth":{"type":"int",...},
  "LastOrder":{"type":"javax.management.openmbean.CompositeData","openType":"com.example.demo.OrderInfo",...},
  "DbPassword":{"type":"java.lang.String",...,"writable":false,"masked":true},
  "MaintenanceMode":{"type":"boolean",...,"writable":true}},
 "operations":[{"name":"drainQueue","returnType":"int","impact":"UNKNOWN","parameters":[{"name":"p0","type":"int","description":"p0"}]},
  {"name":"resetCounters","returnType":"void","impact":"UNKNOWN","parameters":[]}],
 "notifications":[]}
```

**Read** - the record arrives as a JSON object, the password never leaves the server:

```sh
curl -s -u rita:demo-password-123 $B/api/v1/mbeans/$O/attributes
# {"mbean":"com.example.demo:type=Orders","values":{"OrdersProcessed":17,"QueueDepth":4,
#  "LastOrder":{"amountCents":14075,"customer":"customer-27","id":"order-17","processedAt":"2026-09-18T19:58:04.045210Z"},
#  "DbPassword":"********","MaintenanceMode":false}}

curl -s -u rita:demo-password-123 $B/api/v1/mbeans/$O/attributes/DbPassword
# {"mbean":"com.example.demo:type=Orders","attribute":"DbPassword","value":"********"}
```

The mask is applied because the attribute name matches the built-in glob `*password*`; it also
holds for admins, the Jolokia endpoint, streaming and metrics. For names that do not match a
built-in glob add your own under `policy.mask`.

**Exec** - `otto` (operator) is covered by the first rule, `rita` is not:

```sh
curl -s -u otto:demo-password-123 -H "$J" -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' $B/api/v1/exec
# {"mbean":"com.example.demo:type=Orders","operation":"drainQueue","value":4}

curl -s -u rita:demo-password-123 -H "$J" -d '{"mbean":"com.example.demo:type=Orders","operation":"resetCounters"}' $B/api/v1/exec
# {"status":403,"error":"Access denied by policy"}

curl -s -u otto:demo-password-123 -H "$J" -d '{"arguments":["x"]}' $B/api/v1/mbeans/$O/operations/drainQueue
# {"status":400,"error":"Value out of range or not integral for int"}
```

**Write** - only `ada` (admin); the answer contains the previous value:

```sh
curl -s -u otto:demo-password-123 -X PUT -H "$J" -d '{"value":true}' $B/api/v1/mbeans/$O/attributes/MaintenanceMode
# {"status":403,"error":"Access denied by policy"}
curl -s -u ada:demo-password-123 -X PUT -H "$J" -d '{"value":true}' $B/api/v1/mbeans/$O/attributes/MaintenanceMode
# {"mbean":"com.example.demo:type=Orders","attribute":"MaintenanceMode","previousValue":false}
```

**Metrics** - from the three `metrics.rules`:

```sh
curl -s -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" $B/metrics | grep '^demo_'
# demo_orders_processed_total 10
# demo_orders_queue_depth 7
# demo_maintenance_mode 1
```

**Streaming** - while the maintenance mode is on, the worker pauses and the queue grows by one
order per second (`onChange` is on by default, so only changes are sent):

```sh
curl -N -u rita:demo-password-123 "$B/api/v1/subscribe?mbean=com.example.demo:type=Orders&attribute=QueueDepth&interval=1000"
# event: value
# data: {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:24:47.341331Z","values":{"QueueDepth":3}}
#
# event: value
# data: {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:24:48.347904Z","values":{"QueueDepth":4}}
#
# event: value
# data: {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:24:49.358530Z","values":{"QueueDepth":5}}

# only while more than 100 orders are waiting:
curl -N -u rita:demo-password-123 "$B/api/v1/subscribe?mbean=com.example.demo:type=Orders&attribute=QueueDepth&above=100"
```

Switch the maintenance mode off again (`{"value":false}`) and drain the queue with `drainQueue`.

**Audit** - every write and exec, allowed or denied:

```json
{"ts":"2026-09-18T19:58:06.143682Z","user":"otto","auth":"basic","remote":"...","channel":"api","action":"EXEC","mbean":"com.example.demo:type=Orders","member":"drainQueue","outcome":"ALLOWED"}
{"ts":"2026-09-18T19:58:06.163274Z","user":"otto","auth":"basic","remote":"...","channel":"api","action":"WRITE","mbean":"com.example.demo:type=Orders","member":"MaintenanceMode","outcome":"DENIED","reason":"write/exec requires an explicit allow rule","args":true}
```

All outputs on this page were captured from `rocoto.war` and `demo-app.war` running in an embedded
Tomcat 10.1 with the configuration shown above; the Docker Compose project (`examples/tomcat-builtin`) passes `make test` with the same files.

## Design hints for your own MXBeans

- Prefer MXBeans (interface name ending in `MXBean`) over standard MBeans: records, enums, lists
  and maps are mapped to open types and need no classes on the client side.
- Counters should only go up (`..._total` in Prometheus); offer a separate gauge for current values.
- Do not expose secrets at all. If you must, name the attribute so that a mask matches
  (`...Password`, `...Secret`, `...Token` is *not* built in - add `"*token*"` to `policy.mask`).
- Keep operations idempotent and cheap; give them narrow parameters. Every operation needs an
  explicit allow rule before anybody can call it through Rocoto.
- Always unregister in `contextDestroyed()`; otherwise redeployments fail with
  `InstanceAlreadyExistsException` and leak the class loader.
- If several instances of the application run in one JVM, put the context path into the
  ObjectName (`com.example.demo:type=Orders,context=/shop`).

## What to look at next

- [policy-recipes](policy-recipes.md), [custom-metrics-profile](custom-metrics-profile.md)
- [streaming-clients](streaming-clients.md), [prometheus-grafana](prometheus-grafana.md)

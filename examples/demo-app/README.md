# Demo application with its own MXBean

A tiny Jakarta EE 10 web application (servlet API only) that registers the MXBean
`com.example.demo:type=Orders` from a `ServletContextListener` and unregisters it on shutdown.
The other examples deploy it next to `rocoto.war` to have something application-specific to read,
write, execute, mask, stream and export as metrics.

| Member | Kind | Notes |
|--------|------|-------|
| `OrdersProcessed` | attribute, `long` | counter |
| `QueueDepth` | attribute, `int` | gauge |
| `LastOrder` | attribute, composite | a Java `record` mapped to `CompositeData` by the MXBean rules |
| `DbPassword` | attribute, `String` | matches Rocoto's built-in mask `*password*`, always returned as `********` |
| `MaintenanceMode` | attribute, `boolean`, writable | pauses the processing while `true`; orders keep arriving, the queue grows |
| `resetCounters()` | operation | |
| `drainQueue(int max)` | operation | returns the number of processed orders |

A background thread queues one simulated order per second and processes most of them, so the
values move without any client. `POST /demo-app/orders?customer=alice&amountCents=1999` queues an
order by hand.

## Build

```sh
mvn -q package        # JDK 17+, produces target/demo-app.war
```

The project is standalone: it is not a module of the Rocoto build and has no dependency on Rocoto.

## Deploy

Copy `target/demo-app.war` next to `rocoto.war` (`webapps/` on Tomcat,
`standalone/deployments/` on WildFly). [tomcat-builtin](../tomcat-builtin/README.md) does this with
Docker Compose.

## What to look at next

Cookbook page [Expose your own MXBean](../../docs/examples/expose-your-own-mxbean.md).

# 02 - Installation

This chapter takes you from the source tree to the first authenticated request: build the WAR,
deploy it, create a user and an API token, call the API over HTTPS.

## Requirements

| Component | Requirement |
|-----------|-------------|
| Build | JDK 17 or newer, Maven |
| Runtime | A Jakarta EE 10 web container (Servlet 6.0, optionally WebSocket 2.1) on Java 17+ |
| WildFly / JBoss EAP | WildFly 27 or newer, JBoss EAP 8 |
| Tomcat / TomEE | Tomcat 10.1 or newer (verified: 10.1.0, 10.1.20, 10.1.57, 10.1.60, 11.0), TomEE 10. Tomcat 10.0.27 passes the same tests, but 10.0 is end of life upstream - best effort |
| Jetty | 12.0 or 12.1 with the EE10 environment, see [below](#jetty-12) |
| Open Liberty / WebSphere Liberty | with the Jakarta EE 10 features (`servlet-6.0`), see [below](#open-liberty) |
| Undertow (core), Netty | no servlet container needed, see [below](#undertow-and-netty-embedded-runtime) |
| Payara | 6 (best effort, see [below](#payara-6-experimental)) |
| Transport | An HTTPS listener. Credentials sent over plain HTTP are rejected by default. |

Rocoto uses the `jakarta.*` namespace. It does not run on Java EE 8 / `javax.servlet` servers
(WildFly 26 and older, JBoss EAP 7, Tomcat 9, Payara 5).

> **Test coverage.** The automated integration tests in this repository (`rocoto-it`) deploy the
> real WAR into an embedded Tomcat. In addition, the CI job "Smoke test on a real WildFly
> (Elytron)" (`ci/wildfly/smoke-test.sh`) deploys the WAR into the official WildFly container
> image (it ran on WildFly 41.0.1.Final) and verifies adapter detection, `auth.mode: chain` with
> an Elytron user, a built-in user and an API token, the normalized resources, the policy, the
> `jvm_*` and `wildfly_*` metrics, the Jolokia endpoint and SSE streaming. The Elytron CLI scripts
> under [`scripts/wildfly/`](scripts/wildfly/) (custom realm, mTLS, OIDC, OCSP) are executed against a
> real WildFly (plus an `openssl ocsp` responder and Keycloak) by `ci/wildfly/cli-scripts-test.sh` -
> they remain recipes: review and adapt them before use. Payara support is experimental;
> `ci/payara/smoke-test.sh` covers Payara 6 / 7 and GlassFish 7 / 8 (built-in and file realm authentication,
> AMX resources, `payara_*` metrics).

## Build

```bash
mvn verify
```

Artifacts:

| File | Purpose |
|------|---------|
| `rocoto-war/target/rocoto.war` | The web application to deploy |
| `rocoto-tools/target/rocoto-tools.jar` | Command line tool, runnable with `java -jar` |
| `rocoto-undertow/target/rocoto-undertow-<version>-all.jar`, `rocoto-netty/target/rocoto-netty-<version>-all.jar` | Standalone server and Java agent for JVMs without a servlet container, see [Undertow and Netty](#undertow-and-netty-embedded-runtime) |

**Compiled downloads.** Every release tag `v<version>` publishes `rocoto-<version>.war`,
`rocoto-tools-<version>.jar` and `SHA256SUMS` on the GitHub releases page
(`.github/workflows/release.yml`). Save the WAR as `rocoto.war` before deploying it to Tomcat.

**CPU architectures.** Both artifacts are plain Java bytecode without native libraries: the same
file runs on arm64 (Apple Silicon, AWS Graviton, ...) and amd64 (Intel/AMD) - there are no
per-architecture builds. `ci/arch/arch-test.sh` checks the artifacts for native code and runs the
one WAR in `linux/arm64` and `linux/amd64` Tomcat containers; CI additionally builds and tests
natively on Linux amd64, Linux arm64 and Apple Silicon. When you build container
images (see [`examples/kubernetes`](../../examples/kubernetes/README.md)), the architecture comes
from the base image: `docker buildx build --platform linux/amd64,linux/arm64 ...`.

The context root is `/rocoto` everywhere: `jboss-web.xml` and `glassfish-web.xml` set it
explicitly, and on Tomcat it follows from the file name `rocoto.war` (do not rename the file).

## Where the configuration lives

Rocoto runs without any configuration file, with secure defaults. To change something, create
`rocoto.yaml` in the server's configuration directory:

| Server | Location |
|--------|----------|
| WildFly / JBoss EAP | `$JBOSS_HOME/standalone/configuration/rocoto.yaml` |
| Tomcat | `$CATALINA_BASE/conf/rocoto.yaml` |
| Jetty | `$JETTY_BASE/etc/rocoto.yaml` |
| Open Liberty | `${server.config.dir}/rocoto.yaml`, next to `server.xml` (`/config` in the container images) |
| Payara | `<domain-dir>/config/rocoto.yaml`, for example `glassfish/domains/domain1/config/` |
| any | the file named by the system property `rocoto.config` or the environment variable `ROCOTO_CONFIG` |

The user store `rocoto-users.yaml` is looked up in the same directory as `rocoto.yaml` (unless
`auth.builtin.usersFile` is an absolute path). All keys are described in the
[configuration reference](../reference/configuration.md).

A `rocoto.yaml` is not needed for that: if **no** `rocoto.yaml` exists, relative paths such as the
default `rocoto-users.yaml` are still resolved against the server's configuration directory
(`${jboss.server.config.dir}`, `${catalina.base}/conf`, `${com.sun.aas.instanceRoot}/config`, `${jetty.base}/etc`, `${server.config.dir}`) -
never against the working directory of the server process. Only on a server that sets none of
these system properties (the `generic` case) does a relative path fall back to the working
directory; use an absolute `auth.builtin.usersFile` there.

## WildFly / JBoss EAP

1. Create the first credentials (see
   [Creating the first user and token](#creating-the-first-user-and-token)). A `rocoto.yaml` is
   only needed when you want to change a default:

   ```bash
   cd $JBOSS_HOME/standalone/configuration
   java -jar /path/to/rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
   ```

2. Deploy, either by copying the file into the deployment scanner directory

   ```bash
   cp rocoto-war/target/rocoto.war $JBOSS_HOME/standalone/deployments/
   ```

   or with the management CLI:

   ```bash
   $JBOSS_HOME/bin/jboss-cli.sh --connect --command="deploy rocoto-war/target/rocoto.war"
   ```

3. Check the server log for `Rocoto <version> started (configuration: ...)` and
   `Rocoto server adapter: wildfly`.

Notes for WildFly:

- The WAR contains a `jboss-web.xml` that maps the deployment to the application security domain
  `other` and switches Undertow's proactive authentication off (so that Rocoto's filter, not
  Elytron, sees an `Authorization` header first). The domain only matters for `auth.mode:
  container` or `chain`; see
  [03 - Authentication](03-authentication.md) and [10 - WildFly and Elytron](10-wildfly-elytron.md).
- `jboss-deployment-structure.xml` declares an optional dependency on the module
  `org.wildfly.security.elytron`, so the caller's identity can be propagated to streaming threads.
- Pool, session and request statistics are only populated when `statistics-enabled=true` is set
  on the respective subsystem. See [04 - The JMX connection](04-jmx-connection.md).
- The HTTPS listener of the default configuration is port 8443.

## Tomcat 10.1+

1. Create the first credentials:

   ```bash
   cd $CATALINA_BASE/conf
   java -jar /path/to/rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
   ```

2. Deploy:

   ```bash
   cp rocoto-war/target/rocoto.war $CATALINA_BASE/webapps/
   ```

3. Check `logs/catalina.out` for `Rocoto server adapter: tomcat`.

Tomcat does not enable an HTTPS connector out of the box. Configure a `<Connector>` with
`SSLEnabled="true"` in `conf/server.xml`, or terminate TLS in a reverse proxy that talks HTTPS to
Tomcat. If a proxy forwards plain HTTP to Tomcat, the request is not secure from Tomcat's point of
view and Rocoto rejects the credentials, unless Tomcat is told about the original scheme (for
example with a `RemoteIpValve` and `X-Forwarded-Proto`), in which case `request.isSecure()` is
`true`.

### Apache TomEE

TomEE 10 is Tomcat 10.1 plus Jakarta EE services; everything in this section applies unchanged
(`$CATALINA_BASE` is `/usr/local/tomee` in the container images). Rocoto uses the `tomcat` adapter
and reports `"product":"Apache TomEE"`. Container managed data sources from `tomee.xml` /
`resources.xml` are registered as `openejb.management:ObjectType=datasources,DataSource=<id>`; they
appear in `/api/v1/resources/datasources` and as `tomcat_datasource_connections_*` metrics (label
`datasource`, empty `context`).

Verified by `ci/tomee/smoke-test.sh` with the `tomee` images 10.2.0 (Tomcat 10.1.57) in the
flavours webprofile, microprofile, plus and plume on JDK 17, 21 and 25: built-in and
`tomcat-users.xml` authentication, adapter, data source, metrics, Jolokia, SSE, WebSocket. The
`alpine` variants of the image exist for amd64 only; use the default (Ubuntu) tags on arm64.

## Jetty 12

Verified by `ci/jetty/smoke-test.sh` with the official `jetty` images 12.0.39 and 12.1.13 on
JDK 17 and 25 (HTTPS, built-in and LoginService authentication, adapter, metrics, Jolokia, SSE,
WebSocket).

1. Enable the modules in `$JETTY_BASE` - `jmx` makes Jetty register its MBeans (without it
   `/api/v1/resources/...` and the `jetty_*` metrics stay empty), `statistics` feeds the request
   metrics, `ee10-websocket-jakarta` is only needed for WebSocket streaming (SSE works without):

   ```bash
   cd $JETTY_BASE
   java -jar $JETTY_HOME/start.jar \
     --add-modules=ssl,https,ee10-deploy,ee10-annotations,ee10-websocket-jakarta,jmx,statistics
   ```

2. Create the first credentials and deploy:

   ```bash
   java -jar /path/to/rocoto-tools.jar user add ada --roles rocoto-admin --file $JETTY_BASE/etc/rocoto-users.yaml
   chmod 600 $JETTY_BASE/etc/rocoto-users.yaml
   cp rocoto.war $JETTY_BASE/webapps/          # context path /rocoto follows from the file name
   ```

3. `auth.mode: container` / `chain` only: Rocoto's `web.xml` declares BASIC with the realm name
   `Rocoto`. Register a Jetty `LoginService` **with the name `Rocoto`** on the server, e.g. a
   `HashLoginService` in `$JETTY_BASE/etc/rocoto-realm.xml` (add the file name to a
   `start.d/*.ini`); the roles of its users must be `rocoto-reader`, `rocoto-operator` or
   `rocoto-admin`:

   ```xml
   <Configure id="Server" class="org.eclipse.jetty.server.Server">
     <Call class="org.eclipse.jetty.util.resource.ResourceFactory" name="of">
       <Arg><Ref refid="Server"/></Arg>
       <Call id="rocotoRealmResource" name="newResource">
         <Arg><Property name="jetty.base"/>/etc/rocoto-realm.properties</Arg>
       </Call>
     </Call>
     <Call name="addBean">
       <Arg>
         <New class="org.eclipse.jetty.security.HashLoginService">
           <Set name="name">Rocoto</Set>
           <Set name="config"><Ref refid="rocotoRealmResource"/></Set>
         </New>
       </Arg>
     </Call>
   </Configure>
   ```

   Jetty refuses to start a web application that has a `<login-config>` but no LoginService.
   `rocoto.war` therefore ships `WEB-INF/jetty-ee10-web.xml`, which registers an **empty** fallback
   LoginService named `Rocoto`: with `auth.mode: builtin` nothing has to be configured, and nobody
   can authenticate through the fallback. A LoginService of your own with the same name is
   registered earlier and wins.

`GET /api/v1/server` reports `"adapter":"jetty"`, the Jetty version and `"jmxModule": true|false`.

## Open Liberty

Verified by `ci/liberty/smoke-test.sh` with the container images `open-liberty:full-java17-openj9`,
`icr.io/appcafe/open-liberty` (Java 17 and 21) and `icr.io/appcafe/websphere-liberty` (all
26.0.0.9): HTTPS, built-in and registry authentication, adapter, metrics, Jolokia, SSE, WebSocket.

1. `server.xml` - `monitor-1.0` publishes the `WebSphere:*` statistics MBeans (without it
   `/api/v1/resources/...` only lists the applications and there are no `liberty_*` metrics),
   `websocket-2.1` is only needed for WebSocket streaming, `appSecurity-5.0` only for
   `auth.mode: container` / `chain`:

   ```xml
   <server>
     <featureManager>
       <feature>servlet-6.0</feature>
       <feature>websocket-2.1</feature>
       <feature>appSecurity-5.0</feature>
       <feature>transportSecurity-1.0</feature>
       <feature>monitor-1.0</feature>
     </featureManager>
     <httpEndpoint id="defaultHttpEndpoint" host="*" httpPort="9080" httpsPort="9443"/>
     <keyStore id="defaultKeyStore" password="..."/>
     <webApplication location="rocoto.war" contextRoot="/rocoto"/>
   </server>
   ```

2. Create the first credentials next to `server.xml` and copy `rocoto.war` to `apps/`:

   ```bash
   cd $WLP_USER_DIR/servers/defaultServer
   java -jar /path/to/rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
   chmod 600 rocoto-users.yaml
   cp /path/to/rocoto.war apps/
   ```

3. `auth.mode: container` / `chain` only: users come from Liberty's user registry. Without an
   `<application-bnd>` Liberty maps a security role to the registry **group of the same name**:

   ```xml
   <basicRegistry id="basic" realm="Rocoto">
     <user name="carl" password="{xor}..."/>          <!-- securityUtility encode -->
     <group name="rocoto-operator"><member name="carl"/></group>
   </basicRegistry>
   ```

`GET /api/v1/server` reports `"adapter":"liberty"`, product, version and
`"monitorFeature": true|false`. Notes:

- Liberty registers `WebSphere:type=SessionStats` lazily, with the first HTTP session of an
  application; until then the application is missing from `/api/v1/resources/sessions`.
- Liberty rejects an encoded slash (`%2F`) in the URL path with `400`; its session MBeans contain
  one (`name=default_host/rocoto`). Use `POST /api/v1/read` for those, see
  [troubleshooting](12-troubleshooting.md#object-names-containing-a-slash).
- The `datasources` mapping (`WebSphere:type=ConnectionPoolStats`) has **not** been executed:
  the smoke test has no data source.

## Undertow and Netty (embedded runtime)

Undertow's core API and Netty have no servlet layer, so there is nothing `rocoto.war` could be
deployed to. `rocoto-embedded` runs the same bootstrap, authentication filter and servlets as the
WAR on a small exchange abstraction; `rocoto-undertow` and `rocoto-netty` connect it to the two
servers. (WildFly's Undertow *with* servlets is covered by the WAR, see above.)

| | WAR | Embedded runtime |
|---|---|---|
| `auth.mode` | `container`, `builtin`, `chain` | `builtin` only - start-up fails otherwise, there is no container |
| Built-in users, API tokens, JWT, mTLS | yes | yes (mTLS: `embedded.tls.clientAuth: want`, or your own `SslHandler` / HTTPS listener) |
| REST API, `/metrics`, Jolokia protocol, policy, audit | yes | yes |
| Streaming | SSE and WebSocket | SSE |
| Server adapter | per server | `generic`, or the adapter of the server the agent runs in |

Three ways to use it, each with both servers (`ci/embedded/smoke-test.sh` runs all six, the module
tests talk to the standalone servers over TLS):

1. **Mount it into your own server** - Maven dependency `io.rocoto:rocoto-undertow` or
   `io.rocoto:rocoto-netty`; complete applications are in
   [`examples/embedded`](../../examples/embedded/README.md):

   ```java
   RocotoEngine rocoto = RocotoEngine.start("/rocoto");            // reads rocoto.yaml as usual

   // Undertow: requests outside of /rocoto go to your handler
   Undertow.builder().addHttpsListener(8443, "0.0.0.0", sslContext)
           .setHandler(new RocotoUndertowHandler(rocoto, applicationHandler)).build().start();

   // Netty: behind HttpServerCodec and HttpObjectAggregator, one instance per channel. Rocoto
   // blocks (JMX calls) - give it its own executor, never the event loop.
   pipeline.addLast(new HttpServerCodec(), new HttpObjectAggregator(1 << 20),
                    new RocotoNettyHandler(rocoto, rocotoWorkers), applicationHandler);
   ```

   TLS is whatever your server does: an HTTPS listener (Undertow) or an `SslHandler` in the
   pipeline (Netty). Rocoto detects it and refuses credentials on plain connections.

2. **Java agent** - for applications you cannot or do not want to change. The HTTP server inside
   `rocoto-<server>-<version>-all.jar` is relocated (`io.rocoto.shaded.*`), so it does not clash
   with the Netty or Undertow version of the application:

   ```bash
   java -javaagent:/opt/rocoto/rocoto-netty-<version>-all.jar=config=/etc/rocoto/rocoto.yaml,port=8778 -jar app.jar
   ```

   A failure to start is logged and never stops the application. The listener is configured in the
   [`embedded` section](../reference/configuration.md#embedded-servers-embedded) of `rocoto.yaml`
   (default: `127.0.0.1:8778`, plain HTTP - set `embedded.tls.keystore`).

3. **Standalone** - `java -Drocoto.config=... -jar rocoto-undertow-<version>-all.jar` serves the
   MBeans of its own JVM; mostly useful to try Rocoto out.

## Payara 6 (experimental)

Payara support is best effort. `ci/payara/smoke-test.sh` verifies built-in users, file realm users in
`chain` mode, the AMX resources and the `payara` metrics profile; it passed on Payara Server 6.2023.10, 6.2025.4, 6.2025.11 and 7.2026.9 (JDK 17/21/25) and Eclipse GlassFish 7.0.15, 7.0.25, 7.1.0 and 8.0.4.
For GlassFish see [GlassFish differences](#glassfish-differences). Plain MBean access,
authentication, policy and JVM metrics use only standard Jakarta EE and JDK APIs; the normalized
resource view and the `payara` metrics profile additionally depend on Payara's AMX monitoring
MBeans.

1. Create the first credentials:

   ```bash
   cd $PAYARA_HOME/glassfish/domains/domain1/config
   java -jar /path/to/rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
   ```

2. Deploy:

   ```bash
   asadmin deploy rocoto-war/target/rocoto.war
   ```

3. For the normalized resources and the `payara` metrics profile, AMX must be loaded and the
   monitoring level of the modules must be `HIGH` (with `LOW`, Payara 6 and 7 register no
   `*-mon` MBeans). `set-monitoring-level` expects one level per module:

   ```bash
   asadmin set-amx-enabled --enabled=true --dynamic=true
   asadmin set-monitoring-level \
     --module=jdbc-connection-pool,thread-pool,web-container,http-service --level=HIGH,HIGH,HIGH,HIGH
   ```

   Without that, `GET /api/v1/resources/...` returns empty lists while everything else keeps
   working. `GET /api/v1/server` reports `"amxLoaded": true` or `false`.

### GlassFish differences

Eclipse GlassFish uses the same adapter (`"adapter":"payara"`) and metrics profile. Differences found
with the official images:

- **GlassFish 7: no `:` in the URL path.** GlassFish 7.0.x / 7.1.0 answer `503` for every URL whose
  path contains a colon - encoded or not - before Rocoto's filter runs (server log:
  `IllegalArgumentException: invalid URLPatternSpec` from its Jakarta Authorization integration).
  ObjectNames contain a colon, so `GET /api/v1/mbeans/<name>/...` and Jolokia GET URLs do not work.
  Use the body based endpoints (`POST /api/v1/read`, `/write`, `/exec`, `/search`, `/bulk`, Jolokia
  POST), `/api/v1/resources/...` and `/metrics`. GlassFish 8 and Payara are not affected.
- **Configuration location.** The GlassFish 8 container image creates its domain on the first
  start, so nothing can be mounted into `domains/domain1/config`. Set `ROCOTO_CONFIG` instead and
  deploy through `/deploy`.
- **AMX** has no `set-amx-enabled` command. AMX starts when a JMX client connects, or through the
  MBean operation `amx-support:type=boot-amx` / `bootAMX` - via Rocoto with this rule:

  ```yaml
  policy:
    rules:
      - roles: [rocoto-admin]
        allow: [exec]
        mbean: "amx-support:type=boot-amx"
        operations: [bootAMX]
  ```

  ```bash
  curl -u ada -H 'Content-Type: application/json' \
       -d '{"mbean":"amx-support:type=boot-amx","operation":"bootAMX"}' https://host:8181/rocoto/api/v1/exec
  ```
- **Monitoring levels** are set with `asadmin set` (there is no `set-monitoring-level`):

  ```bash
  M=configs.config.server-config.monitoring-service.module-monitoring-levels
  asadmin set $M.jdbc-connection-pool=HIGH $M.thread-pool=HIGH $M.web-container=HIGH $M.http-service=HIGH
  ```
- GlassFish 8 points the JVM's default trust store to a domain store without CA certificates. Set
  `tls.outbound.truststore` when you use OTLP over HTTPS or an HTTPS `jwksUrl`.

`glassfish-web.xml` maps realm groups named `rocoto-reader`, `rocoto-operator` and `rocoto-admin`
onto the roles of the same name (relevant for `auth.mode: container` or `chain`).

## Creating the first user and token

Out of the box the user store is empty, so nobody can log in. Credentials are managed with
`rocoto-tools`, which writes only hashes to `rocoto-users.yaml`. The complete command set:

```text
user add <name> --roles <r1,r2> [--file rocoto-users.yaml] [--password-stdin]
user remove <name> [--file rocoto-users.yaml]
token create <id> --roles <r1,r2> [--days <n> | --expires <ISO-8601>]
                  [--mbeans "<pattern>;<pattern>"] [--file rocoto-users.yaml]
token revoke <id> [--file rocoto-users.yaml]
hash [--password-stdin]            print a PBKDF2 hash for a password
policy convert <jolokia-access.xml>  print a Rocoto policy for a Jolokia policy
tls check <host:port>              show certificate chain and OCSP stapling status
```

Create an administrator. The password is asked for twice on the terminal; it must have at least
12 characters and is never taken from the command line:

```bash
java -jar rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
```

In scripts, pipe the password:

```bash
printf '%s\n' "$ROCOTO_ADMIN_PASSWORD" | \
  java -jar rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml --password-stdin
```

Create a read-only API token for monitoring that expires after 90 days and may only see JVM
MBeans:

```bash
java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader --days 90 \
  --mbeans "java.lang:*;java.nio:*" --file rocoto-users.yaml
```

The token (`rct_prometheus_<secret>`) is printed **once**. Only its SHA-256 hash is stored.
Token ids may contain letters, digits and `-`; user names letters, digits and `. _ @ -`.

The tool writes the file with permissions `rw-------` on POSIX file systems. The file must be
readable by the user the application server runs as; Rocoto logs a warning if it is accessible by
other users. Changes are picked up by the running server within about two seconds - no redeploy.

## First calls

Who am I, and which server is this?

```bash
curl -s -u ada https://server:8443/rocoto/api/v1/server
```

```json
{
  "rocoto": { "version": "0.1.0", "authMode": "builtin" },
  "server": { "adapter": "tomcat", "product": "Apache Tomcat", "version": "Apache Tomcat/10.1.x", "jmxDomain": "Catalina" },
  "caller": { "name": "ada", "roles": ["rocoto-admin"], "authenticatedBy": "basic" }
}
```

Read one attribute with the API token (note the URL-encoded ObjectName):

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  https://server:8443/rocoto/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage
```

```json
{
  "mbean": "java.lang:type=Memory",
  "attribute": "HeapMemoryUsage",
  "value": { "committed": 268435456, "init": 268435456, "max": 4294967296, "used": 73400320 }
}
```

The same read in RPC style, which needs no URL encoding:

```bash
curl -s -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage"}' \
  https://server:8443/rocoto/api/v1/read
```

Health, including configuration warnings:

```bash
curl -s -u ada https://server:8443/rocoto/api/v1/health
```

If your server uses a certificate from a private CA, pass it with `--cacert ca.pem`. Avoid `-k`.

## Credentials over plain HTTP are rejected

With the default configuration, a request that carries credentials over an insecure connection is
answered with `403`, and the attempt is written to the audit log:

```bash
curl -i -u ada http://server:8080/rocoto/api/v1/server
# HTTP/1.1 403
# {"status":403,"error":"Credentials are only accepted over TLS"}
```

For local experiments only, this check can be switched off:

```yaml
# rocoto.yaml - LOCAL TESTING ONLY
auth:
  allowInsecureTransport: true
```

Rocoto logs a warning at start-up and `GET /api/v1/health` reports the status `WARN` with
`auth.allowInsecureTransport is enabled` for as long as the setting is active. Note that the
credentials have already crossed the network unencrypted by the time the server rejects them; the
rejection makes the mistake visible, it cannot undo it.

Next: [03 - Authentication](03-authentication.md)

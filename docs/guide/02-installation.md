# 02 - Installation

This chapter takes you from the source tree to the first authenticated request: build the WAR,
deploy it, create a user and an API token, call the API over HTTPS.

## Requirements

| Component | Requirement |
|-----------|-------------|
| Build | JDK 17 or newer, Maven |
| Runtime | A Jakarta EE 10 web container (Servlet 6.0, optionally WebSocket 2.1) on Java 17+ |
| WildFly / JBoss EAP | WildFly 27 or newer, JBoss EAP 8 |
| Tomcat | 10.1 or newer |
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
> under [`scripts/wildfly/`](scripts/wildfly/) (custom realm, mTLS, OIDC, OCSP) are recipes that
> have not been executed by the build - review and adapt them before use. Payara support is
> experimental and untested.

## Build

```bash
mvn verify
```

Artifacts:

| File | Purpose |
|------|---------|
| `rocoto-war/target/rocoto.war` | The web application to deploy |
| `rocoto-tools/target/rocoto-tools.jar` | Command line tool, runnable with `java -jar` |

The context root is `/rocoto` everywhere: `jboss-web.xml` and `glassfish-web.xml` set it
explicitly, and on Tomcat it follows from the file name `rocoto.war` (do not rename the file).

## Where the configuration lives

Rocoto runs without any configuration file, with secure defaults. To change something, create
`rocoto.yaml` in the server's configuration directory:

| Server | Location |
|--------|----------|
| WildFly / JBoss EAP | `$JBOSS_HOME/standalone/configuration/rocoto.yaml` |
| Tomcat | `$CATALINA_BASE/conf/rocoto.yaml` |
| Payara | `<domain-dir>/config/rocoto.yaml`, for example `glassfish/domains/domain1/config/` |
| any | the file named by the system property `rocoto.config` or the environment variable `ROCOTO_CONFIG` |

The user store `rocoto-users.yaml` is looked up in the same directory as `rocoto.yaml` (unless
`auth.builtin.usersFile` is an absolute path). All keys are described in the
[configuration reference](../reference/configuration.md).

A `rocoto.yaml` is not needed for that: if **no** `rocoto.yaml` exists, relative paths such as the
default `rocoto-users.yaml` are still resolved against the server's configuration directory
(`${jboss.server.config.dir}`, `${catalina.base}/conf`, `${com.sun.aas.instanceRoot}/config`) -
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

## Payara 6 (experimental)

Payara support is best effort and has not been verified by automated tests. Plain MBean access,
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
   monitoring level of the modules must be at least `LOW`:

   ```bash
   asadmin set-monitoring-level \
     --module=jdbc-connection-pool,thread-pool,web-container,http-service --level=LOW
   ```

   Without that, `GET /api/v1/resources/...` returns empty lists while everything else keeps
   working. `GET /api/v1/server` reports `"amxLoaded": true` or `false`.

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
  "rocoto": { "version": "0.1.0-SNAPSHOT", "authMode": "builtin" },
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

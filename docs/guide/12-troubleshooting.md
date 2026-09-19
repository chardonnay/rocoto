# 12. Troubleshooting

Two sources answer most questions:

- the **server log**: start-up problems, user store problems, unexpected errors
  (loggers `io.rocoto.*`);
- the **audit log**: why a login or a request was refused (logger `io.rocoto.audit`, and
  `audit.file` if configured). Responses are deliberately vague; the audit record is not.

`GET /api/v1/server` shows who Rocoto thinks you are and which roles it sees, and
`GET /api/v1/health` lists active warnings.

## Symptom, cause, fix

| Symptom | Likely cause | Fix |
|---|---|---|
| Every request: `503` `Rocoto is not initialised; see server log` | Invalid `rocoto.yaml`, missing file named by `rocoto.config`/`ROCOTO_CONFIG`, unwritable `audit.file`, unreadable trust store | Read the `SEVERE` message `Rocoto failed to start and will reject all requests: ...`, fix, redeploy. [Details](#503-rocoto-is-not-initialised) |
| GlassFish 7: `503` HTML page (not JSON) for URLs with an ObjectName in the path | GlassFish 7 rejects every path containing `:` before Rocoto's filter (`invalid URLPatternSpec` in the server log) | Use `POST /api/v1/read`, `/write`, `/exec`, `/bulk`; fixed in GlassFish 8. [Details](02-installation.md#glassfish-differences) |
| Jetty: resources and `jetty_*` metrics are empty, `"jmxModule": false` | Jetty registers MBeans only with its `jmx` module | `java -jar $JETTY_HOME/start.jar --add-modules=jmx` (and `statistics` for request metrics). [Details](02-installation.md#jetty-12) |
| Open Liberty: resources and `liberty_*` metrics are empty, `"monitorFeature": false` | The statistics MBeans come from the `monitor-1.0` feature | Add `<feature>monitor-1.0</feature>` to `server.xml`. [Details](02-installation.md#open-liberty) |
| `401` although the password is right | User store empty or in another place; unknown user; token disabled or expired | [Details](#401-and-login-loops) |
| `403` `Credentials are only accepted over TLS` | Request arrived on a connection the container reports as not secure | Use HTTPS; behind a proxy configure forwarding headers in the container. [Details](#403-credentials-are-only-accepted-over-tls) |
| `403` `Access denied by policy` | Policy, token scope or built-in deny list | Look up the `DENIED` record in the audit log. [Details](#403-access-denied-by-policy) |
| `403` `Cross-origin request denied` | Browser request from an origin that is not allow-listed, or proxy rewrites `Host` | Add the origin to `cors.allowedOrigins`; forward the original host |
| `403` `Cross-site request denied` | A browser requested the URL from a page of another site (`<img>`, `<script>`, link, redirect): no `Origin` header, but `Sec-Fetch-Site` is neither `same-origin` nor `none`. Also a proxy that adds or forwards a foreign `Sec-Fetch-Site` header | Open the URL directly (typed or bookmarked: `Sec-Fetch-Site: none`) or from a same-origin page; use a non-browser client; do not add `Sec-Fetch-*` headers in scripts or proxies |
| `403` `The application server denied access to this MBean` | The server's own JMX security (WildFly RBAC, a security manager) refused; a refused read is in the audit log as `DENIED`, reason `denied by the application server` | [Chapter 10](10-wildfly-elytron.md#management-rbac-and-the-jmx-subsystem) |
| `405` `write and exec must be sent with POST` on `/jolokia/exec/...` or `/jolokia/write/...` | The GET form of a Jolokia mutation came from a browser (request carries `Sec-Fetch-Site`, `Sec-Fetch-Mode` or `Origin`), or `compat.jolokia.allowGetMutations: false` | Send the request as POST with a JSON body; for jolokia-client-java keep `allowGetMutations: true` ([chapter 9](09-jolokia-compat-and-migration.md#get-and-state-changes)) |
| `413` `The pattern matches N MBeans; at most 1000 can be read at once (api.maxPatternMBeans)` (in Jolokia: `"status":413` inside the envelope) | A pattern read (`POST /read` with a pattern, Jolokia `read` with a pattern) matches more MBeans than `api.maxPatternMBeans` | Narrow the pattern, or raise `api.maxPatternMBeans` |
| `415` | POST/PUT without `Content-Type: application/json` | Add the header (`curl -H 'Content-Type: application/json'`); also for `{}` bodies and for POSTs to `/jolokia` |
| `429` `Too many failed login attempts` | Lockout of the user or of the client address | Wait `lockSeconds`, fix the client. Behind a proxy see `addressMaxAttempts`. [Details](#429) |
| Stream ends with `event: end`, reason `maximum stream duration reached - reconnect` | Every subscription ends after `stream.maxDurationSeconds` (default 3600) | Expected. Reconnect (SSE) or subscribe again (WebSocket); raise the value if needed - `0` means unlimited, not recommended with container, JWT or mTLS identities |
| Stream ends with `event: error`, `{"status":401,"error":"Credentials are no longer valid"}` | The built-in user was removed, the API token revoked or expired, or the roles of either were changed while the stream ran | Reconnect with valid credentials. After a deliberate role change simply reconnect |
| WildFly: `401` for a user from `rocoto-users.yaml` although the password is right; no Rocoto audit record | A custom `jboss-web.xml` (deployment overlay) lacks `<proactive-authentication>false</proactive-authentication>`, so Elytron evaluates the `Authorization` header before Rocoto's filter | Add the element to the overlay file and redeploy. [Details](#401-and-login-loops) |
| `curl -u` / browser does not prompt or sends no credentials in `container` mode with a non-BASIC mechanism | The `401` written by Rocoto carries `auth.container.challenge`, default `Basic realm="Rocoto", charset="UTF-8"` | Set `auth.container.challenge` to match the mechanism, e.g. `Bearer realm="Rocoto"` |
| `429` `Too many active subscriptions` | `stream.maxSubscriptions` or `maxSubscriptionsPerUser` reached | Close unused streams or raise the limits |
| `400`/`404` for ObjectNames containing `/` | Encoded slash in the URL path is rejected or rewritten | Use the RPC style. [Details](#object-names-containing-a-slash) |
| `400` `Operation x is overloaded; specify the signature` | Several operations with that name and argument count | Send `signature` ([chapter 8](08-rest-api.md#overloaded-operations)) |
| `400` `Unsupported parameter type ...` | Operation takes a type outside Rocoto's closed converter set | Not callable through Rocoto by design |
| `/api/v1/resources/...` has empty `items` or only names; few metrics | Statistics disabled on the server | [Details](#empty-resources-and-metrics) |
| Search returns `[]`, reads give `403`, login works | Identity has none of the `policy.readRoles` | Check `caller.roles` in `/api/v1/server`; fix role mapping in the realm / user store |
| WebSocket closes with code 1008 (policy violation) | The upgraded session carries no Rocoto identity | [Details](#websocket-closes-with-a-policy-violation) |
| WebSocket handshake `404`; SSE works | No WebSocket container, or `stream.websocket: false` | Server log: `No WebSocket container available`; use SSE |
| Changes to `rocoto-users.yaml` have no effect | Syntax error (old content kept), wrong file, or write without changed timestamp | [Details](#user-store-is-not-reloaded) |
| WildFly: deployment of `rocoto.war` fails, message mentions the security domain | `jboss-web.xml` names `other`, but no such `application-security-domain` exists | [Details](#wildfly-deployment-fails-because-of-the-security-domain) |
| JWT always `401` | Signature, issuer, audience, time or key problem | Audit `reason` starts with `JWT rejected:`. [Details](#jwt-is-rejected) |
| `/jolokia` answers `404` `The Jolokia compatibility endpoint is disabled` | `compat.jolokia.enabled: false` | Enable it if needed |
| `/metrics` answers `404` `Metrics are disabled` | `metrics.enabled: false` | Enable it if needed |
| Jolokia client fails with `400` `JSONP is not supported` | Client uses `callback`/JSONP | Switch the client to POST/CORS |

## 503: Rocoto is not initialised

Rocoto fails closed: if start-up throws, nothing is published to the servlet context and the
filter answers every request, even anonymous ones, with (the response carries the usual
hardening headers)

```json
{ "status": 503, "error": "Rocoto is not initialised; see server log" }
```

Find the cause in the server log:

```
SEVERE [io.rocoto.api.RocotoBootstrap] Rocoto failed to start and will reject all requests: auth.mode must be one of container, builtin, chain
```

Typical messages: `auth.mode must be one of ...`, `cors.allowedOrigins must list explicit
origins; '*' is not supported`, `auth.builtin.jwt needs exactly one of 'jwksUrl' or 'publicKey'`,
`auth.builtin.jwt.jwksUrl must use https`, `... must be one of off, soft-fail, hard-fail`,
`Cannot open audit file ...`, `tls.selfProbe.url must be an https URL`. Also check *which* file
is read: the search order is in the [configuration reference](../reference/configuration.md).
A file named through `rocoto.config` or `ROCOTO_CONFIG` must exist.

After fixing the file, redeploy the WAR (or restart the server); the configuration is read at
start-up only. The user store is the exception, it is reloaded at runtime.

## 401 and login loops

A browser that keeps showing the login dialog, or a client that gets `401` with correct-looking
credentials:

1. **Look at the audit log.** Built-in authentication records every failure:

   ```json
   {"ts":"...","user":"ada","auth":"builtin","remote":"10.0.4.17","channel":"auth","action":"LOGIN","outcome":"AUTH_FAILED","reason":"wrong user name or password"}
   ```

   Reasons: `wrong user name or password`, `malformed Basic credentials`, `unknown or wrong API
   token`, `API token expired`, `JWT rejected: ...`, `client certificate rejected: ...`,
   `no certificate mapping matches`.
2. **No audit record at all** means Rocoto saw no usable credentials: no `Authorization` header
   (a proxy stripped it), `Basic` while `auth.builtin.basic: false`, an `rct_` token while
   `tokens: false`, or a non-Rocoto bearer token while JWT is disabled - in `builtin` mode. In
   `container` and `chain` mode, credentials that were handed to the application server and
   rejected by it are audited too, with `"auth":"container"`, `"user":null` and the reason
   `rejected by the application server's security domain`:

   ```json
   {"ts":"...","user":null,"auth":"container","remote":"10.0.4.17","channel":"auth","action":"LOGIN","outcome":"AUTH_FAILED","reason":"rejected by the application server's security domain"}
   ```

   Rocoto does not know *why* the server refused; check the server's own security log (on
   WildFly raise `org.wildfly.security` to `DEBUG`/`TRACE`).
3. **User store empty or elsewhere.** The server log says
   `Rocoto user store <path> does not exist - built-in authentication is locked until a user or
   token is created with rocoto-tools`, and `/api/v1/health` (once you can log in) warns
   `built-in user store is empty`. `usersFile` is resolved relative to the directory of
   `rocoto.yaml` - without a `rocoto.yaml`, relative to the server's configuration directory
   (`standalone/configuration`, `$CATALINA_BASE/conf`, `<domain-dir>/config`), not to the working
   directory. `rocoto-tools` writes to `./rocoto-users.yaml` unless `--file` is given.
4. **`chain` mode.** Basic credentials of a user that is not in `rocoto-users.yaml` are handed to
   the container. A typo in the user name therefore ends in a `401` after the *container*
   rejected the credentials, and the audit record says `"auth":"container"`.
5. **WildFly: the `401` comes before Rocoto.** If a built-in user gets `401` and there is *no*
   Rocoto audit record, Undertow's proactive authentication is on: Elytron evaluated the
   `Authorization: Basic` header before Rocoto's filter ran and rejected a user it does not know.
   The shipped `jboss-web.xml` sets `<proactive-authentication>false</proactive-authentication>`;
   a deployment overlay that replaces the file must keep that element
   ([chapter 10](10-wildfly-elytron.md#pointing-rocoto-at-another-security-domain)).
6. **`401` without a login prompt.** Undertow does not send a challenge of its own when
   `request.authenticate()` fails; Rocoto adds `WWW-Authenticate` from `auth.container.challenge`.
   If the header is missing, a proxy removed it.
7. **`container` mode, login works but everything is empty or `403`.** That is not a login
   problem: the identity has no Rocoto role. See the row about `policy.readRoles` above.
8. **Client certificates** are only considered when the request has no `Authorization` header.

## 403: Credentials are only accepted over TLS

Built-in authentication refuses credentials when `request.isSecure()` is false. The credentials
are not evaluated, and the event is audited as `AUTH_FAILED`.

- Direct access: use the HTTPS port.
- TLS terminated by a reverse proxy: the container must learn the original scheme from the
  proxy. Tomcat: `RemoteIpValve` with `protocolHeader="X-Forwarded-Proto"` and a matching
  `internalProxies`. WildFly: `proxy-address-forwarding=true` on the listener the proxy uses.
  AJP connectors transport the scheme by themselves.
- Local experiments only: `auth.allowInsecureTransport: true`. Never in production; health
  reports `WARN` while it is set.

## 403: Access denied by policy

The response never says why. The audit record does:

```json
{"ts":"...","user":"token:prometheus","auth":"token","remote":"10.0.4.20","channel":"api","action":"READ","mbean":"java.lang:type=Memory","member":"Verbose","outcome":"DENIED","reason":"outside the MBean scope of the credential"}
```

| `reason` | Meaning | Fix |
|---|---|---|
| `outside the MBean scope of the credential` | The API token's `mbeans` list does not cover the MBean | Create a token with a wider scope |
| `built-in deny list: ...` | `write`/`exec` on a dangerous MBean | None. Cannot be overridden |
| `deny rule for <pattern>` | One of your `deny` rules matched | Adjust the rule; deny rules are evaluated before allow rules |
| `write/exec requires an explicit allow rule` | No allow rule matches MBean, member and one of the caller's roles | Add a rule ([chapter 5](05-authorization-policy.md)) |
| `no role with read access` | Caller has none of `policy.readRoles` | Fix the caller's roles |

On WildFly or Tomcat, find the records with `grep '"outcome":"DENIED"' server.log`, or in
`audit.file`.

## 415

`Request bodies must be sent as application/json`. Every POST and PUT needs
`Content-Type: application/json` (a `charset` parameter is fine), including bodies like `{}`.
`curl -d` alone sends `application/x-www-form-urlencoded`. This check is part of the CSRF
protection and cannot be switched off.

## 429

`Too many failed login attempts`, with `Retry-After: 60`. After `lockout.maxAttempts` failures for
one user name within `windowSeconds` that user is locked for `lockSeconds` (defaults 5 / 300 s /
900 s); even the correct password is refused while the lock lasts. Independently, failures are
counted per client address (`addressMaxAttempts`, default 50); wrong tokens and JWTs count toward
the address only. The audit outcome is `AUTH_LOCKED`.

- Find the client that keeps sending wrong credentials (`remote` field of the `AUTH_FAILED`
  records) and fix it. Locks expire by themselves; a successful login clears a user's failure
  count.
- Lockout state is kept in memory. Redeploying Rocoto clears it.
- Behind a reverse proxy all clients share the proxy's address. Let the container restore the
  client address from `X-Forwarded-For`, raise `addressMaxAttempts`, or set it to `0`.

## Empty resources and metrics

`/api/v1/resources/{kind}` and the server-specific metric profiles read the server's own
statistics MBeans. If the server does not collect statistics, the MBeans are missing or carry no
values; Rocoto then returns items with only `name` and `mbean`, or an empty list.

**WildFly / JBoss EAP**

```
/subsystem=datasources/data-source=ExampleDS:write-attribute(name=statistics-enabled, value=true)
/subsystem=undertow:write-attribute(name=statistics-enabled, value=true)
reload
```

or `-Dwildfly.statistics-enabled=true`. See [chapter 10](10-wildfly-elytron.md#statistics-giving-rocoto-something-to-show).
Also make sure the `jmx` subsystem exposes the management model (`expose-model=resolved`).

**Payara / GlassFish**

Runtime data is published as AMX MBeans (`amx:*`) once AMX is loaded and the monitoring level of
the module is `HIGH` (one level per module; `LOW` registers no `*-mon` MBeans on Payara 6 / 7).
The statistics of a JDBC pool appear once the pool has been used:

```bash
asadmin set-amx-enabled --enabled=true --dynamic=true
asadmin set-monitoring-level --module=jdbc-connection-pool,thread-pool,web-container --level=HIGH,HIGH,HIGH
```

`GET /api/v1/server` shows `"amxLoaded": false` while the AMX MBeans are absent. Plain MBean access
(`java.lang:*` etc.) works regardless.

**All servers**

- The caller must be allowed to read the underlying MBeans. A scoped token
  (`mbeans: ["java.lang:*"]`) sees no server resources.
- Wrong adapter: `GET /api/v1/server` shows `server.adapter`. Force it with
  `server.adapter: wildfly|tomcat|payara|generic` if auto-detection picked `generic`.

## Object names containing a slash

Tomcat's per-application MBeans contain the context path, for example
`Catalina:type=Manager,host=localhost,context=/shop`. In the resource style the slash must be
encoded as `%2F`. Tomcat rejects encoded slashes in the path with `400` by default, other
components decode them early, which splits the name into two segments and produces `400`
(`Invalid ObjectName`) or `404`.

Use the RPC style, which has no such limits:

```bash
curl -s -u rita -H 'Content-Type: application/json' \
  -d '{"mbean":"Catalina:type=Manager,host=localhost,context=/shop","attribute":"activeSessions"}' \
  https://localhost:8443/rocoto/api/v1/read
```

The `mbean` values returned by `/api/v1/resources/sessions` can be used as they are. For Jolokia
GET requests escape the slash as `!/`. Loosening the container (`encodedSolidusHandling` on
Tomcat's connector) is not recommended.

## WebSocket closes with a policy violation

Close code `1008` with reason `Authentication required` means the WebSocket session was opened
without a Rocoto identity. The upgrade request passes the authentication filter like any other
request, and the identity travels to the endpoint as the handshake's user principal.

- An upgrade request *without* valid credentials does not get that far; it fails with `401`
  during the handshake. Browsers cannot set an `Authorization` header on WebSocket connections;
  from a browser use SSE (`/api/v1/subscribe`) or a same-origin page with Basic credentials
  already cached by the browser.
- If the handshake succeeds and the session is closed immediately with `1008`, the container did
  not hand the wrapped request's principal to the WebSocket session, or Rocoto was restarted while
  the connection was being established. Use SSE on such a container and report the server and
  version to the project.
- Errors *inside* an open session (unknown MBean, denied attribute, limits, credentials that are
  no longer valid) do not close it. They arrive as
  `{"id":"...","event":"error","data":{"status":403,"error":"..."}}`; the end of a subscription
  after `stream.maxDurationSeconds` arrives as `"event":"end"`.

## User store is not reloaded

`rocoto-users.yaml` is checked when credentials are verified, at most every two seconds, and
reloaded when its modification time changed. The server log confirms each reload:

```
INFO [io.rocoto.security.auth.UserStore] Loaded Rocoto user store /opt/.../rocoto-users.yaml (2 users, 1 tokens, 0 certificate mappings)
```

If nothing happens:

- **Parse error.** `SEVERE Cannot load Rocoto user store ... - keeping previous content`. Rocoto
  keeps the last good state on purpose, so that a typo neither opens nor locks the door. Fix the
  file and save again.
- **Wrong file.** `rocoto-tools` defaults to `./rocoto-users.yaml` in the current directory. Pass
  `--file` with the path the server uses (the `Loaded ...` log line shows it).
- **Unchanged timestamp.** Some deployment tools restore the original modification time. `touch`
  the file.
- **Container images / Kubernetes.** A `subPath` mount of a ConfigMap or Secret is never updated
  by the kubelet. Mount the directory instead.
- **Changed passwords.** The in-memory cache of verified passwords is bound to the stored hash,
  so the old password stops working as soon as the file has been reloaded.

## WildFly: deployment fails because of the security domain

`WEB-INF/jboss-web.xml` references the security domain `other`. Every default WildFly
configuration maps that name in the Undertow subsystem:

```
/subsystem=undertow/application-security-domain=other:read-resource
```

If the resource does not exist (custom or slimmed-down configuration, Galleon provisioning
without the default security setup), the deployment cannot be installed; the failure message
names the missing security domain. This happens in **every** `auth.mode`, because WildFly
resolves the reference at deployment time. Fix it in one of these ways:

```
# a) create the mapping
/subsystem=undertow/application-security-domain=other:add(security-domain=ApplicationDomain)

# b) point Rocoto at an existing mapping with a deployment overlay
deployment-overlay add --name=rocoto-security-domain \
    --content=/WEB-INF/jboss-web.xml=/path/to/jboss-web.xml --deployments=rocoto.war --redeploy-affected
```

The overlay file must keep `<proactive-authentication>false</proactive-authentication>`
(template: [`scripts/wildfly/jboss-web-rocoto-domain.xml`](scripts/wildfly/jboss-web-rocoto-domain.xml)).

See [chapter 10](10-wildfly-elytron.md#pointing-rocoto-at-another-security-domain).

## JWT is rejected

The client only sees `401`. The audit record carries the reason:

| `reason` (`JWT rejected: ...`) | Check |
|---|---|
| `malformed JWT`, `not a signed JWT`, `token too long` | Is it really a compact JWS access token (three dot-separated parts)? Opaque tokens and encrypted JWTs (JWE) are not supported |
| `unsupported algorithm <alg>` | The token must be signed with an RSA or EC algorithm. `none` and HMAC algorithms are not accepted |
| `key type does not match algorithm` | The key found for the token's `kid` is of another type than `alg` claims |
| `no matching key in JWKS` | `jwksUrl` belongs to another realm/tenant, or the keys were rotated and the JWKS endpoint was unreachable. Look for JWKS download errors in the server log; mind `tls.outbound` (revocation, trust store) |
| `invalid signature` | Wrong `publicKey`, or token from another issuer |
| `unexpected issuer` | `iss` must equal `auth.builtin.jwt.issuer` exactly (trailing slash, host name as seen by the client that obtained the token) |
| `unexpected audience` | `aud` must contain `auth.builtin.jwt.audience`. Keycloak: add an audience mapper to the client |
| `exp missing`, `token expired`, `token not yet valid` | Token lifetime and clocks; `clockSkewSeconds` defaults to 60 |
| `claim 'sub' missing` | `nameClaim` names a claim the token does not have |

A token that is accepted but yields no roles produces `200` with empty results or `403`. Compare
`caller.roles` in `/api/v1/server` with `rolesClaim` (dotted paths such as `realm_access.roles`
descend into objects) and `roleMapping`. Mind the mapping rule: as soon as `roleMapping` is not
empty, **only mapped roles count** - a token role `rocoto-reader` is dropped unless the mapping
contains `rocoto-reader: rocoto-reader`. With an empty mapping the roles pass through unchanged.

Bearer tokens that start with `rct_` are always treated as Rocoto API tokens, never as JWTs.

## Raising log levels

Rocoto logs through `java.util.logging`. Logger names are the class names below `io.rocoto`
(`io.rocoto.api.*`, `io.rocoto.security.*`, `io.rocoto.adapters.*`, `io.rocoto.metrics.*`), plus
the audit logger `io.rocoto.audit`. Most messages are `INFO`, `WARNING` or `SEVERE`; `FINE` adds a
few details such as identity propagation problems.

### WildFly / JBoss EAP

```
/subsystem=logging/logger=io.rocoto:add(level=FINE)
# the default CONSOLE handler is limited to INFO; the FILE handler (server.log) is not
/subsystem=logging/console-handler=CONSOLE:write-attribute(name=level, value=FINE)
```

Audit records in a file of their own, rotated daily by the server:

```
/subsystem=logging/pattern-formatter=ROCOTO_AUDIT_FMT:add(pattern="%s%n")
/subsystem=logging/periodic-rotating-file-handler=ROCOTO_AUDIT:add( \
    file={relative-to=jboss.server.log.dir, path=rocoto-audit.log}, \
    suffix=".yyyy-MM-dd", named-formatter=ROCOTO_AUDIT_FMT, append=true)
/subsystem=logging/logger=io.rocoto.audit:add(level=INFO, handlers=[ROCOTO_AUDIT], use-parent-handlers=false)
```

Review and adapt before use; written against the WildFly 27+ management model. Back to normal:
`/subsystem=logging/logger=io.rocoto:remove`.

### Tomcat

`$CATALINA_BASE/conf/logging.properties` (JULI):

```properties
io.rocoto.level = FINE
io.rocoto.audit.level = INFO

# handlers filter too: raise them if FINE messages do not show up
java.util.logging.ConsoleHandler.level = FINE
1catalina.org.apache.juli.AsyncFileHandler.level = FINE
```

A separate audit file:

```properties
handlers = 1catalina.org.apache.juli.AsyncFileHandler, 2localhost.org.apache.juli.AsyncFileHandler, \
           5rocoto.org.apache.juli.AsyncFileHandler, java.util.logging.ConsoleHandler

5rocoto.org.apache.juli.AsyncFileHandler.level = INFO
5rocoto.org.apache.juli.AsyncFileHandler.directory = ${catalina.base}/logs
5rocoto.org.apache.juli.AsyncFileHandler.prefix = rocoto-audit.
5rocoto.org.apache.juli.AsyncFileHandler.formatter = org.apache.juli.VerbatimFormatter

io.rocoto.audit.handlers = 5rocoto.org.apache.juli.AsyncFileHandler
io.rocoto.audit.useParentHandlers = false
```

Keep the other handlers of your existing `handlers` line. Tomcat reads the file at start-up;
restart after changing it.

### Payara

```bash
asadmin set-log-levels io.rocoto=FINE
```

Whatever the server: do not set the logger `io.rocoto.audit` to `WARNING` or `OFF` unless
`audit.file` is configured. Audit records are logged at `INFO` and would be lost.

## Next

[13. TLS and OCSP stapling](13-tls-and-ocsp.md)

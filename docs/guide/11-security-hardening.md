# 11. Security hardening

JMX is a remote administration interface: whoever can call arbitrary MBean operations can usually
execute code in the JVM. A JMX-over-HTTP bridge therefore deserves the same care as an SSH daemon.
This chapter describes the threats Rocoto was designed against, what it guarantees, and what
remains your job in production.

## Threat model: what went wrong with JMX bridges

| Problem | How it was exploited | Rocoto's answer |
|---|---|---|
| **Unauthenticated by default** | Agents deployed "just for monitoring" were reachable without credentials; scanners look for `/jolokia` | No anonymous access in any mode. No configuration at all means built-in authentication with an empty user store: nobody gets in until a user or token is created |
| **Proxy mode, JNDI injection (CVE-2018-1000130)** | A request with a `target` JMX service URL such as `service:jmx:rmi:///jndi/ldap://attacker/x` made the agent perform a JNDI lookup against an attacker's server, leading to remote code execution | There is no proxy mode. Rocoto never opens connections to targets named by a client; a Jolokia request containing `target` gets `403` |
| **logback `JMXConfigurator.reloadByURL`** | `exec` on `ch.qos.logback.classic:*` loads a configuration file from an attacker's URL; the file triggers JNDI lookups or writes files, ending in RCE. log4j2's `setConfigText`/`setConfigLocationUri` are similar | Built-in deny list for `write`/`exec`; cannot be overridden by policy |
| **`DiagnosticCommand` / `HotSpotDiagnostic`** | `jvmtiAgentLoad` loads a native library, `compilerDirectivesAdd`, `vmLog` and `dumpHeap` read or write arbitrary files, `setVMOption` changes the JVM | Deny list |
| **MLet (`javax.management.loading`)** | `getMBeansFromURL` downloads and instantiates classes from a URL | Deny list for the domain, plus the operations `getMBeansFromURL` and `addURL` on any MBean |
| **Server-specific escalation** | Tomcat `MBeanFactory` creates valves, realms and connectors, `Users:*` edits the user database; on WildFly the management model exposes Elytron and the management interface itself | Deny list: `Catalina:type=MBeanFactory`, `Users:*`, `jboss.as[.expr]:subsystem=elytron,*`, `jboss.as[.expr]:core-service=management,*`, `jboss.as:core-service=vault` |
| **CSRF** | A web page makes the administrator's browser send `exec` requests, authenticated by cached Basic credentials | Requests with a foreign `Origin` are refused; requests without `Origin` whose `Sec-Fetch-Site` header is not `same-origin` or `none` are refused too (`<img>`, `<script>` and navigation GETs); POST/PUT bodies must be `application/json`, which HTML forms cannot send; no GET endpoint of the native API changes state; Jolokia's GET form of `write`/`exec` is refused for browsers (see below) |
| **Permissive CORS / JSONP** | `Access-Control-Allow-Origin: *` or a `callback` parameter lets any site read JMX data | Explicit origin allow list only, `*` is rejected at start-up; JSONP does not exist |
| **Information leaks** | Stack traces in responses; passwords in MBean attributes (data source, mail session, connector configuration) | No stack traces; attribute names and nested keys matching `*password*`, `*secret*`, `*credential*`, ... are masked on the server |
| **Reflection-based serialization** | Walking arbitrary getters exposes internals and triggers side effects | Unknown types are rendered with `toString()` |
| **Client-controlled type conversion** | Instantiating classes named in the request is a deserialization gadget | Closed set of target types for arguments |
| **Credential brute force** | Unlimited login attempts | Lockout per user and per client address; PBKDF2-SHA256 password hashes; constant-time handling of unknown users |

### CSRF in detail

A browser that has cached Basic credentials for the Rocoto origin attaches them to *every*
request to that origin, whichever page triggered it. Rocoto's filter therefore refuses, before
authentication:

| Request | Browser behaviour | Rocoto's check | Answer |
|---|---|---|---|
| cross-origin `fetch`/XHR, any `POST` | sends `Origin` | origin must be the server itself or in `cors.allowedOrigins` | `403 Cross-origin request denied` |
| cross-site `GET` through `<img>`, `<script>`, a link or a redirect | sends **no** `Origin`, but `Sec-Fetch-Site: cross-site` (or `same-site`) | without `Origin`, `Sec-Fetch-Site` must be absent, `same-origin` or `none` | `403 Cross-site request denied` |
| HTML form `POST` | cannot send `application/json` | `POST`/`PUT` bodies must be `application/json` | `415` |

Non-browser clients send neither header and are not affected.

**Jolokia's GET form of `write` and `exec`** (`/jolokia/exec/...`, `/jolokia/write/...`) changes
state with a plain URL, which is exactly what CSRF attacks are made of - yet the unmodified
jolokia-client-java sends simple `exec`/`write` requests as GET. The compromise: such a request
is only accepted from non-browser clients. If it carries `Sec-Fetch-Site`, `Sec-Fetch-Mode` or
`Origin`, it is answered with `405` `write and exec must be sent with POST`; POST is protected
by the JSON-only rule. In any case the call needs an allow rule like every other `write`/`exec`.

Residual risk, stated honestly: very old browsers that do not send fetch metadata (Safari before
16.4, Firefox before 90) are indistinguishable from non-browser clients. An operator using such a
browser, with cached Basic credentials and a policy that allows the operation, could be made to
trigger a GET `exec`. When all your Jolokia clients can POST, switch the GET form off entirely:

```yaml
compat:
  jolokia:
    allowGetMutations: false   # GET write/exec always answered with 405
```

## What Rocoto guarantees

These are the guarantees of the project's [SECURITY.md](../../SECURITY.md); a violation of any of
them is treated as a vulnerability:

- No request is processed without an authenticated identity. (The single, opt-in exception is
  `GET /api/v1/health/live` with `health.allowAnonymous: true`; it returns a constant.)
- `write` and `exec` are denied unless explicitly allowed by the policy.
- MBeans on the built-in deny list can never be written to or executed.
- Masked attribute values never leave the server.
- Rocoto never opens connections to JMX targets supplied by a client (no proxy mode).
- Every `write`, `exec`, denial and failed login produces an audit record.

Further properties worth knowing:

- **Fail closed.** If the configuration is invalid or a file named by `rocoto.config` /
  `ROCOTO_CONFIG` does not exist, Rocoto does not start and every request gets `503` (with the
  same hardening headers as every other response).
- **Revocation reaches running streams.** SSE and WebSocket subscriptions re-validate a built-in
  user or API token before every poll and every notification: a removed user, a revoked or
  expired token or changed roles end the stream with an `error` event (`401`). Identities from
  the container, a JWT or a client certificate cannot be re-validated; for them - and as an upper
  bound for everybody - every stream ends after `stream.maxDurationSeconds` (default 3600) and
  the client has to reconnect and authenticate again. See [chapter 7](07-streaming.md).
- **One pipeline.** REST, Jolokia compatibility, `/metrics`, SSE and WebSocket all go through the
  same policy, masking and audit code. `/metrics` is evaluated with the scraper's identity, so a
  scoped token sees only the metrics of MBeans inside its scope.
- **Generic denials.** A `403` never tells the caller which rule fired; the reason is in the audit
  log.
- **Credentials only over TLS.** Basic credentials, API tokens and JWTs received on a connection
  the container does not report as secure are refused with `403` *before* they are evaluated.

## Production checklist

### Transport

- [ ] Expose Rocoto on an HTTPS listener only. TLS 1.2 or later, preferably 1.3.
- [ ] **Never enable `auth.allowInsecureTransport`.** It exists for local tests. When it is on,
      Rocoto logs a warning at start-up and `/api/v1/health` reports status `WARN`.
- [ ] Enable OCSP stapling on the listener and let Rocoto watch it
      ([chapter 13](13-tls-and-ocsp.md)).
- [ ] Keep `tls.outbound.revocation` at `soft-fail` or `hard-fail` for JWKS and OTLP connections.
      `metrics.otlp.allowInsecure` stays `false`.

### Authentication

- [ ] Decide between `builtin` and `container`:
      `builtin` gives API tokens with expiry and MBean scope, lockout, and identical behavior on
      all servers. `container` reuses the server's identity management (LDAP, Kerberos, OIDC) and
      its password policy. `chain` combines them; use it when humans come from the directory and
      machines use tokens.
- [ ] One credential per client. No shared "monitoring" password.
- [ ] Machines get **API tokens**, not passwords, with the least privilege they need:

  ```bash
  java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader --days 90 \
       --mbeans "java.lang:*;jboss.as:subsystem=datasources,*" \
       --file /opt/wildfly/standalone/configuration/rocoto-users.yaml
  ```

  The token is printed once; only its SHA-256 hash is stored. Set an expiry (`--days` or
  `--expires`) and rotate. `token revoke <id>` takes effect within seconds, without restart -
  for new requests and for streams that are already running.
- [ ] `rocoto-admin` is for people, and for few of them.
- [ ] With JWT: `issuer` and `audience` are mandatory; use `jwksUrl` over HTTPS. **Configure a
      `roleMapping`.** With a non-empty mapping only mapped roles count and every other role in
      the token is dropped. With an empty mapping (the default) the token's roles pass through
      unchanged, so a role literally named `rocoto-admin` at the identity provider grants
      administrator rights. Keep JWT lifetimes short and set `stream.maxDurationSeconds`
      accordingly - a stream opened with a JWT runs until that limit.
- [ ] With mTLS in built-in mode keep `auth.builtin.mtls.revocation: hard-fail` and use a
      dedicated trust store that contains only the CA issuing client certificates, not the JDK
      default trust store.

### Files

- [ ] `rocoto-users.yaml` must be readable by the application server's user only:

  ```bash
  chown wildfly:wildfly rocoto-users.yaml && chmod 600 rocoto-users.yaml
  ```

  Rocoto logs a warning when the file is readable or writable by others. It contains only hashes,
  but hashes of weak passwords can be attacked offline. `rocoto-tools` enforces a minimum
  password length of 12 characters and never takes passwords from the command line.
- [ ] `rocoto.yaml`: mode `640` or stricter, and no secrets in it (see below).
- [ ] Configure `audit.file` on a path the server user can write and others cannot read. Ship it
      to your log management; Rocoto appends and does not rotate the file, so use `logrotate`
      with `copytruncate` or rely on the `io.rocoto.audit` logger and the server's log handlers.

### Policy

- [ ] Start read-only. Add `allow` rules per MBean and per operation or attribute, never
      `mbean: "*:*"` with `allow: [exec]`.
- [ ] Add `deny: [read]` rules for data that readers should not see, for example
      `java.lang:type=Runtime` attributes `SystemProperties` and `InputArguments` (they often
      contain secrets passed as `-D` options).
- [ ] Extend `policy.mask` with name patterns specific to your applications.
- [ ] Leave `audit.execArguments` off unless you are certain that no operation takes secrets as
      arguments.

### Network and reverse proxies

- [ ] Restrict who can reach `/rocoto` at the network level: management network, firewall, or an
      allow list in the reverse proxy. Authentication is mandatory, but it should not be the only
      layer.
- [ ] Do not publish `/rocoto` on the same public virtual host as your applications. If the
      listener is shared, block the path in the public-facing proxy.
- [ ] **Lockout behind a proxy.** Failed logins are counted per user *and* per client address
      (`auth.builtin.lockout.addressMaxAttempts`, default 50 per window). Behind a reverse proxy
      every client appears with the proxy's address, so one misconfigured client can lock out
      everybody for `lockSeconds`. Either let the container restore the real client address (next
      item), raise the value, or set it to `0` to disable the per-address lockout and rely on the
      per-user lockout plus rate limiting in the proxy.
- [ ] **`X-Forwarded-*` is the container's job.** Rocoto uses `request.getRemoteAddr()`,
      `request.isSecure()` and the scheme/host/port of the request as the container reports them;
      it never parses forwarding headers itself. Configure the container to evaluate them *only
      for trusted proxies*: Tomcat `RemoteIpValve` (`internalProxies`, `protocolHeader`), WildFly
      `proxy-address-forwarding=true` on a listener that only the proxy can reach. If the container
      trusts these headers from anyone, clients can fake a secure connection and their address.
- [ ] If the proxy terminates TLS, the hop between proxy and application server should still be
      TLS, or at least a loopback / isolated network.
- [ ] The proxy must pass `Authorization` unchanged, must not cache, and for streaming must not
      buffer (`proxy_buffering off` for SSE, upgrade headers for WebSocket).
- [ ] The same-origin check compares the `Origin` header with scheme, host and port of the request
      as seen by the container. A proxy that rewrites `Host` without forwarding headers makes
      same-origin browser requests fail with `403`; API clients do not send `Origin` and are not
      affected.

### Surface reduction

- [ ] `compat.jolokia.enabled: false` if no Jolokia client is left. If it stays on and all
      clients can POST: `compat.jolokia.allowGetMutations: false`.
- [ ] Keep `api.maxBulkRequests`, `api.maxPatternMBeans` and `stream.maxDurationSeconds` at their
      defaults or lower; do not set `stream.maxDurationSeconds: 0` when identities come from the
      container, JWTs or client certificates.
- [ ] `cors.allowedOrigins` stays empty unless a browser UI on another origin needs the API. List
      exact origins (`https://ops.example.com`); wildcards are not supported. Allowed origins
      receive `Access-Control-Allow-Credentials: true`, so treat the list as a list of sites you
      trust with your operators' sessions.
- [ ] `stream.websocket: false` if only SSE is used; keep `stream.maxSubscriptionsPerUser` low.
- [ ] `metrics.enabled: false` if nothing scrapes.
- [ ] `health.allowAnonymous` only if an orchestrator cannot send credentials. It exposes nothing
      but `{"status":"UP"}`.

## What is logged

Audit records are JSON lines. They always go to the `java.util.logging` logger `io.rocoto.audit`
(level `INFO`), and additionally to `audit.file` if configured.

```json
{"ts":"2026-03-01T09:14:07.412Z","user":"otto","auth":"basic","remote":"10.0.4.17","channel":"api","action":"EXEC","mbean":"java.lang:type=Memory","member":"gc","outcome":"ALLOWED"}
{"ts":"2026-03-01T09:14:12.903Z","user":"rita","auth":"basic","remote":"10.0.4.18","channel":"jolokia","action":"EXEC","mbean":"com.sun.management:type=DiagnosticCommand","member":"vmVersion","outcome":"DENIED","reason":"built-in deny list: diagnostic commands can load native agents"}
{"ts":"2026-03-01T09:15:40.020Z","user":"ada","auth":"builtin","remote":"203.0.113.9","channel":"auth","action":"LOGIN","outcome":"AUTH_FAILED","reason":"wrong user name or password"}
```

| Event | Recorded |
|---|---|
| `write`, `exec` | always: `ALLOWED`, or `FAILED` with the error message |
| any request denied by policy, token scope or deny list | always: `DENIED` with the precise `reason` |
| a read the policy allowed but the application server refused (for example WildFly management RBAC) | always: `DENIED` with the reason `denied by the application server` |
| failed login, locked login, credentials over plain HTTP (built-in authentication) | always: `AUTH_FAILED` / `AUTH_LOCKED`, `auth` is `builtin` |
| credentials presented to the container and rejected by it (`container` and `chain` mode) | always: `AUTH_FAILED`, `auth` is `container`, `user` is `null`, reason `rejected by the application server's security domain` |
| successful reads | only with `audit.reads: true` (high volume with scrapers) |
| operation arguments | only with `audit.execArguments: true`; arguments whose parameter name matches a mask are masked |
| written values | always (`args`), replaced by `********` if the attribute name matches a mask |
| successful logins | not recorded separately; every audited action carries user, auth source and address |

Fields: `ts`, `user`, `auth` (`basic`, `token`, `jwt`, `mtls`, `container`, `internal` for the OTLP
exporter; for login events `builtin` or `container`), `remote` (the literal `websocket` for WebSocket
subscriptions), `channel` (`api`, `jolokia`, `metrics`, `stream`, `auth`), `action` (`READ`,
`WRITE`, `EXEC`, `LIST`, `SEARCH`, `LOGIN`), `mbean`, `member`, `outcome`, `reason`, `args`.

Not logged by Rocoto: passwords, tokens, `Authorization` headers and masked values. In `container`
and `chain` mode Rocoto records *that* the application server rejected presented credentials
(without the user name, which Rocoto does not parse); details such as the user name, and any
lockout, are the application server's business and appear in its own security log. Requests
rejected by the origin, fetch-metadata and content-type checks are answered before
authentication and are not audited.

## Handling secrets

Any string value in `rocoto.yaml` may be a reference that is resolved at start-up:

| Syntax | Source |
|---|---|
| `${env:NAME}` | environment variable |
| `${sys:name}` | Java system property |
| `${file:/path}` | content of a file, trimmed; suited for Kubernetes or Docker secrets |

```yaml
auth:
  builtin:
    mtls:
      enabled: true
      truststore: /etc/rocoto/client-ca.p12
      truststorePassword: ${file:/run/secrets/rocoto-truststore-password}
metrics:
  otlp:
    enabled: true
    endpoint: https://collector.example.com:4318/v1/metrics
    headers:
      Authorization: ${env:OTLP_AUTH}
```

Recommendations:

- Prefer `${file:}` over `${env:}`. Environment variables are visible in `/proc/<pid>/environ`,
  in crash dumps and, on many servers, through the very MBeans Rocoto exposes
  (`java.lang:type=Runtime`). System properties (`${sys:}`) are readable through
  `SystemProperties` as well; deny or mask them if you use that form.
- A reference must be the whole value; it cannot be embedded in a longer string.
- User passwords and API tokens never appear in configuration. They are created with
  `rocoto-tools`, which writes PBKDF2 (passwords) or SHA-256 (tokens) hashes.
- On WildFly, keep key store passwords of the listener in an Elytron credential store rather than
  in `standalone.xml`.

## Reporting vulnerabilities

Do not open public issues for security problems. Use GitHub's private vulnerability reporting as
described in [SECURITY.md](../../SECURITY.md).

## Next

[12. Troubleshooting](12-troubleshooting.md)

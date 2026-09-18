# Configuration reference

Rocoto is configured with one YAML file, `rocoto.yaml`. **Without any file Rocoto starts with
secure defaults**: built-in authentication, TLS-only credentials, read-only policy, JVM metrics.

## Where Rocoto looks for `rocoto.yaml`

First hit wins:

| # | Location | Typical server |
|---|----------|----------------|
| 1 | system property `rocoto.config` | any |
| 2 | environment variable `ROCOTO_CONFIG` | containers |
| 3 | `${jboss.server.config.dir}/rocoto.yaml` | WildFly / JBoss EAP (`standalone/configuration/`) |
| 4 | `${catalina.base}/conf/rocoto.yaml` | Tomcat |
| 5 | `${com.sun.aas.instanceRoot}/config/rocoto.yaml` | Payara / GlassFish |
| 6 | `rocoto.yaml` on the class path | embedded in a custom WAR |

If 1 or 2 points to a file that does not exist, Rocoto refuses to start. An invalid configuration
also aborts start-up; every request is then answered with `503` (Rocoto fails closed).

Relative paths inside the file (`usersFile`, metric profiles) are resolved against the directory
of `rocoto.yaml`.

### Keeping secrets out of the file

Any string value may be a single reference:

| Syntax | Resolved from |
|--------|---------------|
| `${env:NAME}` | environment variable |
| `${sys:name}` | Java system property |
| `${file:/path}` | content of a file (trimmed) – e.g. a Kubernetes secret mount |

## Complete example with defaults

```yaml
server:
  adapter: auto                 # auto | wildfly | tomcat | payara | generic

auth:
  mode: builtin                 # builtin | container | chain
  allowInsecureTransport: false # true = accept credentials over plain HTTP (local testing only)
  container:
    # WWW-Authenticate value Rocoto sends in container/chain mode when the application server did
    # not issue a challenge itself (WildFly/Undertow for requests without credentials)
    challenge: 'Basic realm="Rocoto", charset="UTF-8"'
  builtin:
    usersFile: rocoto-users.yaml
    basic: true                 # HTTP Basic against the user store
    tokens: true                # API tokens (Authorization: Bearer rct_...)
    lockout:
      maxAttempts: 5            # failed logins per user before the user is locked
      addressMaxAttempts: 50    # failed logins per client address; 0 disables (reverse proxies!)
      windowSeconds: 300
      lockSeconds: 900
    mtls:
      enabled: false
      revocation: hard-fail     # off | soft-fail | hard-fail  (OCSP first, CRL fallback)
      truststore: /path/ca.p12  # default: the JDK trust store
      truststorePassword: ${env:ROCOTO_TRUSTSTORE_PASSWORD}
      truststoreType: PKCS12
      ocspResponder: null       # override the responder URL from the certificate
      cacheSeconds: 300         # how long a positive revocation result is reused
    jwt:
      enabled: false
      issuer: https://idp.example/realms/ops      # required
      audience: rocoto                            # required
      jwksUrl: https://idp.example/realms/ops/protocol/openid-connect/certs
      # publicKey: |                              # alternative to jwksUrl (PEM, RSA or EC)
      nameClaim: sub
      rolesClaim: roles         # dotted paths descend into objects: realm_access.roles
      roleMapping: {}           # e.g. { ops-admin: rocoto-admin }; if set, unmapped roles are dropped
      clockSkewSeconds: 60

policy:
  readRoles: [rocoto-reader, rocoto-operator, rocoto-admin]
  rules: []                     # see "Policy rules"
  mask: []                      # additional name globs, added to the built-in ones

audit:
  file: null                    # JSON lines; always also logged to the io.rocoto.audit logger
  reads: false                  # also record successful reads
  execArguments: false          # record operation arguments (may contain secrets!)

cors:
  allowedOrigins: []            # explicit origins only, '*' is rejected

health:
  allowAnonymous: false         # true = GET /api/v1/health/live needs no credentials

api:
  maxBulkRequests: 100

metrics:
  enabled: true
  profiles: [jvm, auto]         # jvm | tomcat | wildfly | payara | auto | <path to profile file>
  rules: []                     # inline mapping rules, same format as a profile
  otlp:
    enabled: false
    endpoint: https://collector:4318/v1/metrics
    allowInsecure: false        # allow http:// endpoints
    intervalSeconds: 60
    serviceName: rocoto
    resourceAttributes: {}
    headers: {}                 # e.g. { Authorization: "${env:OTLP_AUTH}" }
    roles: [rocoto-reader]      # roles of the internal identity "otlp-exporter"

stream:
  websocket: true
  maxSubscriptions: 200
  maxSubscriptionsPerUser: 20
  minIntervalMillis: 500
  maxDurationSeconds: 3600      # a stream ends after this time and the client reconnects; 0 = unlimited
  threads: 2

compat:
  jolokia:
    enabled: true               # the /jolokia endpoint

tls:
  outbound:                     # connections Rocoto opens: JWKS download, OTLP push
    revocation: soft-fail       # off | soft-fail | hard-fail
    requireStapling: false      # true = reject servers that do not staple an OCSP response
    truststore: null            # default: the JDK trust store
    truststorePassword: null
  selfProbe:
    url: null                   # e.g. https://localhost:8443 - enables the TLS watchdog
    expectStapling: true
    warnDaysBeforeExpiry: 21
    intervalSeconds: 300
```

## Policy rules

```yaml
policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]   # required for allow rules
      allow: [exec]                            # read | write | exec | list | search | all
      mbean: "java.lang:type=Memory"           # ObjectName or pattern
      operations: [gc]                         # globs; omit for all operations
    - roles: [rocoto-admin]
      allow: [exec]
      mbean: "java.util.logging:type=Logging"
      operations: [setLoggerLevel]
    - roles: [rocoto-admin]
      allow: [write]
      mbean: "java.lang:type=Memory"
      attributes: [Verbose]                    # globs; omit for all attributes
    - deny: [read]                             # deny rules may omit roles = everybody
      mbean: "java.lang:type=Runtime"
      attributes: [SystemProperties, InputArguments]
```

Evaluation order, first match wins:

1. API token MBean scope (`mbeans:` of the token)
2. built-in deny list (write/exec only, **cannot be overridden**)
3. `deny` rules
4. `allow` rules
5. default: read/list/search for `readRoles`; write/exec denied

`read` in a rule implies `list` and `search`. Receiving JMX notifications of an MBean is a `read`
on the pseudo attribute `@notifications`.

### Built-in deny list (write/exec)

`ch.qos.logback.classic:*`, `org.apache.logging.log4j2:*`, `com.sun.management:type=DiagnosticCommand`,
`com.sun.management:type=HotSpotDiagnostic`, `jdk.management.jfr:*`, `javax.management.loading:*`,
operations `getMBeansFromURL`/`addURL` on any MBean, `JMImplementation:*`,
`jboss.as[.expr]:subsystem=elytron,*`, `jboss.as[.expr]:core-service=management,*`,
`jboss.as:core-service=vault`, `Catalina:type=MBeanFactory`, `Users:*`, `io.rocoto:*`.

### Built-in masks

Attribute names and nested keys matching (case-insensitive) `*password*`, `*passwd*`, `*secret*`,
`*credential*`, `*privatekey*`, `*private-key*`, `*apikey*`, `*api-key*`, `*accesskey*`,
`*authtoken*`, `*clear-text*`, `*cleartext*` are replaced by `********` on the server.

## User store (`rocoto-users.yaml`)

Managed with `rocoto-tools`; contains only hashes. Re-read automatically when it changes.

```yaml
users:
  - name: ada
    password: "pbkdf2-sha256$600000$<salt>$<hash>"
    roles: [rocoto-admin]
tokens:
  - id: prometheus
    hash: "sha256$<hash>"
    roles: [rocoto-reader]
    expires: "2027-01-01T00:00:00Z"        # optional
    mbeans: ["java.lang:*", "Catalina:*"]   # optional scope
certificates:                               # used when auth.builtin.mtls.enabled
  - subject: "CN=ops-*,OU=Operations,O=Example,C=DE"   # glob over the RFC 2253 subject DN
    san: "*.ops.example.com"                           # optional glob over SAN entries
    name: ops                                          # optional, default: the CN
    roles: [rocoto-operator]
```

## Metric mapping rules

```yaml
rules:
  - mbean: "java.lang:type=MemoryPool,name=*"   # pattern
    attribute: Usage
    path: used                  # key inside a composite value (optional)
    name: jvm_memory_pool_used_bytes
    type: gauge                 # gauge | counter (counter names must end with _total)
    help: "Used memory per pool"
    labels: { pool: "${name}" } # ${prop} = ObjectName key property
    scale: 1.0                  # multiplier, e.g. 0.001 for ms -> s
```

Without `path`, every numeric key of a composite value becomes a sample with a `key` label.

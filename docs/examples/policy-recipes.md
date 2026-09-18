# Policy recipes

## Goal

Copy-and-paste rules for `policy:` in `rocoto.yaml`, with the reasoning behind them.

## Prerequisites

A running Rocoto, for example [tomcat-builtin-auth](tomcat-builtin-auth.md). Configuration changes
need a redeploy or restart of `rocoto.war`; only `rocoto-users.yaml` is re-read automatically.

## How a decision is made

First match wins:

1. MBean scope of the API token (`mbeans:` of the token) - outside the scope everything is denied
2. built-in deny list (write/exec only, **cannot be overridden**)
3. `deny` rules
4. `allow` rules
5. default: read/list/search for `policy.readRoles`; write/exec denied

Consequences: without any rule Rocoto is read-only. Every write and exec needs an explicit
`allow` rule that names `roles`. A `deny` rule may omit `roles` and then applies to everybody,
including admins. `read` in a rule implies `list` and `search`. `attributes`/`operations` are
globs; omit them to cover all members.

## Files

### 1. Operators may run selected operations

```yaml
policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Threading"
      operations: ["reset*", dumpAllThreads]
```

### 2. Admins may change log levels (java.util.logging)

```yaml
    - roles: [rocoto-admin]
      allow: [exec]
      mbean: "java.util.logging:type=Logging"
      operations: [setLoggerLevel]
```

Logback and Log4j 2 MBeans are on the built-in deny list (their MBeans can load configuration from
URLs); no rule can open them.

### 3. Full control over your own application's MBeans, nothing else

```yaml
    - roles: [rocoto-admin]
      allow: [write, exec]
      mbean: "com.example.demo:*"
```

More selective, as used by the examples:

```yaml
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "com.example.demo:type=Orders"
      operations: [resetCounters, drainQueue]
    - roles: [rocoto-admin]
      allow: [write]
      mbean: "com.example.demo:type=Orders"
      attributes: [MaintenanceMode]
```

### 4. Hide sensitive attributes from everybody

```yaml
    - deny: [read]
      mbean: "java.lang:type=Runtime"
      attributes: [SystemProperties, InputArguments, ClassPath, BootClassPath, LibraryPath]
```

The MBean stays visible, the other attributes stay readable; reading a denied attribute gives
`403`, and "read all attributes" simply leaves it out.

### 5. Hide whole MBeans or domains

```yaml
    - deny: [all]                      # read, list, search, write, exec
      mbean: "com.example.internal:*"
    - roles: [rocoto-reader]           # only for one role
      deny: [read]
      mbean: "Catalina:type=DataSource,*"
```

A role-specific deny applies to every caller that has the role - also to a caller that
additionally has a more powerful one.

### 6. Additional masks

Attribute names and nested keys matching the built-in globs (`*password*`, `*secret*`,
`*credential*`, `*apikey*`, ... - see the [reference](../reference/configuration.md)) are replaced
by `********` on the server. `policy.mask` adds globs; it never removes the built-in ones:

```yaml
policy:
  mask: ["*pin", "*iban*", "connectionUrl"]
```

Masks are matched against names, case-insensitively, wherever they occur - choose them narrow.
`"*Order*"` would mask `OrdersProcessed` and `LastOrder` of the demo MBean alike.

### 7. A different set of reading roles

```yaml
policy:
  readRoles: [rocoto-reader, rocoto-operator, rocoto-admin, monitoring]
```

Setting `readRoles` replaces the default list, so repeat the standard roles you still need. In
`container`/`chain` mode Rocoto asks the application server for every role named here or in a rule.

### 8. Narrow API tokens instead of narrow roles

The scope of a token is part of the token, not of the policy:

```sh
java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader --days 365 \
     --mbeans "java.lang:*;java.nio:*;Catalina:*" --file rocoto-users.yaml
```

Outside its scope such a token gets `403`, and `/metrics` only contains samples from MBeans in scope.

### 9. Notifications and streaming

Subscribing to attribute values needs `read` on those attributes. Receiving JMX notifications is a
`read` on the pseudo attribute `@notifications`:

```yaml
    - roles: [rocoto-reader]
      deny: [read]
      mbean: "java.lang:type=Memory"
      attributes: ["@notifications"]
```

### 10. Audit more

```yaml
audit:
  file: /var/log/rocoto/audit.log
  reads: true             # also record successful reads (volume!)
  execArguments: true     # record operation arguments - they may contain secrets
```

## Commands

Test a rule with two users:

```sh
gc='{"mbean":"java.lang:type=Memory","operation":"gc"}'
curl -s -u rita:demo-password-123 -H 'Content-Type: application/json' -d "$gc" $B/api/v1/exec
curl -s -u otto:demo-password-123 -H 'Content-Type: application/json' -d "$gc" $B/api/v1/exec
curl -s -u ada:demo-password-123 "$B/api/v1/mbeans/java.lang%3Atype%3DRuntime/attributes/SystemProperties"
```

## Expected output

```text
{"status":403,"error":"Access denied by policy"}
{"mbean":"java.lang:type=Memory","operation":"gc","value":null}
{"status":403,"error":"Access denied by policy"}
```

The response never says why; the audit log does:

```json
{"ts":"...","user":"rita","auth":"basic","remote":"...","channel":"api","action":"EXEC","mbean":"java.lang:type=Memory","member":"gc","outcome":"DENIED","reason":"write/exec requires an explicit allow rule"}
{"ts":"...","user":"otto","auth":"basic","remote":"...","channel":"api","action":"EXEC","mbean":"java.lang:type=Memory","member":"gc","outcome":"ALLOWED"}
```

Other reasons: `built-in deny list: ...`, `deny rule for <mbean>`,
`outside the MBean scope of the credential`, `no role with read access`.

Recipes 1 to 7 and 10 were loaded into a running Rocoto (embedded Tomcat) and spot-checked with
the requests above.

### What cannot be allowed

Write and exec on these targets are always denied, whatever the rules say:
`ch.qos.logback.classic:*`, `org.apache.logging.log4j2:*`,
`com.sun.management:type=DiagnosticCommand`, `com.sun.management:type=HotSpotDiagnostic`,
`jdk.management.jfr:*`, `javax.management.loading:*`, operations `getMBeansFromURL`/`addURL` on
any MBean, `JMImplementation:*`, `jboss.as[.expr]:subsystem=elytron,*`,
`jboss.as[.expr]:core-service=management,*`, `jboss.as:core-service=vault`,
`Catalina:type=MBeanFactory`, `Users:*`, `io.rocoto:*`.

### Configuration errors

An allow rule without `roles`, a rule with both or neither of `allow`/`deny`, an unknown operation
name or an invalid ObjectName abort the start-up; Rocoto then answers every request with `503`.
The message in the server log names the rule, e.g.
`policy.rules[2]: allow rules must name the roles they apply to`.

## What to look at next

- [expose-your-own-mxbean](expose-your-own-mxbean.md)
- [jolokia-migration](jolokia-migration.md) - convert an existing `jolokia-access.xml`
- User guide: [Authorization policy](../guide/05-authorization-policy.md)

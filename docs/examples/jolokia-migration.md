# Migrating from Jolokia

## Goal

Replace a Jolokia WAR agent by Rocoto without rewriting the clients on day one: point the existing
Jolokia clients (jolokia-client-java, jolokia.js, Telegraf `jolokia2_agent`, hawtio) to
`/rocoto/jolokia`, give them credentials, and convert `jolokia-access.xml` into a Rocoto policy.

## Prerequisites

- A running Rocoto (`compat.jolokia.enabled: true` is the default), e.g.
  [tomcat-builtin-auth](tomcat-builtin-auth.md)
- `rocoto-tools.jar` (`mvn install -DskipTests` in the repository root)
- One Rocoto user or token per client, normally with the role `rocoto-reader`

## What changes for a client

| | Jolokia agent | Rocoto |
|---|---|---|
| URL | `https://host/jolokia` | `https://host/rocoto/jolokia` |
| Authentication | optional | mandatory (Basic, API token, mTLS, JWT) |
| `write`, `exec` | allowed unless restricted | denied unless an `allow` rule exists |
| POST bodies | any content type | must be `Content-Type: application/json` (otherwise `415`) |
| Proxy mode (`target`) | available | answered with status `403` |
| JSONP (`callback`) | available | `400` |
| Stack traces in errors | optional | never |
| Secrets in values | returned | masked as `********` |
| `jolokia-access.xml` | read by the agent | **not read** - convert it (below) |

The protocol version reported is `7.2`. `write`/`exec` as GET URLs are accepted from non-browser
clients only and can be switched off with `compat.jolokia.allowGetMutations: false`; see the
[user guide](../guide/09-jolokia-compat-and-migration.md) for the details of the compatibility
layer.

## Files

### 1. Convert the policy

`jolokia-access.xml` (input):

```xml
<?xml version="1.0" encoding="utf-8"?>
<restrict>
  <remote>
    <host>10.0.0.0/16</host>
  </remote>
  <commands>
    <command>read</command>
    <command>list</command>
    <command>search</command>
    <command>exec</command>
  </commands>
  <allow>
    <mbean>
      <name>java.lang:type=Memory</name>
      <operation>gc</operation>
    </mbean>
    <mbean>
      <name>com.example.demo:type=Orders</name>
      <attribute mode="read">*</attribute>
      <operation>resetCounters</operation>
      <operation>drainQueue</operation>
    </mbean>
  </allow>
  <deny>
    <mbean>
      <name>java.lang:type=Runtime</name>
      <attribute>SystemProperties</attribute>
      <attribute>InputArguments</attribute>
    </mbean>
  </deny>
</restrict>
```

### 2. Clients

**jolokia-client-java** (`JolokiaClient`, `J4pClient` before 2.4) - the client Rocoto's integration test `JolokiaClientIT` uses:

```java
// jolokia-client-java 2.4 and later; versions up to 2.3 call these classes J4pClient, J4pReadRequest, ...
JolokiaClient client = new JolokiaClientBuilder()
        .url("https://app.example.com:8443/rocoto/jolokia")
        .user("monitor")
        .password(System.getenv("ROCOTO_PASSWORD"))
        .connectionTimeout(5000)
        .build();

JolokiaReadResponse response = client.execute(
        new JolokiaReadRequest("java.lang:type=Memory", "HeapMemoryUsage"));
Map<String, Long> usage = response.getValue();
```

Do not configure a `JolokiaTargetConfig` (proxy mode).

**jolokia.js**:

```javascript
const jolokia = new Jolokia({
  url: "https://app.example.com:8443/rocoto/jolokia",
  username: "monitor",
  password: password,
  method: "post"            // JSON POST; JSONP is not available
});

jolokia.request(
  { type: "read", mbean: "com.example.demo:type=Orders", attribute: "QueueDepth" },
  { success: (r) => console.log(r.value), error: (r) => console.warn(r.status, r.error) }
);
```

A page that is not served from Rocoto's own origin must be listed:

```yaml
cors:
  allowedOrigins: ["https://dashboard.example.com"]     # explicit origins only, '*' is rejected
```

**Telegraf** (`inputs.jolokia2_agent`; `jolokia2_proxy` cannot work - there is no proxy mode):

```toml
[[inputs.jolokia2_agent]]
  urls = ["https://app.example.com:8443/rocoto/jolokia"]
  username = "telegraf"
  password = "${ROCOTO_TELEGRAF_PASSWORD}"
  tls_ca = "/etc/telegraf/internal-ca.pem"

  [[inputs.jolokia2_agent.metric]]
    name  = "jvm_memory"
    mbean = "java.lang:type=Memory"
    paths = ["HeapMemoryUsage", "NonHeapMemoryUsage"]

  [[inputs.jolokia2_agent.metric]]
    name     = "demo_orders"
    mbean    = "com.example.demo:type=Orders"
    paths    = ["OrdersProcessed", "QueueDepth"]
```

**hawtio** - in the *Connect* tab add a connection with scheme `https`, your host and port, and the
path `/rocoto/jolokia`; hawtio asks for the user name and password. If hawtio connects through its
server-side proxy, the Rocoto host must be on hawtio's proxy allow list; if your hawtio talks to
Rocoto directly from the browser, add hawtio's origin to `cors.allowedOrigins`. Browsing works
for a `rocoto-reader`; plugin actions that write or execute need allow rules.

## Commands

```sh
java -jar rocoto-tools/target/rocoto-tools.jar policy convert jolokia-access.xml > policy-draft.yaml

# a user for each migrated client
java -jar rocoto-tools/target/rocoto-tools.jar user add telegraf --roles rocoto-reader --file rocoto-users.yaml

# smoke test with curl
curl -s -u rita:demo-password-123 "$B/jolokia/version"
curl -s -u rita:demo-password-123 "$B/jolokia/read/com.example.demo:type=Orders/QueueDepth"
curl -s -u otto:demo-password-123 -H 'Content-Type: application/json' \
     -d '{"type":"exec","mbean":"com.example.demo:type=Orders","operation":"drainQueue(int)","arguments":[3]}' $B/jolokia
```

## Expected output

`policy convert` for the file above (actual output of the tool):

```yaml
# Converted from jolokia-access.xml - REVIEW BEFORE USE.
# Rocoto is read-only by default and its built-in deny list always applies.
# NOTE: Jolokia allowed exec on every MBean that is not denied. Rocoto has no such blanket permission: only the MBeans listed in <allow> were converted. Add allow rules for anything else you really need.
# NOTE: <remote> host restrictions are not part of Rocoto's policy. Restrict network access in the application server or firewall; Rocoto authenticates every caller instead.
policy:
  rules:
    - roles: [rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: ["gc"]
    - roles: [rocoto-admin]
      allow: [exec]
      mbean: "com.example.demo:type=Orders"
      operations: ["resetCounters", "drainQueue"]
    - deny: [read, write]
      mbean: "java.lang:type=Runtime"
      attributes: ["SystemProperties", "InputArguments"]
```

Jolokia has no users or roles, so everything it allowed to anyone is granted to `rocoto-admin`
only. Review the draft, widen `roles` where operators need an operation, and paste it into
`rocoto.yaml`.

The smoke test (captured from an embedded Tomcat running `rocoto.war` and the demo application):

```text
{"request":{"type":"version"},"value":{"agent":"rocoto-0.1.1","protocol":"7.2","info":{"product":"Apache Tomcat","vendor":"Rocoto","version":"Apache Tomcat/10.1.34"},"config":{"agentDescription":"Rocoto Jolokia compatibility","agentType":"servlet"}},"timestamp":1789761531,"status":200}

{"request":{"type":"read","mbean":"com.example.demo:type=Orders","attribute":"QueueDepth"},"value":2,"timestamp":1789761531,"status":200}

{"request":{"type":"exec","mbean":"com.example.demo:type=Orders","operation":"drainQueue(int)","arguments":[3]},"value":3,"timestamp":1789762904,"status":200}
```

As in Jolokia, failures of single requests are reported inside the JSON answer while the HTTP
status stays `200`:

```text
{"request":{...,"target":{"url":"service:jmx:rmi:///jndi/rmi://other:9999/jmxrmi"}},"error_type":"java.lang.SecurityException","error":"java.lang.SecurityException : Proxy mode (target) is not supported by Rocoto","status":403}
```

Authentication failures are real HTTP `401` responses. The Java client, jolokia.js, Telegraf and
hawtio snippets were not executed for this page; the Java client is covered by Rocoto's
integration tests.

## Migration checklist

1. Deploy Rocoto next to the Jolokia agent; create users/tokens; convert and review the policy.
2. Move one client at a time to `/rocoto/jolokia` with credentials. Watch the audit log for
   `DENIED` entries - they show which allow rules are still missing.
3. Consider the native replacements: `/rocoto/metrics` instead of Telegraf polling
   ([prometheus-grafana](prometheus-grafana.md)), SSE/WebSocket instead of Jolokia notifications
   ([streaming-clients](streaming-clients.md)), `/api/v1` for new code ([rest-clients](rest-clients.md)).
4. Undeploy the Jolokia agent. When nothing uses the compatibility endpoint any more, switch it
   off with `compat.jolokia.enabled: false`.

## What to look at next

- User guide: [Jolokia compatibility and migration](../guide/09-jolokia-compat-and-migration.md)
- [policy-recipes](policy-recipes.md)

# 8. REST API reference (`/api/v1`)

This chapter documents every endpoint of Rocoto's native API. All paths are relative to the context
root of the WAR, which is `/rocoto` by default, so the full URL of the first example is
`https://host:8443/rocoto/api/v1/server`.

The examples use HTTP Basic (`-u ada`) or an API token:

```bash
export ROCOTO=https://localhost:8443/rocoto
export TOKEN=rct_prometheus_...          # created with rocoto-tools, see chapter 3
curl -s -H "Authorization: Bearer $TOKEN" $ROCOTO/api/v1/server
```

A machine-readable description of the same API is served at `GET /api/v1/openapi.json`
(OpenAPI 3.0.3; `?format=yaml` for YAML).

## General rules

- Every request needs credentials. The only exception is `GET /api/v1/health/live`, and only if
  `health.allowAnonymous: true` is configured.
- Request bodies of `POST` and `PUT` must be sent with `Content-Type: application/json`. Anything
  else is answered with `415`. This is a CSRF defense, not a formality: HTML forms cannot send
  that content type.
- Request bodies are limited to 1 MiB (`413`).
- Requests with an `Origin` header are rejected with `403` unless the origin is the server itself
  or listed in `cors.allowedOrigins`. Requests without `Origin` but with a `Sec-Fetch-Site`
  header other than `same-origin` or `none` - a browser loading an API URL from a foreign page
  through `<img>`, `<script>` or a link - are rejected with `403 Cross-site request denied`.
- Responses are JSON (`application/json`, UTF-8) unless another format is requested with
  `?format=xml|yaml|toml|html|csv|text|table` or the `Accept` header - see
  [15 - Output formats](15-output-formats.md). This chapter shows JSON. All responses - including
  all error responses, the `503` of a failed start-up too - carry `Cache-Control: no-store`,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, a restrictive
  `Content-Security-Policy` and `Referrer-Policy: no-referrer`.
- Reads are allowed for the roles in `policy.readRoles`. `write` and `exec` always need an explicit
  allow rule, see [chapter 5](05-authorization-policy.md).
- Values of attributes whose name matches a mask (`*password*`, `*secret*`, ...) are returned as
  `"********"`.

## Two styles, one implementation

| | Resource style | RPC style |
|---|---|---|
| Shape | ObjectName inside the URL path | ObjectName inside a JSON body |
| Read | `GET /mbeans/{name}/attributes/{attribute}` | `POST /read` |
| Write | `PUT /mbeans/{name}/attributes/{attribute}` | `POST /write` |
| Exec | `POST /mbeans/{name}/operations/{operation}` | `POST /exec` |
| Search | `GET /mbeans?pattern=...` | `POST /search` |
| Describe | `GET /mbeans/{name}` | `POST /describe` |
| Several requests at once | - | `POST /bulk` |

Both styles call the same code and return the same JSON.

### When to prefer the RPC style

In the resource style the ObjectName is **one URL-encoded path segment**. That works for most names
(`java.lang:type=Memory` may even be sent unencoded), but it breaks for names that contain a `/`.
Tomcat uses such names for web applications, for example
`Catalina:type=Manager,host=localhost,context=/rocoto`. The slash has to be sent as `%2F`, and
servlet containers treat encoded slashes in paths as suspicious: Tomcat rejects them with `400` by
default, and reverse proxies often normalize or refuse them. The RPC style carries the name in the
body and has no escaping problem at all. Use it whenever ObjectNames come from data rather than
from a constant in your code.

## Endpoint overview

| Method | Path | Purpose |
|---|---|---|
| GET | `/server` | Rocoto version, detected server, the caller's identity |
| GET | `/health` | Status, warnings, counters (authenticated) |
| GET | `/health/live` | Liveness, `{"status":"UP"}` |
| GET | `/mbeans?pattern=` | Search ObjectNames |
| GET | `/mbeans/{name}` | Describe one MBean |
| GET | `/mbeans/{name}/attributes` | Read all attributes |
| GET | `/mbeans/{name}/attributes/{attribute}` | Read one attribute |
| PUT | `/mbeans/{name}/attributes/{attribute}` | Write one attribute |
| POST | `/mbeans/{name}/operations/{operation}` | Invoke an operation |
| POST | `/search`, `/describe`, `/read`, `/write`, `/exec` | RPC style |
| POST | `/bulk` | Several RPC requests, per-item status |
| GET | `/resources`, `/resources/{kind}` | Normalized server resources |
| GET | `/schema?mbean=` | JSON Schema of an MBean's attributes |
| GET | `/openapi.json` | OpenAPI document |
| GET | `/subscribe` | Server-sent events, see [chapter 7](07-streaming.md) |

Any other method than `GET`, `PUT`, `POST` yields `405`.

## Server and health

### `GET /server`

```bash
curl -s -u ada $ROCOTO/api/v1/server
```

```json
{
  "rocoto": { "version": "0.1.0", "authMode": "builtin" },
  "server": {
    "adapter": "wildfly",
    "product": "WildFly",
    "version": "30.0.1.Final",
    "serverState": "running",
    "launchType": "STANDALONE"
  },
  "caller": {
    "name": "ada",
    "roles": ["rocoto-admin"],
    "authenticatedBy": "basic"
  }
}
```

`server` always contains `adapter` (`wildfly`, `tomcat`, `payara` or `generic`); the other keys
depend on the adapter and on what the caller may read. `authenticatedBy` is one of `basic`,
`token`, `jwt`, `mtls`, `container`. The endpoint is the easiest way to check which roles Rocoto
sees for a login.

### `GET /health`

```json
{
  "status": "WARN",
  "version": "0.1.0",
  "adapter": "tomcat",
  "visibleMBeans": 187,
  "activeSubscriptions": 2,
  "warnings": ["the HTTPS listener does not staple an OCSP response"]
}
```

`status` is `UP` when `warnings` is empty, otherwise `WARN`. Possible warnings: an empty built-in
user store, `auth.allowInsecureTransport` being enabled, and the findings of the TLS self probe
([chapter 13](13-tls-and-ocsp.md)). `visibleMBeans` counts the MBeans visible *to the caller*. The
HTTP status is `200` in both cases.

### `GET /health/live`

Returns `{"status":"UP"}` as soon as Rocoto has started. It needs credentials unless
`health.allowAnonymous: true` is set. If start-up failed, this endpoint answers `503` like every
other one.

## Search

```bash
curl -s -u ada "$ROCOTO/api/v1/mbeans?pattern=java.lang:type=Mem*"
curl -s -u ada -H 'Content-Type: application/json' \
     -d '{"pattern":"java.lang:type=Mem*"}' $ROCOTO/api/v1/search
```

```json
{
  "pattern": "java.lang:type=Mem*",
  "mbeans": ["java.lang:type=Memory"]
}
```

Without `pattern` all MBeans (`*:*`) are returned. Names are canonical ObjectNames, sorted. MBeans
the caller may not list are left out, so a caller without any read role gets an empty list rather
than an error.

## Describe

```bash
curl -s -u ada $ROCOTO/api/v1/mbeans/java.lang%3Atype%3DMemory
curl -s -u ada -H 'Content-Type: application/json' \
     -d '{"mbean":"java.lang:type=Memory"}' $ROCOTO/api/v1/describe
```

```json
{
  "mbean": "java.lang:type=Memory",
  "className": "sun.management.MemoryImpl",
  "description": "Information on the management interface of the MBean",
  "attributes": {
    "HeapMemoryUsage": {
      "type": "javax.management.openmbean.CompositeData",
      "openType": "java.lang.management.MemoryUsage",
      "description": "HeapMemoryUsage",
      "readable": true,
      "writable": false
    },
    "Verbose": { "type": "boolean", "openType": "java.lang.Boolean",
                 "description": "Verbose", "readable": true, "writable": true }
  },
  "operations": [
    { "name": "gc", "description": "gc", "returnType": "void", "impact": "UNKNOWN",
      "parameters": [] }
  ],
  "notifications": [
    { "name": "javax.management.Notification", "description": "Memory Notification",
      "types": ["java.management.memory.threshold.exceeded",
                "java.management.memory.collection.threshold.exceeded"] }
  ]
}
```

- Attributes the caller may not read are removed. Masked attributes carry `"masked": true`.
- `openType` is present only for open MBeans / MXBeans.
- `impact` is `INFO`, `ACTION`, `ACTION_INFO` or `UNKNOWN`.
- Each parameter has `name`, `type`, `description`. The `type` values are what you pass in
  `signature` for overloaded operations.
- A pattern instead of a concrete name yields `400`.

## Read

### One attribute

```bash
curl -s -u ada $ROCOTO/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage
curl -s -u ada -H 'Content-Type: application/json' \
     -d '{"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage"}' $ROCOTO/api/v1/read
```

```json
{
  "mbean": "java.lang:type=Memory",
  "attribute": "HeapMemoryUsage",
  "value": { "committed": 268435456, "init": 268435456, "max": 4294967296, "used": 84152320 }
}
```

### Several or all attributes

`POST /read` accepts `attribute` (string) and/or `attributes` (array). With exactly one attribute
the single-value form above is returned; otherwise the response has a `values` object:

```bash
curl -s -u ada -H 'Content-Type: application/json' \
     -d '{"mbean":"java.lang:type=Threading","attributes":["ThreadCount","PeakThreadCount"]}' \
     $ROCOTO/api/v1/read
```

```json
{
  "mbean": "java.lang:type=Threading",
  "values": { "ThreadCount": 42, "PeakThreadCount": 57 }
}
```

Without any attribute (or `GET /mbeans/{name}/attributes`) all readable attributes are returned.
Attributes the caller may not read are skipped silently. Attributes that fail to read are skipped
too and reported under `errors`:

```json
{
  "mbean": "java.lang:type=Runtime",
  "errors": { "BootClassPath": "Not supported by the MBean" },
  "values": { "VmName": "OpenJDK 64-Bit Server VM", "Uptime": 5234871 }
}
```

When you name attributes explicitly, a missing attribute is a `404` and a denied one a `403` for
the whole request.

### Reading with an ObjectName pattern

If `mbean` is a pattern, the result is grouped per MBean:

```bash
curl -s -u ada -H 'Content-Type: application/json' \
     -d '{"mbean":"java.lang:type=MemoryPool,*","attribute":"Name"}' $ROCOTO/api/v1/read
```

```json
{
  "pattern": "java.lang:name=*,type=MemoryPool",
  "values": {
    "java.lang:name=G1 Eden Space,type=MemoryPool": { "Name": "G1 Eden Space" },
    "java.lang:name=G1 Old Gen,type=MemoryPool": { "Name": "G1 Old Gen" }
  }
}
```

Pattern reads never fail because of a single MBean: inaccessible or failing attributes are left
out, and MBeans without any remaining value are omitted. No match gives an empty `values` object
with status `200`. Note that `pattern` and the keys are *canonical* names (key properties sorted).

A pattern that matches more than `api.maxPatternMBeans` MBeans (default 1000; counted after the
policy has filtered the search result) is refused with `413` instead of reading half the server:
`The pattern matches 4210 MBeans; at most 1000 can be read at once (api.maxPatternMBeans)`.
Narrow the pattern or raise the limit.

## Write

Requires an allow rule with `allow: [write]` for the MBean and attribute.

```bash
curl -s -u ada -X PUT -H 'Content-Type: application/json' -d '{"value":true}' \
     $ROCOTO/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/Verbose
curl -s -u ada -H 'Content-Type: application/json' \
     -d '{"mbean":"java.lang:type=Memory","attribute":"Verbose","value":true}' $ROCOTO/api/v1/write
```

```json
{ "mbean": "java.lang:type=Memory", "attribute": "Verbose", "previousValue": false }
```

The key `value` is mandatory (`"value": null` is allowed for non-primitive types).
`previousValue` is `null` if the attribute is not readable and `"********"` if it is masked.
Writing a read-only attribute or a value that cannot be converted gives `400`.

## Exec

Requires an allow rule with `allow: [exec]` for the MBean and operation.

```bash
curl -s -u otto -H 'Content-Type: application/json' -d '{}' \
     $ROCOTO/api/v1/mbeans/java.lang%3Atype%3DMemory/operations/gc
curl -s -u otto -H 'Content-Type: application/json' \
     -d '{"mbean":"java.util.logging:type=Logging","operation":"getLoggerLevel","arguments":["io.rocoto"]}' \
     $ROCOTO/api/v1/exec
```

```json
{ "mbean": "java.lang:type=Memory", "operation": "gc", "value": null }
```

```json
{ "mbean": "java.util.logging:type=Logging", "operation": "getLoggerLevel", "value": "FINE" }
```

Body fields:

| Field | Type | Meaning |
|---|---|---|
| `mbean`, `operation` | string | RPC style only; in the resource style they are in the path |
| `arguments` | array | Optional, default `[]` |
| `signature` | array of strings | Optional; JMX type names exactly as shown by describe |

The resource style still needs a JSON object as body, `{}` at minimum.

### Overloaded operations

Without `signature` Rocoto picks the operation by name and argument count. If that is ambiguous
(here: `getThreadInfo` of `java.lang:type=Threading` called with one argument) the answer is `400`:

```json
{
  "status": 400,
  "error": "Operation getThreadInfo is overloaded; specify the signature. Candidates: [[long], [[J]]"
}
```

Pass the signature to select one:

```bash
curl -s -u otto -H 'Content-Type: application/json' -d '{
  "mbean": "java.lang:type=Threading",
  "operation": "getThreadInfo",
  "signature": ["long", "int"],
  "arguments": [1, 5]
}' $ROCOTO/api/v1/exec
```

Array types use the JVM notation: `[J` is `long[]`, `[Ljava.lang.String;` is `String[]`.

## Bulk

`POST /bulk` takes a JSON array of RPC requests, or an object `{"requests": [...]}`. Each item has
a `type` (`read`, `write`, `exec`, `search`, `describe`) plus the fields of that RPC call. Items
are executed in order. **One failing item does not fail the others**: every result carries its own
`status`.

```bash
curl -s -u rita -H 'Content-Type: application/json' -d '[
  {"type":"read","mbean":"java.lang:type=Runtime","attribute":"VmName"},
  {"type":"exec","mbean":"java.lang:type=Memory","operation":"gc"},
  {"type":"read","mbean":"java.lang:type=Runtime","attribute":"Missing"}
]' $ROCOTO/api/v1/bulk
```

```json
{
  "results": [
    { "status": 200, "mbean": "java.lang:type=Runtime", "attribute": "VmName",
      "value": "OpenJDK 64-Bit Server VM" },
    { "status": 403, "error": "Access denied by policy" },
    { "status": 404, "error": "No such attribute: Missing" }
  ]
}
```

The HTTP status of a bulk call is `200` as long as the envelope is valid. At most
`api.maxBulkRequests` (default 100) items are accepted; more yield `413`.

## Resources

A server-independent view of the things operators look at first. Rocoto's adapter translates the
server's own MBeans into common field names.

```bash
curl -s -u ada $ROCOTO/api/v1/resources
```

```json
{ "kinds": ["datasources", "threadpools", "deployments", "sessions"] }
```

```bash
curl -s -u ada $ROCOTO/api/v1/resources/datasources
```

```json
{
  "kind": "datasources",
  "server": "wildfly",
  "items": [
    {
      "name": "ExampleDS",
      "mbean": "jboss.as:data-source=ExampleDS,subsystem=datasources",
      "jndiName": "java:jboss/datasources/ExampleDS",
      "enabled": true,
      "activeConnections": 3,
      "idleConnections": 7,
      "maxConnections": 20,
      "availableConnections": 17,
      "waitingThreads": 0,
      "maxUsedConnections": 9
    }
  ]
}
```

Every item has `name` and `mbean`; the `mbean` value can be fed directly into `POST /read`. All
other fields appear only if the server provides them *and* the caller may read them. Fields are
read through the normal pipeline with the caller's identity, so policy, token scope and masking
apply. An empty `items` list usually means statistics are disabled on the server, see
[chapter 12](12-troubleshooting.md). An unknown kind gives `404`.

## Schema

`GET /schema?mbean=<ObjectName>` returns a JSON Schema (draft 2020-12) for the readable attributes
of one MBean, useful for code generators and UI forms.

```bash
curl -s -u ada "$ROCOTO/api/v1/schema?mbean=java.lang:type=Memory"
```

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "java.lang:type=Memory",
  "description": "Information on the management interface of the MBean",
  "type": "object",
  "properties": {
    "HeapMemoryUsage": { "type": "object", "description": "HeapMemoryUsage",
                         "readOnly": true,
                         "x-jmx-type": "javax.management.openmbean.CompositeData" },
    "ObjectPendingFinalizationCount": { "type": "integer", "description": "...",
                                        "readOnly": true, "x-jmx-type": "int" },
    "Verbose": { "type": "boolean", "description": "Verbose", "readOnly": false,
                 "x-jmx-type": "boolean" }
  }
}
```

`long` attributes are typed `["integer","string"]` and floating point attributes
`["number","string"]` because of the number rules below. Types Rocoto does not know get no `type`
keyword at all.

## Errors

Every error of the native API has the same body:

```json
{ "status": 403, "error": "Access denied by policy" }
```

Stack traces and exception class names of the server are never returned.

| Status | Meaning | Typical `error` text |
|---|---|---|
| 400 | Malformed JSON, invalid ObjectName, pattern where a concrete name is required, missing field, value not convertible, read-only attribute, ambiguous overload, wrong argument count | `Invalid ObjectName: foo`, `'mbean' is required`, `Expected an integer for int` |
| 401 | No or wrong credentials. `WWW-Authenticate` lists the accepted schemes (in `container` and `chain` mode including `auth.container.challenge`) | `Authentication required` |
| 403 | Denied by policy, token scope or built-in deny list; credentials over plain HTTP; foreign `Origin`; cross-site browser request without `Origin` (`Sec-Fetch-Site` other than `same-origin`/`none`); the application server itself refused (`SecurityException`) | `Access denied by policy`, `Credentials are only accepted over TLS`, `Cross-origin request denied`, `Cross-site request denied`, `The application server denied access to this MBean` |
| 404 | Unknown path, MBean, attribute, operation, resource kind or bulk `type` | `No MBean registered as x:type=Y` |
| 405 | Method other than GET, PUT, POST | `Method not allowed` |
| 413 | Body larger than 1 MiB, more than `api.maxBulkRequests` items, or a pattern read matching more than `api.maxPatternMBeans` MBeans | `At most 100 requests per bulk call`, `The pattern matches 4210 MBeans; at most 1000 can be read at once (api.maxPatternMBeans)` |
| 406 | `format` names an unknown format or one not enabled in `api.formats` (checked before the request is executed) | `Unsupported format 'pdf'; available: [json, xml, ...]` |
| 415 | POST/PUT without `Content-Type: application/json` | `Request bodies must be sent as application/json` |
| 429 | Login locked after repeated failures (`Retry-After: 60`), or subscription limits reached | `Too many failed login attempts` |
| 500 | The MBean threw an exception, or an unexpected error. Unexpected errors are logged on the server and reported only as `Internal error` | `IllegalStateException: pool is closed` |
| 503 | Rocoto failed to start (invalid configuration). Fails closed | `Rocoto is not initialised; see server log` |

The text of a policy denial is deliberately generic. The precise reason (which rule, deny list,
token scope) is written to the audit log only.

## Type mapping: JMX to JSON

| Java / JMX type | JSON | Notes |
|---|---|---|
| `boolean` | boolean | |
| `byte`, `short`, `int` | number | |
| `long` | number, or **string** if the magnitude exceeds 2^53 - 1 (9007199254740991) | JavaScript and many JSON libraries lose precision beyond that |
| `BigInteger`, `BigDecimal` | string | always |
| `float`, `double` | number; `"NaN"`, `"Infinity"`, `"-Infinity"` as strings | JSON has no literal for them |
| `String`, `char`, `Enum` | string | enum constant name |
| `byte[]` | string, base64 | |
| `Date`, `Calendar` | string, ISO-8601 instant, e.g. `2026-03-01T12:00:00Z` | UTC |
| `java.time.*` | string | `toString()` form |
| `ObjectName` | string, canonical name | |
| `CompositeData` | object | keys matching a mask are replaced by `"********"` |
| `TabularData` | **array of row objects** | one object per row, index columns included |
| `Map` | object | keys converted with `toString()`, masks apply |
| arrays, `Collection` | array | |
| `null`, `void` | `null` | |
| anything else | string, `toString()` | Rocoto never walks arbitrary beans by reflection |

Nesting is followed to a depth of 16 and collections are cut at 10,000 elements; whatever lies
deeper is rendered with `toString()`.

Example of tabular data (`java.lang:type=Runtime`, attribute `SystemProperties`, shortened):

```json
{ "value": [ { "key": "java.vendor", "value": "Eclipse Adoptium" },
             { "key": "file.encoding", "value": "UTF-8" } ] }
```

The Jolokia compatibility endpoint uses Jolokia's conventions instead (plain numbers, nested maps
for tabular data), see [chapter 9](09-jolokia-compat-and-migration.md).

## Argument conversion: JSON to Java

Values for `write` and arguments for `exec` are converted according to the type declared in the
MBean's metadata. The set of target types is **closed**. Rocoto never instantiates a class named
by the client; a parameter of any other type yields `400 Unsupported parameter type ...`.

| Declared type | Accepted JSON |
|---|---|
| `java.lang.String`, `CharSequence` | any scalar |
| `boolean`, `Boolean` | `true`/`false`, or the strings `"true"`/`"false"` |
| `byte`, `short`, `int`, `long` and wrappers | integral number, or a string containing one; range checked |
| `float`, `double` and wrappers, `BigDecimal` | number or numeric string |
| `BigInteger` | integral number or string |
| `char`, `Character` | string of length 1 |
| `java.util.Date` | ISO-8601 instant string or epoch milliseconds |
| `javax.management.ObjectName` | string |
| `byte[]` | base64 string (or array of numbers) |
| other arrays (`[I`, `[Ljava.lang.String;`, nested) | array; elements converted recursively |
| `List`, `Collection`, `ArrayList`, `Set`, `HashSet`, `LinkedHashSet` | array, elements passed as parsed |
| `Map`, `HashMap`, `LinkedHashMap` | object |
| `java.lang.Object` | the parsed JSON value as is |
| `CompositeData` (open type known) | object; all keys must belong to the composite type |
| `TabularData` (open type known) | array of row objects |

Strings are accepted for numbers so that values Rocoto emitted as strings (large `long`,
`BigInteger`) can be sent back unchanged. `null` is rejected for primitive types.

## Client snippets

### curl

```bash
curl -sS --fail-with-body -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
     -d '{"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage"}' \
     https://app.example.com:8443/rocoto/api/v1/read
```

### Java (`java.net.http`)

```java
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder(URI.create("https://app.example.com:8443/rocoto/api/v1/read"))
        .header("Authorization", "Bearer " + System.getenv("ROCOTO_TOKEN"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
                "{\"mbean\":\"java.lang:type=Memory\",\"attribute\":\"HeapMemoryUsage\"}"))
        .build();
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
if (response.statusCode() != 200) {
    throw new IllegalStateException(response.body());   // {"status":...,"error":"..."}
}
```

### Python (`requests`)

```python
import os, requests

s = requests.Session()
s.headers["Authorization"] = f"Bearer {os.environ['ROCOTO_TOKEN']}"
r = s.post("https://app.example.com:8443/rocoto/api/v1/read",
           json={"mbean": "java.lang:type=Memory", "attribute": "HeapMemoryUsage"}, timeout=10)
r.raise_for_status()
print(r.json()["value"]["used"])
```

`json=` sets the required `Content-Type` header.

### JavaScript (`fetch`)

```javascript
const res = await fetch("https://app.example.com:8443/rocoto/api/v1/bulk", {
  method: "POST",
  headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json" },
  body: JSON.stringify([
    { type: "read", mbean: "java.lang:type=Memory", attribute: "HeapMemoryUsage" },
    { type: "read", mbean: "java.lang:type=Threading", attribute: "ThreadCount" }
  ])
});
const { results } = await res.json();
for (const r of results) {
  if (r.status !== 200) console.warn(r.error);
}
```

From a browser this only works if the page's origin is listed in `cors.allowedOrigins` (or the
page is served from the same origin). Remember that a `long` may arrive as a string: use
`BigInt(value)` when it does.

## Next

[9. Jolokia compatibility and migration](09-jolokia-compat-and-migration.md)

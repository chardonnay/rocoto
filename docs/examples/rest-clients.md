# REST clients

## Goal

Call the REST API from `curl`, Java, Python and Node.js: identify the caller, read, search,
describe, write, exec, bulk - and handle errors properly.

Runnable clients: [`examples/clients`](../../examples/clients/README.md). The complete sources
are listed on the [streaming-clients](streaming-clients.md) page (the same programs also stream).

## Prerequisites

- A running Rocoto with the demo application, e.g. [tomcat-builtin-auth](tomcat-builtin-auth.md)
- Credentials: below the demo users of that example (`demo-password-123`, demo only)

## The API in one table

| Request | Purpose |
|---------|---------|
| `GET /api/v1/server` | Rocoto version, server product, the caller and its roles |
| `GET /api/v1/health`, `GET /api/v1/health/live` | status and warnings; liveness |
| `GET /api/v1/mbeans?pattern=java.lang:*` | search |
| `GET /api/v1/mbeans/{name}` | describe attributes, operations, notifications |
| `GET /api/v1/mbeans/{name}/attributes[/{attribute}]` | read all / one |
| `PUT /api/v1/mbeans/{name}/attributes/{attribute}` with `{"value": ...}` | write |
| `POST /api/v1/mbeans/{name}/operations/{operation}` with `{"arguments": [...], "signature": [...]}` | exec |
| `POST /api/v1/read`, `/write`, `/exec`, `/search`, `/describe` | the same, RPC style: ObjectName in the JSON body |
| `POST /api/v1/bulk` | array of RPC requests, each with a `type` |
| `GET /api/v1/resources/{datasources,threadpools,deployments,sessions}` | normalized server view |
| `GET /api/v1/schema?mbean=...` | JSON Schema of an MBean's attributes |
| `GET /api/v1/openapi.json` | OpenAPI 3 document of all this |

`{name}` is the URL-encoded ObjectName as **one** path segment
(`java.lang%3Atype%3DMemory`). Some servers reject encoded slashes in paths - ObjectNames that
contain `/` (Tomcat contexts) are best addressed RPC style.

## Files

### curl

```sh
B=http://localhost:8080/rocoto
J='Content-Type: application/json'

curl -s -u rita:demo-password-123 "$B/api/v1/mbeans?pattern=com.example.*:*"
curl -s -u rita:demo-password-123 "$B/api/v1/mbeans/com.example.demo%3Atype%3DOrders"
curl -s -u rita:demo-password-123 "$B/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage"
curl -s -u rita:demo-password-123 -H "$J" -d '{"mbean":"java.lang:type=MemoryPool,*","attribute":"Name"}' $B/api/v1/read
curl -s -u otto:demo-password-123 -H "$J" -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' $B/api/v1/exec
curl -s -u otto:demo-password-123 -H "$J" -d '{"arguments":[5]}' "$B/api/v1/mbeans/com.example.demo%3Atype%3DOrders/operations/drainQueue"
curl -s -u ada:demo-password-123 -H "$J" -X PUT -d '{"value":true}' "$B/api/v1/mbeans/com.example.demo%3Atype%3DOrders/attributes/MaintenanceMode"
curl -s -u rita:demo-password-123 -H "$J" $B/api/v1/bulk -d '[
  {"type":"read","mbean":"com.example.demo:type=Orders","attribute":"QueueDepth"},
  {"type":"exec","mbean":"com.example.demo:type=Orders","operation":"resetCounters"},
  {"type":"read","mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage"}]'
```

For overloaded operations add `"signature":["int"]` (the JMX parameter types) to pick one.

### Java, Python, Node.js

- [`RocotoClientExample.java`](../../examples/clients/java/src/main/java/com/example/rocoto/client/RocotoClientExample.java) - `java.net.http.HttpClient`, no dependencies
- [`rocoto_client.py`](../../examples/clients/python/rocoto_client.py) - `requests`
- [`rocoto-client.mjs`](../../examples/clients/js/rocoto-client.mjs) - built-in `fetch`

The essential part in each language:

```java
HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/read"))
        .header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json")            // mandatory, otherwise 415
        .POST(HttpRequest.BodyPublishers.ofString("{\"mbean\":\"java.lang:type=Memory\",\"attribute\":\"HeapMemoryUsage\"}"))
        .build();
HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
```

```python
session = requests.Session()
session.headers["Authorization"] = f"Bearer {token}"
r = session.post(f"{base}/api/v1/read", json={"mbean": "java.lang:type=Memory", "attribute": "HeapMemoryUsage"}, timeout=30)
r.raise_for_status()
print(r.json()["value"]["used"])
```

```javascript
const r = await fetch(`${base}/api/v1/read`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  body: JSON.stringify({ mbean: 'java.lang:type=Memory', attribute: 'HeapMemoryUsage' }),
});
if (!r.ok) throw new Error(`HTTP ${r.status}: ${(await r.json()).error}`);
console.log((await r.json()).value.used);
```

## Commands

```sh
export ROCOTO_URL=http://localhost:8080/rocoto ROCOTO_USER=otto ROCOTO_PASSWORD=demo-password-123   # demo only
cd examples/clients/java && mvn -q package && java -jar target/rocoto-client-example.jar
```

## Expected output

```text
{"pattern":"com.example.*:*","mbeans":["com.example.demo:type=Orders"]}

{"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage","value":{"committed":406847488,"init":402653184,"max":6442450944,"used":47611264}}

{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","value":5}

{"mbean":"com.example.demo:type=Orders","attribute":"MaintenanceMode","previousValue":false}

{"results":[{"status":200,"mbean":"com.example.demo:type=Orders","attribute":"QueueDepth","value":2},
            {"status":403,"error":"Access denied by policy"},
            {"status":200,"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage","value":{...}}]}
```

A bulk call answers `200` as a whole; every item carries its own `status`. At most
`api.maxBulkRequests` (100) items, otherwise `413`.

### Status codes

| Status | Meaning | What a client should do |
|--------|---------|-------------------------|
| `400` | malformed JSON, invalid ObjectName, pattern where a concrete name is needed, unconvertible argument | fix the request |
| `401` | missing or wrong credentials, expired token, rejected certificate or JWT | do not retry blindly - failures count towards the lockout |
| `403` | denied by policy; credentials sent over plain HTTP; foreign `Origin` | not retryable |
| `404` | unknown MBean, attribute, operation or path | |
| `413` | body larger than 1 MB or too many bulk items | split the request |
| `415` | `POST`/`PUT` body not sent as `application/json` | set the header |
| `429` | locked after too many failed logins, or too many subscriptions | honor `Retry-After` |
| `500` | the MBean itself failed; the message is in `error`, never a stack trace | |
| `503` | Rocoto could not start (invalid configuration) - fails closed | see the server log |

Errors always have the form `{"status": <code>, "error": "<message>"}`.

### Values

- `long` values outside the IEEE-754 safe integer range are transported as strings, as are
  `BigInteger`/`BigDecimal`; `byte[]` is Base64; dates are ISO-8601 strings; `CompositeData`
  becomes an object, `TabularData` an array. `GET /api/v1/schema?mbean=...` tells you per attribute.
- Masked attributes (`*password*`, ...) are always `"********"`.

All outputs were captured from an embedded Tomcat 10.1 running `rocoto.war` and the demo
application.

## What to look at next

- [streaming-clients](streaming-clients.md), [jolokia-migration](jolokia-migration.md)
- User guide: [REST API](../guide/08-rest-api.md); the OpenAPI document at `/api/v1/openapi.json`

# Streaming clients: Server-Sent Events and WebSocket

## Goal

Get attribute values pushed instead of polling: over Server-Sent Events (one subscription per
HTTP request) or over a WebSocket (many subscriptions multiplexed on one connection). Both use the
same subscription model and the same authentication and policy as the REST API.

Runnable clients: [`examples/clients`](../../examples/clients/README.md).

## Prerequisites

- A running Rocoto with the demo application, e.g. [tomcat-builtin-auth](tomcat-builtin-auth.md)
- `ROCOTO_URL`, and `ROCOTO_TOKEN` or `ROCOTO_USER` + `ROCOTO_PASSWORD` in the environment
- Java 17+ / Python 3.9+ with `requests` / Node.js 18+ with `ws`

## The protocol

**SSE** - `GET /api/v1/subscribe`

| Query parameter | Meaning |
|-----------------|---------|
| `mbean` | concrete ObjectName (required; patterns are rejected with `400`) |
| `attribute` | attribute to poll; repeat the parameter for several |
| `interval` | polling interval in ms; default 1000, never below `stream.minIntervalMillis` (500) |
| `onChange` | default `true`: only emit when the values differ from the previous emission |
| `above`, `below` | only emit while the (single, numeric) attribute is greater / less than the value |
| `notifications` | `true` = also forward the MBean's JMX notifications |

Events: `value`, `notification`, `error`, `heartbeat` (after 15 s of silence). The data of each
event is one JSON document.

**WebSocket** - `/api/v1/ws`, JSON text messages:

```text
-> {"action":"subscribe","id":"heap","mbean":"java.lang:type=Memory","attributes":["HeapMemoryUsage"],"interval":1000}
<- {"id":"heap","event":"value","data":{"mbean":"java.lang:type=Memory","ts":"...","values":{"HeapMemoryUsage":{...}}}}
-> {"action":"unsubscribe","id":"heap"}
```

`id` is chosen by the client (at most 64 characters); `onChange`, `above`, `below` and
`notifications` work as in SSE. Errors arrive as `{"id":...,"event":"error","data":{"status":...,"error":...}}`.

Limits: `stream.maxSubscriptions` (200), `stream.maxSubscriptionsPerUser` (20) - exceeding them
gives `429`. `stream.websocket: false` switches the WebSocket endpoint off.

## Files

### curl

```sh
curl -N -u rita:demo-password-123 \
  "$ROCOTO_URL/api/v1/subscribe?mbean=com.example.demo:type=Orders&attribute=QueueDepth&attribute=OrdersProcessed&interval=1000"
```

### Java: `BodyHandlers.ofLines()` and `java.net.http.WebSocket`

[`examples/clients/java/src/main/java/com/example/rocoto/client/RocotoClientExample.java`](../../examples/clients/java/src/main/java/com/example/rocoto/client/RocotoClientExample.java)

```java
package com.example.rocoto.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Talks to Rocoto with nothing but the JDK ({@code java.net.http}).
 *
 * <p>Environment: {@code ROCOTO_URL} (default {@code http://localhost:8080/rocoto}) and either
 * {@code ROCOTO_TOKEN} or {@code ROCOTO_USER} + {@code ROCOTO_PASSWORD}.
 *
 * <p>The JDK has no JSON parser, so this example prints the raw JSON documents. In a real
 * application parse them with the JSON library you already use.
 */
public final class RocotoClientExample {

    private static final String ORDERS = "com.example.demo:type=Orders";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String authorization;

    private RocotoClientExample(String baseUrl, String authorization) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorization = authorization;
    }

    public static void main(String[] args) throws Exception {
        String url = env("ROCOTO_URL", "http://localhost:8080/rocoto");
        RocotoClientExample client = new RocotoClientExample(url, authorizationFromEnvironment());

        System.out.println("== who am I");
        System.out.println(client.get("/api/v1/server").body());

        System.out.println("== read one attribute (resource style, ObjectName URL-encoded)");
        System.out.println(client.get("/api/v1/mbeans/" + encode("java.lang:type=Memory")
                + "/attributes/HeapMemoryUsage").body());

        System.out.println("== read (RPC style, no escaping needed)");
        System.out.println(client.post("/api/v1/read",
                "{\"mbean\":\"" + ORDERS + "\",\"attributes\":[\"OrdersProcessed\",\"QueueDepth\"]}").body());

        System.out.println("== bulk: every item carries its own status");
        System.out.println(client.post("/api/v1/bulk", """
                [ {"type":"read","mbean":"java.lang:type=Threading","attribute":"ThreadCount"},
                  {"type":"read","mbean":"java.lang:type=Runtime","attribute":"DoesNotExist"},
                  {"type":"search","pattern":"java.lang:type=GarbageCollector,*"} ]""").body());

        System.out.println("== exec (needs an allow rule for the caller's role)");
        HttpResponse<String> exec = client.post("/api/v1/exec",
                "{\"mbean\":\"" + ORDERS + "\",\"operation\":\"drainQueue\",\"arguments\":[5]}");
        System.out.println(describe(exec));

        System.out.println("== error handling");
        System.out.println(describe(client.get("/api/v1/mbeans/" + encode("nope:type=None"))));

        System.out.println("== Server-Sent Events: three values of QueueDepth");
        client.sse("/api/v1/subscribe?mbean=" + encode(ORDERS)
                + "&attribute=QueueDepth&interval=1000&onChange=false", 3);

        System.out.println("== WebSocket: three values of ThreadCount");
        client.webSocket(3);
    }

    // ---- plain requests ------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Rocoto only accepts request bodies sent as application/json (anything else is a 415). */
    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization);
    }

    /** Errors are JSON as well: {"status":403,"error":"Access denied by policy"}. */
    private static String describe(HttpResponse<String> response) {
        return switch (response.statusCode()) {
            case 200 -> "OK " + response.body();
            case 401 -> "401 - wrong or missing credentials";
            case 403 -> "403 - denied by policy (or credentials sent over plain HTTP): " + response.body();
            case 404 -> "404 - no such MBean, attribute or operation: " + response.body();
            case 429 -> "429 - locked after too many failed logins, retry after "
                    + response.headers().firstValue("Retry-After").orElse("?") + " s";
            default -> response.statusCode() + " " + response.body();
        };
    }

    // ---- streaming -----------------------------------------------------------------------

    /** Consumes {@code count} "value" events. Closing the line stream closes the connection. */
    private void sse(String path, int count) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", authorization)
                .header("Accept", "text/event-stream").GET().build(); // no timeout: the stream is endless
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                // denied or unknown targets fail before the stream starts, with a JSON error body
                System.out.println(response.statusCode() + " " + String.join("", lines.toList()));
                return;
            }
            String[] event = { "message" };
            int[] seen = { 0 };
            lines.takeWhile(line -> seen[0] < count).forEach(line -> {
                if (line.startsWith("event: ")) {
                    event[0] = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    System.out.println(event[0] + " " + line.substring(6));
                    if (event[0].equals("value") || event[0].equals("error")) {
                        seen[0]++;
                    }
                }
            });
        }
    }

    private void webSocket(int count) throws Exception {
        URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + "/api/v1/ws");
        CompletableFuture<Void> done = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            private int seen;

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    System.out.println(buffer);
                    buffer.setLength(0);
                    if (++seen >= count) {
                        done.complete(null);
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                done.completeExceptionally(error);
            }
        };
        // The upgrade request is authenticated like any other request.
        WebSocket socket = http.newWebSocketBuilder().header("Authorization", authorization)
                .buildAsync(uri, listener).get(10, TimeUnit.SECONDS);
        socket.sendText("{\"action\":\"subscribe\",\"id\":\"threads\",\"mbean\":\"java.lang:type=Threading\","
                + "\"attributes\":[\"ThreadCount\"],\"interval\":1000,\"onChange\":false}", true);
        done.get(30, TimeUnit.SECONDS);
        socket.sendText("{\"action\":\"unsubscribe\",\"id\":\"threads\"}", true);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static String authorizationFromEnvironment() {
        String token = System.getenv("ROCOTO_TOKEN");
        if (token != null && !token.isBlank()) {
            return "Bearer " + token;
        }
        String user = System.getenv("ROCOTO_USER");
        String password = System.getenv("ROCOTO_PASSWORD");
        if (user == null || password == null) {
            throw new IllegalStateException("Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD");
        }
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** ObjectNames inside a URL path must be encoded into ONE segment. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
```

### Python: `requests` with `stream=True`

[`examples/clients/python/rocoto_client.py`](../../examples/clients/python/rocoto_client.py)

```python
#!/usr/bin/env python3
"""Talks to Rocoto with the `requests` library: read, bulk, exec and Server-Sent Events.

Environment: ROCOTO_URL (default http://localhost:8080/rocoto) and either ROCOTO_TOKEN or
ROCOTO_USER + ROCOTO_PASSWORD.
"""
import json
import os
import sys
from urllib.parse import quote

import requests

BASE = os.environ.get("ROCOTO_URL", "http://localhost:8080/rocoto").rstrip("/")
ORDERS = "com.example.demo:type=Orders"


def new_session() -> requests.Session:
    session = requests.Session()
    token = os.environ.get("ROCOTO_TOKEN")
    if token:
        session.headers["Authorization"] = f"Bearer {token}"
    elif os.environ.get("ROCOTO_USER") and os.environ.get("ROCOTO_PASSWORD"):
        session.auth = (os.environ["ROCOTO_USER"], os.environ["ROCOTO_PASSWORD"])
    else:
        sys.exit("Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD")
    # For a private CA: session.verify = "/path/to/ca.crt". Never disable verification.
    return session


def check(response: requests.Response) -> dict:
    """Rocoto reports errors as JSON: {"status": 403, "error": "Access denied by policy"}."""
    if response.status_code == 429:
        raise RuntimeError(f"locked out, retry after {response.headers.get('Retry-After')} s")
    if not response.ok:
        try:
            message = response.json().get("error")
        except ValueError:
            message = response.text
        raise RuntimeError(f"HTTP {response.status_code}: {message}")
    return response.json()


def read(session, mbean, attribute):
    # Resource style: the ObjectName is ONE URL-encoded path segment.
    url = f"{BASE}/api/v1/mbeans/{quote(mbean, safe='')}/attributes/{quote(attribute, safe='')}"
    return check(session.get(url, timeout=30))["value"]


def rpc(session, kind, body):
    # RPC style: json= sets the mandatory Content-Type: application/json.
    return check(session.post(f"{BASE}/api/v1/{kind}", json=body, timeout=30))


def subscribe(session, mbean, attribute, count, interval=1000):
    """Server-Sent Events with stream=True; yields (event, data) tuples."""
    params = {"mbean": mbean, "attribute": attribute, "interval": interval, "onChange": "false"}
    with session.get(f"{BASE}/api/v1/subscribe", params=params, stream=True,
                     headers={"Accept": "text/event-stream"}, timeout=(5, 60)) as response:
        if response.status_code != 200:
            check(response)  # denied or unknown targets fail before the stream starts
        event, seen = "message", 0
        for line in response.iter_lines(decode_unicode=True):
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                yield event, json.loads(line[6:])
                seen += event in ("value", "error")
                if seen >= count:
                    return  # leaving the with block closes the connection = unsubscribes


def main():
    session = new_session()

    print("== who am I")
    print(check(session.get(f"{BASE}/api/v1/server", timeout=30))["caller"])

    print("== read")
    print("heap used:", read(session, "java.lang:type=Memory", "HeapMemoryUsage")["used"])
    print(rpc(session, "read", {"mbean": ORDERS, "attributes": ["OrdersProcessed", "QueueDepth", "DbPassword"]}))

    print("== bulk: every item carries its own status")
    bulk = rpc(session, "bulk", [
        {"type": "read", "mbean": "java.lang:type=Threading", "attribute": "ThreadCount"},
        {"type": "read", "mbean": "java.lang:type=Runtime", "attribute": "DoesNotExist"},
        {"type": "search", "pattern": "java.lang:type=GarbageCollector,*"},
    ])
    for item in bulk["results"]:
        print(item["status"], item.get("value", item.get("mbeans", item.get("error"))))

    print("== exec (needs an allow rule for the caller's role)")
    try:
        print(rpc(session, "exec", {"mbean": ORDERS, "operation": "drainQueue", "arguments": [5]}))
    except RuntimeError as e:
        print("failed:", e)

    print("== error handling")
    try:
        read(session, "nope:type=None", "X")
    except RuntimeError as e:
        print("failed:", e)

    print("== Server-Sent Events: three values of QueueDepth")
    for event, data in subscribe(session, ORDERS, "QueueDepth", count=3):
        print(event, data)


if __name__ == "__main__":
    main()
```

### Node.js: fetch streaming and the `ws` package

[`examples/clients/js/rocoto-client.mjs`](../../examples/clients/js/rocoto-client.mjs)

```javascript
// Talks to Rocoto from Node.js 18+: built-in fetch for read/bulk/exec and Server-Sent Events,
// the `ws` package for the WebSocket endpoint (the WebSocket API built into browsers and Node
// cannot send an Authorization header).
//
// Environment: ROCOTO_URL (default http://localhost:8080/rocoto) and either ROCOTO_TOKEN or
// ROCOTO_USER + ROCOTO_PASSWORD.
import WebSocket from 'ws';

const BASE = (process.env.ROCOTO_URL ?? 'http://localhost:8080/rocoto').replace(/\/$/, '');
const ORDERS = 'com.example.demo:type=Orders';

function authorization() {
  const { ROCOTO_TOKEN, ROCOTO_USER, ROCOTO_PASSWORD } = process.env;
  if (ROCOTO_TOKEN) return `Bearer ${ROCOTO_TOKEN}`;
  if (ROCOTO_USER && ROCOTO_PASSWORD) {
    return 'Basic ' + Buffer.from(`${ROCOTO_USER}:${ROCOTO_PASSWORD}`, 'utf8').toString('base64');
  }
  throw new Error('Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD');
}
const AUTH = authorization();

// Rocoto reports errors as JSON: {"status":403,"error":"Access denied by policy"}
async function check(response) {
  if (response.ok) return response.json();
  const text = await response.text();
  let message = text;
  try { message = JSON.parse(text).error; } catch { /* not JSON */ }
  const error = new Error(`HTTP ${response.status}: ${message}`);
  error.status = response.status;
  error.retryAfter = response.headers.get('Retry-After'); // set on 429 (lockout)
  throw error;
}

// Resource style: the ObjectName is ONE URL-encoded path segment.
async function read(mbean, attribute) {
  const url = `${BASE}/api/v1/mbeans/${encodeURIComponent(mbean)}/attributes/${encodeURIComponent(attribute)}`;
  return (await check(await fetch(url, { headers: { Authorization: AUTH } }))).value;
}

// RPC style. The Content-Type is mandatory: anything but application/json is answered with 415.
async function rpc(kind, body) {
  return check(await fetch(`${BASE}/api/v1/${kind}`, {
    method: 'POST',
    headers: { Authorization: AUTH, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }));
}

// Server-Sent Events over fetch streaming. EventSource cannot send an Authorization header.
async function* subscribe(mbean, attribute, { interval = 1000, signal } = {}) {
  const query = new URLSearchParams({ mbean, attribute, interval: String(interval), onChange: 'false' });
  const response = await fetch(`${BASE}/api/v1/subscribe?${query}`, {
    headers: { Authorization: AUTH, Accept: 'text/event-stream' }, signal,
  });
  if (!response.ok) await check(response); // denied or unknown targets fail before the stream starts
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of response.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let end;
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      const block = buffer.slice(0, end);
      buffer = buffer.slice(end + 2);
      let event = 'message';
      let data = '';
      for (const line of block.split('\n')) {
        if (line.startsWith('event: ')) event = line.slice(7);
        else if (line.startsWith('data: ')) data += line.slice(6);
      }
      if (data) yield { event, data: JSON.parse(data) };
    }
  }
}

function webSocketValues(count) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(BASE.replace(/^http/, 'ws') + '/api/v1/ws', {
      headers: { Authorization: AUTH }, // the upgrade request is authenticated like any other
    });
    let seen = 0;
    socket.on('open', () => socket.send(JSON.stringify({
      action: 'subscribe', id: 'threads', mbean: 'java.lang:type=Threading',
      attributes: ['ThreadCount'], interval: 1000, onChange: false,
    })));
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString());
      console.log(message.id, message.event, message.data);
      if (++seen >= count) {
        socket.send(JSON.stringify({ action: 'unsubscribe', id: 'threads' }));
        socket.close(1000, 'done');
      }
    });
    socket.on('unexpected-response', (_req, res) => reject(new Error(`WebSocket upgrade failed: HTTP ${res.statusCode}`)));
    socket.on('error', reject);
    socket.on('close', resolve);
  });
}

console.log('== who am I');
console.log((await check(await fetch(`${BASE}/api/v1/server`, { headers: { Authorization: AUTH } }))).caller);

console.log('== read');
console.log('heap used:', (await read('java.lang:type=Memory', 'HeapMemoryUsage')).used);
console.log(await rpc('read', { mbean: ORDERS, attributes: ['OrdersProcessed', 'QueueDepth', 'DbPassword'] }));

console.log('== bulk: every item carries its own status');
const bulk = await rpc('bulk', [
  { type: 'read', mbean: 'java.lang:type=Threading', attribute: 'ThreadCount' },
  { type: 'read', mbean: 'java.lang:type=Runtime', attribute: 'DoesNotExist' },
  { type: 'search', pattern: 'java.lang:type=GarbageCollector,*' },
]);
for (const item of bulk.results) console.log(item.status, item.value ?? item.mbeans ?? item.error);

console.log("== exec (needs an allow rule for the caller's role)");
try {
  console.log(await rpc('exec', { mbean: ORDERS, operation: 'drainQueue', arguments: [5] }));
} catch (e) {
  console.log('failed:', e.message);
}

console.log('== error handling');
try {
  await read('nope:type=None', 'X');
} catch (e) {
  console.log('failed:', e.message);
}

console.log('== Server-Sent Events: three values of QueueDepth');
const abort = new AbortController();
let seen = 0;
try {
  for await (const { event, data } of subscribe(ORDERS, 'QueueDepth', { signal: abort.signal })) {
    console.log(event, data);
    if ((event === 'value' || event === 'error') && ++seen >= 3) break;
  }
} finally {
  abort.abort(); // closes the connection = unsubscribes
}

console.log('== WebSocket: three values of ThreadCount');
await webSocketValues(3);
```

[`examples/clients/js/package.json`](../../examples/clients/js/package.json)

```json
{
  "name": "rocoto-client-example",
  "version": "1.0.0",
  "description": "Rocoto example: Node.js client (built-in fetch, SSE over fetch streaming, WebSocket with ws)",
  "private": true,
  "type": "module",
  "license": "MIT",
  "engines": { "node": ">=18" },
  "scripts": {
    "start": "node rocoto-client.mjs"
  },
  "dependencies": {
    "ws": "^8.18.0"
  }
}
```

## Commands

```sh
export ROCOTO_URL=http://localhost:8080/rocoto ROCOTO_USER=otto ROCOTO_PASSWORD=demo-password-123   # demo only

(cd examples/clients/java && mvn -q package && java -jar target/rocoto-client-example.jar)
(cd examples/clients/python && pip install -r requirements.txt && python rocoto_client.py)
(cd examples/clients/js && npm install && node rocoto-client.mjs)
```

## Expected output

curl:

```text
event: value
data: {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:20:02.378520Z","values":{"QueueDepth":256,"OrdersProcessed":1082}}

event: value
data: {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:20:03.383056Z","values":{"QueueDepth":256,"OrdersProcessed":1083}}
```

Java client, streaming part:

```text
== Server-Sent Events: three values of QueueDepth
value {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:00:07.582433Z","values":{"QueueDepth":11}}
value {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:00:08.593003Z","values":{"QueueDepth":11}}
value {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:00:09.603683Z","values":{"QueueDepth":11}}
== WebSocket: three values of ThreadCount
{"id":"threads","event":"value","data":{"mbean":"java.lang:type=Threading","ts":"2026-09-18T20:00:09.625459Z","values":{"ThreadCount":24}}}
```

Problems are reported before the stream starts, as a normal JSON error:

```text
401  no credentials
403  {"status":403,"error":"Access denied by policy"}          attribute not readable for the caller
404  unknown MBean or attribute
400  {"status":400,"error":"Subscribe to at least one attribute or to notifications"}
400  {"status":400,"error":"Subscriptions need a concrete ObjectName"}
```

If access is lost while a stream is running (policy or token change), an `error` event with
status 403 is sent. All outputs were captured from an embedded Tomcat 10.1 running `rocoto.war`
and the demo application; all three clients were executed.

## Notes

- Browsers: `EventSource` and the browser `WebSocket` API cannot set an `Authorization` header.
  Use fetch streaming (as the Node.js example does) and list the page's origin under
  `cors.allowedOrigins`; requests with a foreign `Origin` header are answered with `403`.
- Closing the HTTP connection (SSE) or the socket ends the subscriptions; there is nothing to
  clean up on the server.
- Reverse proxies must not buffer the stream. Rocoto sends `X-Accel-Buffering: no`; for other
  proxies disable response buffering for `/rocoto/api/v1/subscribe` and raise idle timeouts above
  the 15 s heartbeat.
- A threshold subscription (`above=100`) is a cheap alert: nothing is sent while the value is
  below the threshold.
- Use `wss://` and `https://` outside of local demos; the WebSocket upgrade request is
  authenticated like any other request and credentials over plain HTTP are rejected.

## What to look at next

- [rest-clients](rest-clients.md), [expose-your-own-mxbean](expose-your-own-mxbean.md)
- User guide: [Streaming](../guide/07-streaming.md)

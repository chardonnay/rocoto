# Client examples: Java, Python, Node.js, PowerShell

Four small programs that do the same things against a running Rocoto: identify the caller, read
(resource and RPC style), bulk, exec, error handling and streaming.

| Directory | Stack | Streaming |
|-----------|-------|-----------|
| [`java/`](java) | Java 17, `java.net.http.HttpClient`, no dependencies | SSE with `BodyHandlers.ofLines()`, WebSocket with `java.net.http.WebSocket` |
| [`python/`](python) | Python 3.9+, `requests` | SSE with `stream=True` |
| [`js/`](js) | Node.js 18+, built-in `fetch` | SSE over fetch streaming, WebSocket with the `ws` package |
| [`powershell/`](powershell) | Windows PowerShell 5.1 or PowerShell 7, .NET `HttpClient`, no modules | SSE with a `StreamReader`, WebSocket with `ClientWebSocket` |

All of them expect the demo MBean `com.example.demo:type=Orders`, so the natural server is
[tomcat-builtin](../tomcat-builtin/README.md) (`make up` there first).

## Environment

| Variable | Meaning |
|----------|---------|
| `ROCOTO_URL` | base URL including the context root, default `http://localhost:8080/rocoto` |
| `ROCOTO_TOKEN` | API token (`rct_...`) or JWT; takes precedence |
| `ROCOTO_USER`, `ROCOTO_PASSWORD` | HTTP Basic credentials |

```sh
export ROCOTO_URL=http://localhost:8080/rocoto
export ROCOTO_USER=otto ROCOTO_PASSWORD=demo-password-123     # demo-only credentials of tomcat-builtin
```

With the reader token instead of `otto` the exec step prints `HTTP 403: Access denied by policy` -
that is the policy at work, not a bug.

## Run

```sh
# Java
cd java && mvn -q package && java -jar target/rocoto-client-example.jar

# Python
cd python && python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
python rocoto_client.py

# Node.js
cd js && npm install && node rocoto-client.mjs
```

```powershell
# PowerShell (Windows, or pwsh on Linux/macOS)
$env:ROCOTO_URL = 'http://localhost:8080/rocoto'
$env:ROCOTO_USER = 'otto'; $env:ROCOTO_PASSWORD = 'demo-password-123'     # demo-only credentials of tomcat-builtin
.\powershell\rocoto-client.ps1
```

Expected output (Java, abridged):

```text
== who am I
{"rocoto":{"version":"0.1.0","authMode":"builtin"},"server":{...},"caller":{"name":"otto","roles":["rocoto-operator"],"authenticatedBy":"basic"}}
== read (RPC style, no escaping needed)
{"mbean":"com.example.demo:type=Orders","values":{"OrdersProcessed":127,"QueueDepth":16}}
== bulk: every item carries its own status
{"results":[{"status":200,...,"value":23},{"status":404,"error":"No such attribute: ..."},{"status":200,"pattern":"java.lang:type=GarbageCollector,*","mbeans":[...]}]}
== exec (needs an allow rule for the caller's role)
OK {"mbean":"com.example.demo:type=Orders","operation":"drainQueue","value":5}
== error handling
404 - no such MBean, attribute or operation: {"status":404,"error":"No MBean registered as nope:type=None"}
== Server-Sent Events: three values of QueueDepth
value {"mbean":"com.example.demo:type=Orders","ts":"2026-09-18T20:00:07.582433Z","values":{"QueueDepth":11}}
...
== WebSocket: three values of ThreadCount
{"id":"threads","event":"value","data":{"mbean":"java.lang:type=Threading","ts":"...","values":{"ThreadCount":24}}}
```

All three programs were run against `rocoto.war` and the demo application in an embedded
Tomcat 10.1 with the tomcat-builtin configuration.

## Things every client must get right

- Send request bodies as `Content-Type: application/json`; anything else is answered with `415`.
- Do not send an `Origin` header from non-browser clients; foreign origins are answered with `403`.
- URL-encode the ObjectName into one path segment, or use the RPC endpoints (`POST /api/v1/read`, ...).
- Credentials over plain HTTP are rejected with `403` unless the server runs with the demo-only
  `auth.allowInsecureTransport`. Use `https://` URLs (and `wss://` for the WebSocket).
- `EventSource` and the browser WebSocket API cannot send an `Authorization` header - use fetch
  streaming for SSE and a WebSocket client that supports headers.

Cookbook: [REST clients](../../docs/examples/rest-clients.md),
[Streaming clients](../../docs/examples/streaming-clients.md).

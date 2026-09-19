# Rocoto inside an Undertow or Netty application

Undertow's core API and Netty have no servlet container, so `rocoto.war` cannot be deployed there.
This example mounts Rocoto into the application's own server instead - see
[installation: Undertow and Netty](../../docs/guide/02-installation.md#undertow-and-netty-embedded-runtime)
for the Java agent and the standalone jar.

| File | Purpose |
|------|---------|
| `src/main/java/com/example/embedded/UndertowApp.java` | Undertow application; `RocotoUndertowHandler` in front of its own handler |
| `src/main/java/com/example/embedded/NettyApp.java` | Netty application; `RocotoNettyHandler` in its pipeline, with its own worker threads |
| `conf/rocoto.yaml`, `conf/rocoto-users.yaml` | **LOCAL DEMO ONLY** - plain HTTP with `auth.allowInsecureTransport`, public demo users |

## Run

```sh
mvn install -DskipTests                 # repository root: installs io.rocoto:rocoto-undertow / rocoto-netty
cd examples/embedded && mvn -q package

java -Drocoto.config=conf/rocoto.yaml -cp 'target/classes:target/lib/*' com.example.embedded.UndertowApp 8080
# or
java -Drocoto.config=conf/rocoto.yaml -cp 'target/classes:target/lib/*' com.example.embedded.NettyApp 8080
```

```sh
curl -s http://localhost:8080/hello
# Hello from the Undertow application

curl -s -u rita:demo-password-123 http://localhost:8080/rocoto/api/v1/server
# {"rocoto":{...,"authMode":"builtin"},"server":{"adapter":"generic",...},
#  "caller":{"name":"rita","roles":["rocoto-reader"],"authenticatedBy":"basic"}}

curl -s -N -u rita:demo-password-123 \
  'http://localhost:8080/rocoto/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=1000'
```

Demo users (`rita` reader, `otto` operator, `ada` admin) share the password `demo-password-123`.

## Production

- Remove `auth.allowInsecureTransport` and serve TLS: an HTTPS listener in Undertow, an `SslHandler`
  as the first handler of the Netty pipeline. Rocoto refuses credentials on plain connections.
- `auth.mode` must be `builtin`; there is no container to delegate to.
- Streaming is offered over Server-Sent Events; there is no WebSocket endpoint in this runtime.

## What was verified

`ci/embedded/smoke-test.sh` starts both applications and runs authentication, policy, metrics,
Jolokia, SSE and keep-alive checks against them, then the standalone jars with TLS and the Java
agents.

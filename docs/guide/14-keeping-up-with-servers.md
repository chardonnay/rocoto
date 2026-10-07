# 14 - Keeping up with server releases

Rocoto depends on things application servers are free to change between releases: MBean names
and attribute names (adapters, metric profiles), security behaviour (login configuration,
Jakarta Authorization), container image layouts. None of that is visible to the compiler. The
protection is therefore a set of smoke tests that run the real `rocoto.war` on real servers and
assert concrete facts - "the metric `wildfly_datasource_connections_active` exists", not
"`/metrics` answers 200".

## What runs when

| Workflow | Trigger | Server versions | Purpose |
|----------|---------|-----------------|---------|
| `ci.yml` | every push and pull request | **pinned** image tags in the job matrices | reproducible: a red build is caused by the change, not by a server release |
| `server-compat.yml` | weekly (Monday) and manually | **floating** tags (`latest`, `tomcat:10.1-jdk17`, ...) plus the next platform generation (Tomcat 11) | early warning: opens or updates an issue "Server compatibility: ... fails" |
| Dependabot | weekly | Maven dependencies (embedded Tomcat of the integration tests, Undertow, Netty, SnakeYAML) and GitHub Actions | pull requests; `mvn verify` and the smoke tests decide |

The Jakarta EE APIs are deliberately held at the EE 10 level in `.github/dependabot.yml`.

## When the weekly run turns red

1. Reproduce locally with the image from the issue - every script takes the image as an environment
   variable and needs only Docker, `curl`, `jq` and a built `rocoto.war`:

   ```bash
   mvn install -DskipTests
   JETTY_IMAGE=jetty:latest ci/jetty/smoke-test.sh
   FLAVOR=glassfish GLASSFISH_IMAGE=ghcr.io/eclipse-ee4j/glassfish:latest ci/payara/smoke-test.sh
   ```

2. Find out what moved. The usual suspects, all seen in practice:

   | Symptom | Example | Where to fix |
   |---------|---------|--------------|
   | A metric or a field of `/api/v1/resources/...` disappeared | WildFly's `statistics=pool` uses `InUseCount`, not `inUseCount`; Payara's request counter is `countrequests` | `rocoto-metrics/src/main/resources/io/rocoto/metrics/profiles/<server>.yaml`, `rocoto-adapters/.../<Server>Adapter.java` - look at the live MBean first: `GET /api/v1/mbeans?pattern=<domain>:*`, then `POST /api/v1/read` |
   | ObjectName layout changed | Jetty 12.1: `context=oejs_Server@<hash>/_rocoto` instead of `context=rocoto` | adapter (name rows after a stable attribute), profile (`${prop\|basename}`) |
   | Deployment fails | Jetty requires a LoginService for `<login-config>`; a server without the WebSocket API | server specific descriptor in `rocoto-war/src/main/webapp/WEB-INF/`, defensive code in `RocotoBootstrap` |
   | Rocoto answers `503 not initialised` | GlassFish 8: default trust store without CA certificates | read the `SEVERE` line of the server log; make the failing part lazy or optional |
   | The test itself breaks | image moved its install directory, starts the domain twice, dropped `curl` | the script under `ci/<server>/`; read paths from the image (`docker image inspect`) instead of hard-coding them |
   | A server defect | GlassFish 7 answers 503 for a `:` in the URL path | nothing to fix in Rocoto: assert the known behaviour in the test, document the workaround, link the upstream issue |

3. Keep old versions working: adapters read alternatives (`reader.readFirst(n, "maxPoolSize",
   "maxWorkerPoolSize")`), profiles may carry rules for both spellings - a rule that matches
   nothing costs nothing.

4. Add the new version to the matrix of `ci.yml` next to the old one, note it in the "verified
   with" sentence of [02 - Installation](02-installation.md), and close the issue.

## Moving the pinned versions

Pinned tags only move when somebody moves them. Do it together with step 4 above, or on a
schedule (for example every quarter): raise the tags in the matrices of `.github/workflows/ci.yml`
and the defaults at the top of `ci/*/smoke-test.sh`, run the scripts locally, commit. If you prefer
pull requests for that, Renovate can update image tags in arbitrary files with a
[regex manager](https://docs.renovatebot.com/modules/manager/regex/); Dependabot only understands
`Dockerfile`s and Compose files (it does cover `examples/*/docker-compose.yaml` if you add a
`docker` ecosystem entry per directory).

## A new server

1. Deploy `rocoto.war` and run `examples/tomcat-builtin/test.sh` against it - the generic part
   (authentication, policy, REST, Jolokia, SSE) normally works immediately.
2. List its MBeans through Rocoto, write `<Server>Adapter` (detection, server info, resources) and
   `profiles/<server>.yaml`, register both (`META-INF/services`, `MetricProfiles.BUNDLED`), add the
   configuration directory to `ConfigLoader` if the server has one.
3. Copy the closest `ci/<server>/smoke-test.sh`, add a job to `ci.yml` and a line to
   `server-compat.yml`, document the server in [02 - Installation](02-installation.md).

## Next

[15 - Output formats](15-output-formats.md)

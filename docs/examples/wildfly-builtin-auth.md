# WildFly with built-in authentication

## Goal

The shortest way to a secured Rocoto on WildFly or JBoss EAP: deploy the WAR, create one user and
one token with `rocoto-tools`, talk HTTPS. Elytron is not involved (`auth.mode: builtin`, the
default), so nothing in the server configuration has to change.

There is no separate runnable project for this page. The Docker Compose project
[`examples/wildfly-oidc-keycloak`](../../examples/wildfly-oidc-keycloak/README.md) also runs
WildFly in `builtin` mode.

## Prerequisites

- WildFly 27+ or JBoss EAP 8 (Jakarta EE 10), JDK 17+
- `rocoto.war` and `rocoto-tools.jar` (`mvn install -DskipTests` in the repository root)

## Files

`$JBOSS_HOME/standalone/configuration/rocoto.yaml` - optional. Without any file Rocoto starts
with secure defaults (built-in authentication, TLS-only credentials, read-only policy, JVM
metrics) and looks for `rocoto-users.yaml` in the same directory:

```yaml
auth:
  mode: builtin
  builtin:
    usersFile: rocoto-users.yaml

policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]

audit:
  file: /opt/wildfly/standalone/log/rocoto-audit.log

metrics:
  profiles: [jvm, auto]        # auto = wildfly
```

Statistics for the `wildfly` metrics profile and the normalized resource view (review and adapt
before use; written against the WildFly 27+ management model):

```text
# Review and adapt before use; written against the WildFly 27+ management model
/subsystem=datasources/data-source=ExampleDS:write-attribute(name=statistics-enabled, value=true)
/subsystem=undertow:write-attribute(name=statistics-enabled, value=true)
reload
```

## Commands

```sh
CONF=$JBOSS_HOME/standalone/configuration

java -jar rocoto-tools.jar user add ada --roles rocoto-admin --file $CONF/rocoto-users.yaml
java -jar rocoto-tools.jar token create prometheus --roles rocoto-reader --days 365 \
     --mbeans "java.lang:*;java.nio:*;jboss.as:*" --file $CONF/rocoto-users.yaml
chmod 600 $CONF/rocoto-users.yaml

cp rocoto.war $JBOSS_HOME/standalone/deployments/
```

The default configuration serves HTTPS on port 8443 with a self-signed certificate that WildFly
generates on first use. For a first local test export it and hand it to `curl`; replace it with a
real certificate afterwards:

```sh
keytool -exportcert -rfc -alias server -keystore $CONF/application.keystore -storepass password > wildfly-selfsigned.crt
```

## Expected output

```sh
B=https://localhost:8443/rocoto

curl -s --cacert wildfly-selfsigned.crt -u ada $B/api/v1/server
# Enter host password for user 'ada':
# {"rocoto":{"version":"0.1.0","authMode":"builtin"},
#  "server":{"adapter":"wildfly","product":"WildFly ...","version":"...","serverState":"running","launchType":"STANDALONE"},
#  "caller":{"name":"ada","roles":["rocoto-admin"],"authenticatedBy":"basic"}}

# The same over plain HTTP is refused
curl -s -u ada http://localhost:8080/rocoto/api/v1/server
# {"status":403,"error":"Credentials are only accepted over TLS"}

curl -s --cacert wildfly-selfsigned.crt -u ada $B/api/v1/resources/datasources
# {"kind":"datasources","server":"wildfly","items":[{"name":"ExampleDS","mbean":"jboss.as:data-source=ExampleDS,subsystem=datasources","jndiName":"java:jboss/datasources/ExampleDS",...}]}

curl -s --cacert wildfly-selfsigned.crt -H "Authorization: Bearer $TOKEN" $B/metrics | grep -c '^wildfly_'
```

The status codes and the caller block are what Rocoto's integration tests assert; the WildFly
specific steps on this page were not executed when it was written.

## Notes

- `rocoto.war` contains `WEB-INF/jboss-web.xml` with `<security-domain>other</security-domain>`.
  In `builtin` mode the container never authenticates, the mapping only has to exist - it does in
  every default configuration.
- A scoped token (`--mbeans`) sees only the listed MBeans, in the API and in `/metrics`.
- An invalid `rocoto.yaml` makes Rocoto fail closed: every request is answered with `503` and the
  reason is in `server.log`.

## What to look at next

- [wildfly-basic-elytron](wildfly-basic-elytron.md) - users from an Elytron security domain
- [wildfly-mtls-ocsp](wildfly-mtls-ocsp.md), [wildfly-oidc-keycloak](wildfly-oidc-keycloak.md)
- [policy-recipes](policy-recipes.md)

# WildFly with JWT bearer tokens from Keycloak

## Goal

Users log in at Keycloak; clients send the access token as `Authorization: Bearer <jwt>`. Rocoto
validates the token itself - signature (RS256/384/512, ES256/384) against the realm's JWKS,
`iss`, `aud`, `exp` - and maps `realm_access.roles` to its roles. The application server takes no
part in it, so the same `rocoto.yaml` works on Tomcat and Payara.

Runnable project: [`examples/wildfly-oidc-keycloak`](../../examples/wildfly-oidc-keycloak/README.md).

## Prerequisites

- Docker with the compose plugin, `make`, `curl`, `python3`
- `mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central),
  `mvn -q package` in `examples/demo-app`

## Files

[`examples/wildfly-oidc-keycloak/docker-compose.yaml`](../../examples/wildfly-oidc-keycloak/docker-compose.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# Keycloak runs in development mode over plain HTTP, the realm contains users with public
# passwords and a public client with the password grant. conf/rocoto.yaml sets
# auth.allowInsecureTransport, which also permits the http:// JWKS URL.
# =====================================================================================
services:
  keycloak:
    image: quay.io/keycloak/keycloak:26.0
    command: ["start-dev", "--import-realm"]
    environment:
      KC_BOOTSTRAP_ADMIN_USERNAME: admin
      KC_BOOTSTRAP_ADMIN_PASSWORD: demo-keycloak-123     # DEMO ONLY
      # Fixed front-end URL, so the "iss" claim is always http://localhost:8081/realms/rocoto-demo,
      # while WildFly may still fetch the JWKS through the internal name http://keycloak:8080.
      KC_HOSTNAME: http://localhost:8081
      KC_HOSTNAME_BACKCHANNEL_DYNAMIC: "true"
      KC_HTTP_ENABLED: "true"
    ports:
      - "8081:8080"
    volumes:
      - ./keycloak/rocoto-demo-realm.json:/opt/keycloak/data/import/rocoto-demo-realm.json:ro

  wildfly:
    image: quay.io/wildfly/wildfly:latest-jdk17   # pin a release tag for reproducible runs
    depends_on:
      - keycloak
    ports:
      - "8080:8080"
    volumes:
      - ../../rocoto-war/target/rocoto.war:/opt/jboss/wildfly/standalone/deployments/rocoto.war:ro
      - ../demo-app/target/demo-app.war:/opt/jboss/wildfly/standalone/deployments/demo-app.war:ro
      - ./conf/rocoto.yaml:/opt/jboss/wildfly/standalone/configuration/rocoto.yaml:ro
      - ./conf/rocoto-users.yaml:/opt/jboss/wildfly/standalone/configuration/rocoto-users.yaml:ro
```

The realm: two realm roles, a public client with the password grant (demo only) and an audience
mapper that adds `rocoto` to `aud`, two users (`kate` = `rocoto-admin`, `ken` = `rocoto-reader`,
password `demo-password-123`, demo only):

[`examples/wildfly-oidc-keycloak/keycloak/rocoto-demo-realm.json`](../../examples/wildfly-oidc-keycloak/keycloak/rocoto-demo-realm.json)

```json
{
  "realm": "rocoto-demo",
  "enabled": true,
  "sslRequired": "none",
  "accessTokenLifespan": 300,
  "roles": {
    "realm": [
      { "name": "rocoto-reader", "description": "Read access to Rocoto" },
      { "name": "rocoto-admin", "description": "Administrative access to Rocoto" }
    ]
  },
  "clients": [
    {
      "clientId": "rocoto-cli",
      "name": "Rocoto demo client (DEMO ONLY: public client with the password grant)",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": true,
      "protocol": "openid-connect",
      "fullScopeAllowed": true,
      "protocolMappers": [
        {
          "name": "audience-rocoto",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "consentRequired": false,
          "config": {
            "included.custom.audience": "rocoto",
            "access.token.claim": "true",
            "id.token.claim": "false"
          }
        }
      ]
    }
  ],
  "users": [
    {
      "username": "kate",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Kate",
      "lastName": "Demo",
      "email": "kate@example.com",
      "credentials": [ { "type": "password", "value": "demo-password-123", "temporary": false } ],
      "realmRoles": [ "rocoto-admin" ]
    },
    {
      "username": "ken",
      "enabled": true,
      "emailVerified": true,
      "firstName": "Ken",
      "lastName": "Demo",
      "email": "ken@example.com",
      "credentials": [ { "type": "password", "value": "demo-password-123", "temporary": false } ],
      "realmRoles": [ "rocoto-reader" ]
    }
  ]
}
```

[`examples/wildfly-oidc-keycloak/conf/rocoto.yaml`](../../examples/wildfly-oidc-keycloak/conf/rocoto.yaml)

```yaml
# =====================================================================================
# LOCAL DEMO ONLY - never use in production.
# auth.allowInsecureTransport accepts bearer tokens over plain HTTP AND permits the http://
# jwksUrl below. In production: HTTPS everywhere, remove the flag, use an https:// jwksUrl.
# =====================================================================================
auth:
  mode: builtin                     # Rocoto validates the JWT itself; Elytron is not involved
  allowInsecureTransport: true      # LOCAL DEMO ONLY - never use in production
  builtin:
    usersFile: rocoto-users.yaml
    basic: false                    # people authenticate at Keycloak, not with passwords here
    tokens: true                    # API tokens (rct_...) for machines keep working
    jwt:
      enabled: true
      # must equal the "iss" claim exactly - the URL clients use to reach Keycloak
      issuer: http://localhost:8081/realms/rocoto-demo
      # must be contained in the "aud" claim - added by the client's audience mapper
      audience: rocoto
      # fetched by Rocoto from inside the compose network
      jwksUrl: http://keycloak:8080/realms/rocoto-demo/protocol/openid-connect/certs
      nameClaim: preferred_username # default: sub (a UUID in Keycloak)
      rolesClaim: realm_access.roles
      # roleMapping: { ops-admin: rocoto-admin }   # if your realm roles are named differently

policy:
  rules:
    - roles: [rocoto-admin]
      allow: [exec]
      mbean: "com.example.demo:type=Orders"
      operations: [resetCounters, drainQueue]
    - roles: [rocoto-admin]
      allow: [write]
      mbean: "com.example.demo:type=Orders"
      attributes: [MaintenanceMode]

health:
  allowAnonymous: true
```

[`examples/wildfly-oidc-keycloak/conf/rocoto-users.yaml`](../../examples/wildfly-oidc-keycloak/conf/rocoto-users.yaml)

```yaml
# DEMO ONLY - the prometheus token is published in the README of this example.
# Rocoto built-in credentials. Managed with rocoto-tools; contains only hashes.
tokens:
  - id: prometheus
    hash: sha256$IFn54hbJrL/j2yObb5xTynOJcp67SMRZ4cNmhUZtt3Y
    roles:
      - rocoto-reader
```

[`examples/wildfly-oidc-keycloak/get-token.sh`](../../examples/wildfly-oidc-keycloak/get-token.sh)

```sh
#!/usr/bin/env bash
# Prints an access token for a demo user. DEMO ONLY: the resource owner password grant hands the
# user's password to the client; use the authorization code flow (people) or client credentials
# (services) in real life.
#
#   ./get-token.sh [user] [password]        default: kate / demo-password-123
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
REALM="${REALM:-rocoto-demo}"
USERNAME="${1:-kate}"
PASSWORD="${2:-demo-password-123}"

response="$(curl -s --fail-with-body \
  -d grant_type=password \
  -d client_id=rocoto-cli \
  --data-urlencode "username=$USERNAME" \
  --data-urlencode "password=$PASSWORD" \
  "$KEYCLOAK_URL/realms/$REALM/protocol/openid-connect/token")" || {
    echo "token request failed: $response" >&2
    exit 1
  }

# Extract access_token without jq
printf '%s' "$response" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
```

[`examples/wildfly-oidc-keycloak/call-rocoto.sh`](../../examples/wildfly-oidc-keycloak/call-rocoto.sh)

```sh
#!/usr/bin/env bash
# Obtains a token for the given demo user and calls Rocoto with it.
#   ./call-rocoto.sh [user] [path]          default: kate /api/v1/server
set -euo pipefail

ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
USERNAME="${1:-kate}"
REQUEST_PATH="${2:-/api/v1/server}"

TOKEN="$("$(dirname "$0")/get-token.sh" "$USERNAME")"

echo "== claims Rocoto looks at" >&2
printf '%s' "$TOKEN" | cut -d. -f2 | python3 -c '
import base64, json, sys
segment = sys.stdin.read().strip()
claims = json.loads(base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4)))
print(json.dumps({k: claims.get(k) for k in ("iss", "aud", "exp", "preferred_username", "realm_access")}, indent=2))' >&2

echo "== GET $ROCOTO_URL$REQUEST_PATH" >&2
curl -s -H "Authorization: Bearer $TOKEN" "$ROCOTO_URL$REQUEST_PATH"
echo
```

[`examples/wildfly-oidc-keycloak/test.sh`](../../examples/wildfly-oidc-keycloak/test.sh)

```sh
#!/usr/bin/env bash
# Runs the requests documented in README.md and fails on the first unexpected status code.
# DEMO credentials only - see README.md.
set -euo pipefail

cd "$(dirname "$0")"
ROCOTO_URL="${ROCOTO_URL:-http://localhost:8080/rocoto}"
export KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8081}"
failures=0

expect() {
  local want="$1" what="$2" got
  shift 2
  got="$(curl -s -o /dev/null -w '%{http_code}' "$@" || true)"
  if [ "$got" = "$want" ]; then
    printf 'ok    %s  %s\n' "$got" "$what"
  else
    printf 'FAIL  %s  %s (expected %s)\n' "$got" "$what" "$want"
    failures=$((failures + 1))
  fi
}

ADMIN_JWT="$(./get-token.sh kate)"
READER_JWT="$(./get-token.sh ken)"
json=(-H 'Content-Type: application/json')
drain='{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}'

expect 401 "anonymous request"                     "$ROCOTO_URL/api/v1/server"
expect 401 "garbage instead of a JWT"              -H "Authorization: Bearer not.a.jwt" "$ROCOTO_URL/api/v1/server"
expect 401 "Basic is disabled (auth.builtin.basic: false)" -u "kate:demo-password-123" "$ROCOTO_URL/api/v1/server"
expect 200 "kate (rocoto-admin)"                   -H "Authorization: Bearer $ADMIN_JWT" "$ROCOTO_URL/api/v1/server"
expect 200 "ken (rocoto-reader) may read"          -H "Authorization: Bearer $READER_JWT" \
  "$ROCOTO_URL/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage"
expect 403 "ken may not exec"                      -H "Authorization: Bearer $READER_JWT" "${json[@]}" -d "$drain" "$ROCOTO_URL/api/v1/exec"
expect 200 "kate may exec"                         -H "Authorization: Bearer $ADMIN_JWT" "${json[@]}" -d "$drain" "$ROCOTO_URL/api/v1/exec"
expect 200 "API token next to JWT"                 -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" "$ROCOTO_URL/metrics"

caller="$(curl -s -H "Authorization: Bearer $ADMIN_JWT" "$ROCOTO_URL/api/v1/server")"
case "$caller" in
  *'"name":"kate"'*'"authenticatedBy":"jwt"'*) echo "ok         caller is kate, authenticated by jwt" ;;
  *) echo "FAIL       unexpected caller: $caller"; failures=$((failures + 1)) ;;
esac

if [ "$failures" -gt 0 ]; then
  echo "$failures check(s) failed"
  exit 1
fi
echo "all checks passed"
```

## Commands

```sh
cd examples/wildfly-oidc-keycloak
make up
TOKEN=$(./get-token.sh kate)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/rocoto/api/v1/server
make test
make down
```

## Expected output

```text
{"rocoto":{"version":"0.1.0-SNAPSHOT","authMode":"builtin"},"server":{"adapter":"wildfly",...},
 "caller":{"name":"kate","roles":["default-roles-rocoto-demo","offline_access","rocoto-admin","uma_authorization"],"authenticatedBy":"jwt"}}
```

`ken` may read but gets `{"status":403,"error":"Access denied by policy"}` for `exec`; a
malformed, expired or wrongly signed token gets `401`. The reason (`JWT rejected: unexpected
issuer`, `... unexpected audience`, `... token expired`, `... no matching key in JWKS`) is written
to the audit log only.

**Not executed:** Docker was not available when this page was written. The realm file is valid
JSON and the scripts pass `bash -n`; Keycloak specifics follow the Keycloak 26 documentation.

## Production checklist

- HTTPS everywhere; remove `allowInsecureTransport`. `jwksUrl` must then be `https://` - Rocoto
  refuses to start otherwise. The JWKS download honors `tls.outbound` (trust store, revocation).
- `issuer` must equal the `iss` claim byte for byte; `audience` must be contained in `aud`.
- Instead of `jwksUrl` a fixed `publicKey` (PEM) can be configured - exactly one of the two.
- Use `roleMapping` when the IdP's role names differ: `roleMapping: { ops-admin: rocoto-admin }`.
- Tokens whose value starts with `rct_` are always treated as Rocoto API tokens, everything else
  after `Bearer` as a JWT.
- Do not use the password grant outside of demos.

## What to look at next

- [wildfly-basic-elytron](wildfly-basic-elytron.md) - let Elytron authenticate instead
- [Configuration reference](../reference/configuration.md): `auth.builtin.jwt`, `tls.outbound`

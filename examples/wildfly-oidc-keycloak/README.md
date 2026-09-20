# WildFly with JWT bearer tokens from Keycloak

Keycloak issues access tokens; Rocoto validates them itself (`auth.builtin.jwt`: signature against
the realm's JWKS, `iss`, `aud`, `exp`) and takes the roles from `realm_access.roles`. WildFly's
Elytron is not involved, so the same configuration works on Tomcat and Payara.

> **LOCAL DEMO ONLY - never use in production.** Keycloak in development mode over plain HTTP,
> public passwords, a public client with the password grant, `auth.allowInsecureTransport: true`.

| File | Purpose |
|------|---------|
| `docker-compose.yaml` | Keycloak 26 (`start-dev --import-realm`) on port 8081, WildFly on port 8080 |
| `keycloak/rocoto-demo-realm.json` | realm `rocoto-demo`: roles `rocoto-reader` / `rocoto-admin`, client `rocoto-cli` with an audience mapper (`aud: rocoto`), users `kate` and `ken` |
| `conf/rocoto.yaml` | `auth.builtin.jwt` with `issuer`, `audience`, `jwksUrl`, `nameClaim`, `rolesClaim` |
| `conf/rocoto-users.yaml` | the `prometheus` API token (JWT and API tokens coexist) |
| `get-token.sh` | password grant with `curl`, prints the access token |
| `call-rocoto.sh` | gets a token, prints the relevant claims, calls Rocoto |
| `get-token.ps1`, `call-rocoto.ps1` | the same for PowerShell: `.\call-rocoto.ps1 -User ken -Path /api/v1/resources/threadpools` |

## Demo credentials (demo only)

| Principal | Secret | Realm role |
|-----------|--------|------------|
| `kate` | `demo-password-123` | `rocoto-admin` |
| `ken` | `demo-password-123` | `rocoto-reader` |
| Keycloak admin console `admin` | `demo-keycloak-123` | |
| Rocoto API token `prometheus` | `rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY` | `rocoto-reader` |

## Prerequisites

Docker with the compose plugin, `make`, `curl`, `python3` (used by the scripts to parse JSON); both
WARs built (`mvn install -DskipTests` in the repository root - Rocoto is not on Maven Central - and
`mvn -q package` in `examples/demo-app`).

## Run

```sh
make up
make test
make down
```

## What to try

```sh
# 1. Get a token (password grant - demo only)
TOKEN=$(./get-token.sh kate)
# equivalent to:
# curl -s -d grant_type=password -d client_id=rocoto-cli -d username=kate -d password=demo-password-123 \
#      http://localhost:8081/realms/rocoto-demo/protocol/openid-connect/token

# 2. Call Rocoto
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/rocoto/api/v1/server
# {"rocoto":{"version":"0.1.0","authMode":"builtin"},"server":{"adapter":"wildfly",...},
#  "caller":{"name":"kate","roles":[...,"rocoto-admin",...],"authenticatedBy":"jwt"}}

# Both steps plus the decoded claims:
./call-rocoto.sh ken /api/v1/resources/threadpools
# == claims Rocoto looks at
# {
#   "iss": "http://localhost:8081/realms/rocoto-demo",
#   "aud": ["rocoto", "account"],
#   "exp": 1789761831,
#   "preferred_username": "ken",
#   "realm_access": { "roles": ["default-roles-rocoto-demo", "offline_access", "rocoto-reader", "uma_authorization"] }
# }

# ken is a reader
curl -s -H "Authorization: Bearer $(./get-token.sh ken)" -H 'Content-Type: application/json' \
     -d '{"mbean":"com.example.demo:type=Orders","operation":"drainQueue","arguments":[5]}' \
     http://localhost:8080/rocoto/api/v1/exec
# {"status":403,"error":"Access denied by policy"}
```

Roles that Rocoto's policy does not mention (`offline_access`, ...) are simply ignored.

## Troubleshooting

A rejected token is answered with `401`; the reason is only written to the audit log
(`io.rocoto.audit` logger in `server.log`):

| Audit reason | Cause |
|--------------|-------|
| `JWT rejected: unexpected issuer` | `issuer` differs from the `iss` claim - it is the URL the *client* used to reach Keycloak (here fixed with `KC_HOSTNAME`) |
| `JWT rejected: unexpected audience` | the client has no audience mapper for `rocoto` |
| `JWT rejected: no matching key in JWKS` | `jwksUrl` not reachable from WildFly, or wrong realm |
| `JWT rejected: token expired` | access tokens of this realm live 5 minutes |
| start-up fails with `auth.builtin.jwt.jwksUrl must use https` | `http://` JWKS URLs are only accepted together with `allowInsecureTransport` |

## Status of this example

`make up && make test` was executed with Docker (Docker 29, September 2026, Keycloak 26.0, WildFly 40.0.1.Final): the
realm import, the password grant for `kate` and `ken`, the audience mapper and Rocoto's JWT
validation work as described. If the token request of your own realm answers
`Account is not fully set up`, open the user in the admin console and complete the required
profile fields.

## What to look at next

Cookbook: [wildfly-oidc-keycloak](../../docs/examples/wildfly-oidc-keycloak.md). For HTTPS JWKS
downloads see `tls.outbound` in the [configuration reference](../../docs/reference/configuration.md).

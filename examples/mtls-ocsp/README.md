# Client certificates (mTLS) with OCSP revocation checking and OCSP stapling

Tomcat 10.1 with a real HTTPS connector and Rocoto's built-in certificate authentication:

- `gen-certs.sh` creates a demo CA, a server certificate with an OCSP URL (AIA extension), a valid
  client certificate (`ops-alice`), a **revoked** client certificate (`ops-mallory`) and a
  delegated OCSP responder certificate.
- The `ocsp` service is an `openssl ocsp` responder for that CA.
- Tomcat requests a client certificate (`certificateVerification="optional"`) and only checks that
  it chains to the demo CA. Rocoto maps the subject DN to roles (`certificates:` in
  `rocoto-users.yaml`) and checks revocation with `auth.builtin.mtls.revocation: hard-fail`.
- `-Djdk.tls.server.enableStatusRequestExtension=true` makes the JDK staple an OCSP response for
  the server certificate; `tls.selfProbe` lets Rocoto watch that.

> **LOCAL DEMO ONLY - never use in production.** The transport is real TLS and
> `auth.allowInsecureTransport` is not set, but the PKI is a throw-away demo PKI: unencrypted keys,
> store password `demo-changeit-123` (public).

| File | Purpose |
|------|---------|
| `gen-certs.sh` | creates `certs/` (not checked in) |
| `docker-compose.yaml` | `ocsp` responder and `tomcat` |
| `conf/server.xml` | HTTPS connector on 8443, JSSE, optional client certificates |
| `conf/rocoto.yaml` | `auth.builtin.mtls`, policy, audit file, TLS self probe |
| `conf/rocoto-users.yaml` | certificate mapping `CN=ops-*,OU=Operations,O=Rocoto Demo,C=DE` -> `rocoto-operator` |

## Prerequisites

Docker with the compose plugin, `make`, `curl`, `openssl` 1.1.1 or newer, `keytool` (any JDK; the
script falls back to running `keytool` in the Tomcat image). `rocoto.war` built with
`mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central).

## Run

```sh
./gen-certs.sh     # or: make certs
make up
make test
make down
```

## What to try

```sh
B=https://localhost:8443/rocoto

# Valid certificate: the name is the CN, the role comes from the mapping
curl -s --cacert certs/ca.crt --cert certs/client.crt --key certs/client.key $B/api/v1/server
# {"rocoto":{"version":"0.1.0-SNAPSHOT","authMode":"builtin"},"server":{...},
#  "caller":{"name":"ops-alice","roles":["rocoto-operator"],"authenticatedBy":"mtls"}}

# Revoked certificate: the TLS handshake succeeds, Rocoto rejects the caller
curl -s -w ' %{http_code}\n' --cacert certs/ca.crt --cert certs/revoked.crt --key certs/revoked.key $B/api/v1/server
# {"status":401,"error":"Authentication required"} 401

docker compose exec tomcat tail -n 1 /usr/local/tomcat/logs/rocoto-audit.log
# {"ts":"...","user":"CN=ops-mallory,OU=Operations,O=Rocoto Demo,C=DE","auth":"builtin","remote":"...",
#  "channel":"auth","action":"LOGIN","outcome":"AUTH_FAILED","reason":"client certificate rejected: certificate revoked"}

# No certificate at all
curl -s -o /dev/null -w '%{http_code}\n' --cacert certs/ca.crt $B/api/v1/server
# 401

# Ask the responder yourself
openssl ocsp -issuer certs/ca.crt -CAfile certs/ca.crt -cert certs/revoked.crt -url http://localhost:2560
# certs/revoked.crt: revoked ... Reason: keyCompromise
```

### OCSP stapling

```sh
java -jar ../../rocoto-tools/target/rocoto-tools.jar tls check localhost:8443
# Connected to localhost:8443 using TLSv1.3 / TLS_AES_256_GCM_SHA384
#
# Certificate chain:
#   [0] CN=localhost,O=Rocoto Demo,C=DE
#       issuer:  CN=Rocoto Demo CA,O=Rocoto Demo,C=DE
#       expires: 2027-09-18T20:04:44Z  (364 days)
#   [1] CN=Rocoto Demo CA,O=Rocoto Demo,C=DE
#       ...
#
# OCSP stapling: YES
#   response status:    successful
#   certificate status: good

echo | openssl s_client -connect localhost:8443 -status -CAfile certs/ca.crt 2>/dev/null | grep -A 6 'OCSP response'
# OCSP response:
# ======================================
# OCSP Response Data:
#     OCSP Response Status: successful (0x0)
#     Response Type: Basic OCSP Response
#     Version: 1 (0x0)
#     Responder Id: C = DE, O = Rocoto Demo, CN = Rocoto Demo OCSP Responder
```

With the self probe enabled, `/metrics` additionally contains
`rocoto_tls_cert_expiry_seconds` and `rocoto_tls_ocsp_stapled{status="good"} 1`, and
`GET /api/v1/health` lists a warning when stapling stops or the certificate is about to expire.

### Hard-fail in action

```sh
docker compose stop ocsp
sleep 35      # auth.builtin.mtls.cacheSeconds is 30 in this demo
curl -s -o /dev/null -w '%{http_code}\n' --cacert certs/ca.crt --cert certs/client.crt --key certs/client.key $B/api/v1/server
# 401 - audit reason: "client certificate rejected: revocation status could not be determined"
docker compose start ocsp
```

## What was verified

Docker was not available when this example was written, so the Compose file was not executed.
Everything else was: `gen-certs.sh` (OpenSSL 3.6, `OCSP_URL=http://localhost:2560`), a local
`openssl ocsp` responder, `rocoto.war` in an embedded Tomcat 10.1.34 configured like
`conf/server.xml` (JSSE, optional client certificates, JDK 21 with
`-Djdk.tls.server.enableStatusRequestExtension=true`) and `test.sh` against it. The outputs above
come from that run. Not verified: the `alpine/openssl` image (its entrypoint is assumed to be
`openssl`) and the property replacement `${demo.store.password}` in `server.xml`.

## Notes

- `truststore` in `rocoto.yaml` must be an absolute path; only `usersFile` and metric profiles are
  resolved relative to `rocoto.yaml`.
- A Java PKCS12 trust store needs trusted-certificate entries; create it with `keytool -importcert`
  as the script does. A `.p12` written by `openssl pkcs12 -export -nokeys` is usually empty for Java.
- Tomcat logs a warning that JSSE does not support TLS 1.3 post-handshake authentication. It does
  not matter here: the certificate is requested during the initial handshake for the whole connector.
- An `Authorization` header wins over the certificate: a request that carries both is
  authenticated by the header.
- Rocoto warns at start-up when `rocoto-users.yaml` is readable by other users. With bind mounts
  the file mode of the host applies - `chmod 600` it where the container user allows.

## What to look at next

Cookbook: [wildfly-mtls-ocsp](../../docs/examples/wildfly-mtls-ocsp.md) for the same on WildFly.

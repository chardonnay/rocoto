# WildFly with client certificates, OCSP revocation checking and OCSP stapling

## Goal

Machines and operators authenticate with X.509 client certificates. WildFly's HTTPS listener
requests the certificate and checks that it chains to your CA; Rocoto maps the subject to roles
and checks revocation (OCSP first, CRL as fallback) in `hard-fail` mode. The server certificate's
own OCSP response is stapled into the handshake by the JDK.

The runnable project for this scenario uses Tomcat:
[`examples/mtls-ocsp`](../../examples/mtls-ocsp/README.md). Its PKI script, responder and Rocoto
configuration are reused here unchanged; only the listener configuration is WildFly specific.

## Prerequisites

- WildFly 27+, JDK 17+, `openssl`, `keytool`
- `rocoto.war`, `rocoto-tools.jar` (`mvn install -DskipTests` in the repository root)
- A PKI whose certificates carry an OCSP responder URL (AIA extension) and a reachable responder.
  For a demo: `OCSP_URL=http://localhost:2560 examples/mtls-ocsp/gen-certs.sh`

## Files

The demo PKI (demo only - unencrypted keys, public store password):

[`examples/mtls-ocsp/gen-certs.sh`](../../examples/mtls-ocsp/gen-certs.sh)

```sh
#!/usr/bin/env bash
# Creates a throw-away demo PKI in ./certs:
#   ca.crt / ca.key              demo CA (also keeps the openssl "ca" database: index.txt, serial)
#   ocsp.crt / ocsp.key          delegated OCSP responder certificate (extendedKeyUsage OCSPSigning)
#   server.crt / server.p12      server certificate for localhost with an AIA OCSP URL
#   client.crt / client.key      valid client certificate    CN=ops-alice,OU=Operations,O=Rocoto Demo,C=DE
#   revoked.crt / revoked.key    revoked client certificate  CN=ops-mallory,OU=Operations,O=Rocoto Demo,C=DE
#   ca.p12                       PKCS12 trust store with the CA certificate (for Tomcat and Rocoto)
#
# DEMO ONLY: keys are unencrypted and the store password is public. Never use this PKI anywhere else.
#
# Environment:
#   OCSP_URL        responder URL written into the certificates (default http://ocsp:2560, the
#                   compose service name - it must be resolvable by the Tomcat JVM)
#   STORE_PASSWORD  password of server.p12 and ca.p12 (default demo-changeit-123)
set -euo pipefail

OCSP_URL="${OCSP_URL:-http://ocsp:2560}"
STORE_PASSWORD="${STORE_PASSWORD:-demo-changeit-123}"
DIR="$(cd "$(dirname "$0")" && pwd)/certs"
SUBJECT_BASE="/C=DE/O=Rocoto Demo"

rm -rf "$DIR"
mkdir -p "$DIR/newcerts"
cd "$DIR"
: > index.txt
echo 1000 > serial

cat > openssl.cnf <<CNF
[ ca ]
default_ca = demo_ca

[ demo_ca ]
dir              = .
database         = \$dir/index.txt
new_certs_dir    = \$dir/newcerts
serial           = \$dir/serial
certificate      = \$dir/ca.crt
private_key      = \$dir/ca.key
default_md       = sha256
default_days     = 365
policy           = policy_any
unique_subject   = no
copy_extensions  = none

[ policy_any ]
countryName            = optional
organizationName       = optional
organizationalUnitName = optional
commonName             = supplied

[ req ]
distinguished_name = req_dn
prompt             = no

[ req_dn ]
CN = unused

[ v3_ca ]
basicConstraints     = critical, CA:TRUE, pathlen:0
keyUsage             = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash

[ v3_ocsp ]
basicConstraints       = critical, CA:FALSE
keyUsage               = critical, digitalSignature
extendedKeyUsage       = critical, OCSPSigning
noCheck                = ignored
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid

[ v3_server ]
basicConstraints       = critical, CA:FALSE
keyUsage               = critical, digitalSignature, keyEncipherment
extendedKeyUsage       = serverAuth
subjectAltName         = DNS:localhost, DNS:tomcat, IP:127.0.0.1
authorityInfoAccess    = OCSP;URI:${OCSP_URL}
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid

[ v3_client ]
basicConstraints       = critical, CA:FALSE
keyUsage               = critical, digitalSignature
extendedKeyUsage       = clientAuth
authorityInfoAccess    = OCSP;URI:${OCSP_URL}
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid
CNF

echo "== CA"
openssl req -x509 -newkey rsa:3072 -nodes -keyout ca.key -out ca.crt -days 730 -sha256 \
  -subj "$SUBJECT_BASE/CN=Rocoto Demo CA" -config openssl.cnf -extensions v3_ca

# issue <name> <subject> <extension section>
issue() {
  local name="$1" subject="$2" extensions="$3"
  openssl req -new -newkey rsa:2048 -nodes -keyout "$name.key" -out "$name.csr" -subj "$subject" -config openssl.cnf
  openssl ca -batch -config openssl.cnf -extensions "$extensions" -in "$name.csr" -out "$name.crt" -notext
  rm -f "$name.csr"
}

echo "== OCSP responder certificate"
issue ocsp "$SUBJECT_BASE/CN=Rocoto Demo OCSP Responder" v3_ocsp
echo "== server certificate (AIA: $OCSP_URL)"
issue server "$SUBJECT_BASE/CN=localhost" v3_server
echo "== client certificates"
issue client "$SUBJECT_BASE/OU=Operations/CN=ops-alice" v3_client
issue revoked "$SUBJECT_BASE/OU=Operations/CN=ops-mallory" v3_client

echo "== revoking ops-mallory"
openssl ca -batch -config openssl.cnf -revoke revoked.crt -crl_reason keyCompromise

echo "== key store for Tomcat"
openssl pkcs12 -export -name server -inkey server.key -in server.crt -certfile ca.crt \
  -out server.p12 -passout "pass:$STORE_PASSWORD"

echo "== trust store (a Java trust store needs 'trusted certificate' entries, hence keytool)"
if command -v keytool >/dev/null 2>&1; then
  keytool -importcert -noprompt -alias rocoto-demo-ca -file ca.crt -keystore ca.p12 \
    -storetype PKCS12 -storepass "$STORE_PASSWORD"
else
  docker run --rm -v "$DIR:/certs" tomcat:10.1-jdk17 keytool -importcert -noprompt \
    -alias rocoto-demo-ca -file /certs/ca.crt -keystore /certs/ca.p12 -storetype PKCS12 \
    -storepass "$STORE_PASSWORD"
fi

# The containers run as different users; this is a demo PKI, so make everything readable.
chmod 644 ./*.key ./*.p12 index.txt

echo
echo "done - certificates are in $DIR"
openssl x509 -in server.crt -noout -subject -ext authorityInfoAccess
cat index.txt
```

WildFly listener - copy `certs/server.p12` and `certs/ca.p12` to `standalone/configuration/` first:

```text
# Review and adapt before use; written against the WildFly 27+ management model
# DEMO ONLY: clear-text store passwords. Use a credential store in production.

/subsystem=elytron/key-store=rocotoServerKS:add(path=server.p12, relative-to=jboss.server.config.dir, type=PKCS12, credential-reference={clear-text="demo-changeit-123"})
/subsystem=elytron/key-manager=rocotoServerKM:add(key-store=rocotoServerKS, credential-reference={clear-text="demo-changeit-123"})

/subsystem=elytron/key-store=rocotoClientCaTS:add(path=ca.p12, relative-to=jboss.server.config.dir, type=PKCS12, credential-reference={clear-text="demo-changeit-123"})
/subsystem=elytron/trust-manager=rocotoClientCaTM:add(key-store=rocotoClientCaTS)

# want-client-auth = the certificate is optional on TLS level; Rocoto rejects anonymous requests.
# need-client-auth would lock out clients that use Basic, API tokens or JWT.
/subsystem=elytron/server-ssl-context=rocotoSSC:add(key-manager=rocotoServerKM, trust-manager=rocotoClientCaTM, want-client-auth=true, protocols=["TLSv1.3","TLSv1.2"])

/subsystem=undertow/server=default-server/https-listener=https:write-attribute(name=ssl-context, value=rocotoSSC)
reload
```

OCSP stapling is a JVM feature. Add to `bin/standalone.conf`:

```sh
JAVA_OPTS="$JAVA_OPTS -Djdk.tls.server.enableStatusRequestExtension=true"
```

`standalone/configuration/rocoto.yaml`:

```yaml
auth:
  mode: builtin                     # Rocoto reads the certificate from the request itself
  builtin:
    usersFile: rocoto-users.yaml
    mtls:
      enabled: true
      revocation: hard-fail         # off | soft-fail | hard-fail
      truststore: /opt/wildfly/standalone/configuration/ca.p12     # absolute path
      truststorePassword: ${env:ROCOTO_TRUSTSTORE_PASSWORD}
      truststoreType: PKCS12
      # ocspResponder: http://ocsp.internal:2560   # only if the certificates carry no usable AIA URL
      cacheSeconds: 300

tls:
  selfProbe:
    url: https://localhost:8443     # watches certificate expiry and stapling
    expectStapling: true
```

`standalone/configuration/rocoto-users.yaml` - identical to the Tomcat example:

[`examples/mtls-ocsp/conf/rocoto-users.yaml`](../../examples/mtls-ocsp/conf/rocoto-users.yaml)

```yaml
# DEMO ONLY. Certificate mappings of the mtls-ocsp example; there are no passwords or tokens.
# Rocoto built-in credentials. Managed with rocoto-tools; contains only hashes.
certificates:
  # glob over the RFC 2253 subject DN of the client certificate
  - subject: "CN=ops-*,OU=Operations,O=Rocoto Demo,C=DE"
    roles: [rocoto-operator]           # name defaults to the CN: ops-alice
```

For comparison, the Rocoto configuration of the Tomcat project:

[`examples/mtls-ocsp/conf/rocoto.yaml`](../../examples/mtls-ocsp/conf/rocoto.yaml)

```yaml
# Rocoto behind a real TLS connector. auth.allowInsecureTransport stays at its default (false):
# credentials and certificates are only accepted over HTTPS.
auth:
  mode: builtin
  builtin:
    usersFile: rocoto-users.yaml
    mtls:
      enabled: true
      revocation: hard-fail            # no OCSP/CRL answer = rejected
      truststore: /usr/local/tomcat/conf/certs/ca.p12   # absolute path
      truststorePassword: ${env:ROCOTO_TRUSTSTORE_PASSWORD}
      truststoreType: PKCS12
      # The client certificates carry the responder URL (AIA extension, http://ocsp:2560).
      # Set this only if yours do not, or if the URL in them is not reachable from the server:
      # ocspResponder: http://ocsp:2560
      cacheSeconds: 30                 # demo: re-check quickly; default 300

policy:
  rules:
    - roles: [rocoto-operator, rocoto-admin]
      allow: [exec]
      mbean: "java.lang:type=Memory"
      operations: [gc]

audit:
  file: /usr/local/tomcat/logs/rocoto-audit.log

tls:
  selfProbe:
    url: https://localhost:8443        # TLS watchdog: certificate expiry and OCSP stapling
    expectStapling: true
```

## Commands

```sh
cd examples/mtls-ocsp
OCSP_URL=http://localhost:2560 ./gen-certs.sh
(cd certs && openssl ocsp -port 2560 -index index.txt -CA ca.crt -rsigner ocsp.crt -rkey ocsp.key -nmin 5 -text) &

cp certs/server.p12 certs/ca.p12 $JBOSS_HOME/standalone/configuration/
$JBOSS_HOME/bin/jboss-cli.sh -c --file=wildfly-mtls.cli          # the commands above
export ROCOTO_TRUSTSTORE_PASSWORD=demo-changeit-123              # before starting WildFly
```

## Expected output

```sh
B=https://localhost:8443/rocoto
C=examples/mtls-ocsp/certs

curl -s --cacert $C/ca.crt --cert $C/client.crt --key $C/client.key $B/api/v1/server
# ... "caller":{"name":"ops-alice","roles":["rocoto-operator"],"authenticatedBy":"mtls"}

curl -s -w ' %{http_code}\n' --cacert $C/ca.crt --cert $C/revoked.crt --key $C/revoked.key $B/api/v1/server
# {"status":401,"error":"Authentication required"} 401
# audit: "outcome":"AUTH_FAILED","reason":"client certificate rejected: certificate revoked"

java -jar rocoto-tools/target/rocoto-tools.jar tls check localhost:8443
# Connected to localhost:8443 using TLSv1.3 / TLS_AES_256_GCM_SHA384
# ...
# OCSP stapling: YES
#   response status:    successful
#   certificate status: good

echo | openssl s_client -connect localhost:8443 -status -CAfile $C/ca.crt 2>/dev/null | grep 'OCSP Response Status'
#     OCSP Response Status: successful (0x0)
```

If the responder is down, `hard-fail` rejects every certificate once the cached result
(`cacheSeconds`) has expired - audit reason `revocation status could not be determined`.
`soft-fail` accepts certificates in that situation.

**What was verified:** the PKI script, the responder, Rocoto's mTLS authentication, the rejection
of the revoked certificate, hard-fail and stapling were executed with Tomcat 10.1 (JSSE, JDK 21) -
the outputs above come from that run. The WildFly CLI commands were not executed.

## Notes

- Without stapling `tls check` prints `OCSP stapling: NO` together with the JVM option to set.
  The JDK can only staple if the server certificate has an AIA OCSP URL that the server can reach.
- The certificate mapping is a glob over the RFC 2253 subject DN
  (`CN=ops-*,OU=Operations,O=Rocoto Demo,C=DE`), optionally combined with `san`. The user name is
  the CN unless `name` is set.
- A request with an `Authorization` header is authenticated by that header, not by the certificate.
- Elytron can check revocation on TLS level as well (`ocsp` / `certificate-revocation-list` on the
  `trust-manager`). Then revoked certificates already fail the handshake and never reach Rocoto.
- Outbound connections of Rocoto (JWKS, OTLP) have their own setting: `tls.outbound.revocation`
  and `tls.outbound.requireStapling`.

## What to look at next

- User guide: [TLS and OCSP](../guide/13-tls-and-ocsp.md)
- [kubernetes-deployment](kubernetes-deployment.md) - certificates from a TLS Secret

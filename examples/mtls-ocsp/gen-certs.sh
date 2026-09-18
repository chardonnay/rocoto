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

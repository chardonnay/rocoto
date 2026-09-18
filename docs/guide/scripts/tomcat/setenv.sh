#!/bin/sh
# $CATALINA_BASE/bin/setenv.sh - enables OCSP stapling for JSSE based connectors (NIO/NIO2).
# Review and adapt before use. Not executed as part of Rocoto's automated tests.

CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.server.enableStatusRequestExtension=true"

# Optional tuning (JDK defaults shown)
# CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.stapling.responseTimeout=5000"   # ms to wait for the responder
# CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.stapling.cacheSize=256"          # cached responses, 0 = unlimited
# CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.stapling.cacheLifetime=3600"     # seconds, 0 = until nextUpdate
# CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.stapling.responderURI=http://ocsp.example.com"
# CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.stapling.responderOverride=false"
# CATALINA_OPTS="$CATALINA_OPTS -Djdk.tls.stapling.ignoreExtensions=false"

# Only if client certificates are verified by the connector (certificateVerification) with
# revocationEnabled="true": let the JDK's default PKIX revocation checking use OCSP and
# CRL distribution points.
# CATALINA_OPTS="$CATALINA_OPTS -Dcom.sun.security.enableCRLDP=true"
# and in a java.security override file:  ocsp.enable=true

export CATALINA_OPTS

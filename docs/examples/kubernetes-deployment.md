# Kubernetes deployment

## Goal

Run an application server with Rocoto in Kubernetes: `rocoto.yaml` from a ConfigMap, the user
store from a Secret, TLS inside the pod, probes on the liveness endpoint and a Prometheus Operator
`ServiceMonitor` that authenticates with a bearer token from a Secret.

Runnable project: [`examples/kubernetes`](../../examples/kubernetes/README.md).

## Prerequisites

- A cluster, `kubectl`, a container registry; for the last manifest the Prometheus Operator CRDs
- Docker (image build), `openssl` or cert-manager for the certificate
- `mvn install -DskipTests` in the repository root (Rocoto is not on Maven Central)

## TLS first

Rocoto refuses credentials on requests for which `request.isSecure()` is false
(`403 Credentials are only accepted over TLS`). **`auth.allowInsecureTransport` is not an option
for a cluster.** Either

- **A. serve HTTPS from the pod** - what the manifests below do (certificate from a
  `kubernetes.io/tls` Secret), or
- **B. terminate TLS at the ingress or a sidecar and make the application server trust
  `X-Forwarded-Proto`**, so that `request.isSecure()` becomes true:

  ```xml
  <!-- Tomcat server.xml, inside <Engine> or <Host>; restrict internalProxies to your ingress -->
  <Valve className="org.apache.catalina.valves.RemoteIpValve"
         internalProxies="10\.\d+\.\d+\.\d+"
         remoteIpHeader="X-Forwarded-For"
         protocolHeader="X-Forwarded-Proto"
         protocolHeaderHttpsValue="https"/>
  ```

  ```text
  # WildFly / Undertow. Review and adapt before use; written against the WildFly 27+ management model
  /subsystem=undertow/server=default-server/http-listener=default:write-attribute(name=proxy-address-forwarding, value=true)
  ```

  Only do this on a listener that is reachable exclusively through the proxy - otherwise clients
  can forge the header. The valve also restores the real client address, which the per-address
  lockout (`auth.builtin.lockout.addressMaxAttempts`) and the audit log rely on.

## Files

The image:

[`examples/kubernetes/image/Dockerfile`](../../examples/kubernetes/image/Dockerfile)

```dockerfile
# Tomcat 10.1 with rocoto.war and an HTTPS-only connector. Configuration and secrets are NOT part
# of the image - they are mounted from a ConfigMap and Secrets (see ../manifests).
#
# Build from the repository root so that the WAR is inside the build context:
#   docker build -f examples/kubernetes/image/Dockerfile -t registry.example.com/rocoto-tomcat:0.1.0 .
FROM tomcat:10.1-jdk17

COPY examples/kubernetes/image/server.xml /usr/local/tomcat/conf/server.xml
COPY rocoto-war/target/rocoto.war /usr/local/tomcat/webapps/rocoto.war
# Your own applications go next to it:
# COPY my-app.war /usr/local/tomcat/webapps/

# Run as an unprivileged user; Tomcat needs to write to logs, work, temp and webapps (WAR unpacking).
RUN useradd --uid 10001 --gid 0 --no-create-home --shell /usr/sbin/nologin tomcat \
 && chown -R 10001:0 /usr/local/tomcat \
 && chmod -R g=u /usr/local/tomcat
USER 10001

EXPOSE 8443
```

[`examples/kubernetes/image/server.xml`](../../examples/kubernetes/image/server.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
  HTTPS only. Certificate and key are PEM files mounted from a kubernetes.io/tls Secret
  (cert-manager or "kubectl create secret"). sslImplementationName forces JSSE, which is required
  for OCSP stapling by the JDK (-Djdk.tls.server.enableStatusRequestExtension=true).
-->
<Server port="-1">
  <Listener className="org.apache.catalina.startup.VersionLoggerListener"/>
  <Listener className="org.apache.catalina.core.JreMemoryLeakPreventionListener"/>
  <Listener className="org.apache.catalina.mbeans.GlobalResourcesLifecycleListener"/>
  <Listener className="org.apache.catalina.core.ThreadLocalLeakPreventionListener"/>

  <Service name="Catalina">
    <Connector port="8443" protocol="org.apache.coyote.http11.Http11NioProtocol"
               SSLEnabled="true" scheme="https" secure="true" maxThreads="100"
               sslImplementationName="org.apache.tomcat.util.net.jsse.JSSEImplementation">
      <SSLHostConfig protocols="TLSv1.2+TLSv1.3">
        <Certificate certificateFile="/etc/rocoto-tls/tls.crt"
                     certificateKeyFile="/etc/rocoto-tls/tls.key"/>
      </SSLHostConfig>
    </Connector>

    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="true" autoDeploy="false"/>
    </Engine>
  </Service>
</Server>
```

The manifests:

[`examples/kubernetes/manifests/00-namespace.yaml`](../../examples/kubernetes/manifests/00-namespace.yaml)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: rocoto-demo
```

[`examples/kubernetes/manifests/10-configmap.yaml`](../../examples/kubernetes/manifests/10-configmap.yaml)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: rocoto-config
  namespace: rocoto-demo
  labels:
    app.kubernetes.io/name: rocoto-demo
data:
  # Found through the environment variable ROCOTO_CONFIG (see the Deployment).
  # auth.allowInsecureTransport is deliberately absent: the pod serves HTTPS.
  rocoto.yaml: |
    auth:
      mode: builtin
      builtin:
        # absolute path: the user store comes from a Secret, not from this ConfigMap
        usersFile: /etc/rocoto-secrets/rocoto-users.yaml
        lockout:
          # Behind an ingress or a service mesh all clients may share one source address.
          # 0 disables the per-address lockout; the per-user lockout stays active.
          addressMaxAttempts: 0

    policy:
      rules:
        - roles: [rocoto-operator, rocoto-admin]
          allow: [exec]
          mbean: "java.lang:type=Memory"
          operations: [gc]

    # No audit.file: audit events always go to the io.rocoto.audit logger and therefore to the
    # container log, where the cluster's log shipping picks them up.

    health:
      allowAnonymous: true        # liveness/readiness probes cannot send credentials easily

    metrics:
      profiles: [jvm, auto]

    tls:
      selfProbe:
        url: https://localhost:8443
        expectStapling: false     # set to true once the certificate has an OCSP responder and
                                  # the JVM runs with -Djdk.tls.server.enableStatusRequestExtension=true
```

Demo content only (password `demo-password-123`, token
`rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY`) - create your own Secret from a
file written by `rocoto-tools`:

[`examples/kubernetes/manifests/20-secret-users.yaml`](../../examples/kubernetes/manifests/20-secret-users.yaml)

```yaml
# DEMO ONLY - never apply this file to a real cluster. It contains the demo user store whose
# password (demo-password-123) and token are published in the README. Create your own with
# rocoto-tools and load it with:
#   kubectl -n rocoto-demo create secret generic rocoto-users --from-file=rocoto-users.yaml
apiVersion: v1
kind: Secret
metadata:
  name: rocoto-users
  namespace: rocoto-demo
  labels:
    app.kubernetes.io/name: rocoto-demo
type: Opaque
stringData:
  rocoto-users.yaml: |
    # Rocoto built-in credentials. Managed with rocoto-tools; contains only hashes.
    tokens:
      - id: prometheus
        hash: sha256$IFn54hbJrL/j2yObb5xTynOJcp67SMRZ4cNmhUZtt3Y
        roles:
          - rocoto-reader
    users:
      - name: rita
        password: pbkdf2-sha256$600000$kZDIpomyMrgTrUj8op6mzQ$QtBQ227/KFDMz/TAcA1gt4cmsQsEQGjYoEmDPQFck7M
        roles:
          - rocoto-reader
      - name: ada
        password: pbkdf2-sha256$600000$CyJvHrNzgc2bbZ+x8NIfjA$RNaX/KUf16YqnmuV0I0i5BRt/xlNAYE22oXlL/XsXPM
        roles:
          - rocoto-admin
```

[`examples/kubernetes/manifests/21-secret-prometheus-token.yaml`](../../examples/kubernetes/manifests/21-secret-prometheus-token.yaml)

```yaml
# DEMO ONLY - the clear-text API token Prometheus presents (the user store above only has its hash).
# Must live in the namespace of the ServiceMonitor.
apiVersion: v1
kind: Secret
metadata:
  name: rocoto-prometheus-token
  namespace: rocoto-demo
  labels:
    app.kubernetes.io/name: rocoto-demo
type: Opaque
stringData:
  token: rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY
```

[`examples/kubernetes/manifests/30-deployment.yaml`](../../examples/kubernetes/manifests/30-deployment.yaml)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rocoto-demo
  namespace: rocoto-demo
  labels:
    app.kubernetes.io/name: rocoto-demo
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: rocoto-demo
  template:
    metadata:
      labels:
        app.kubernetes.io/name: rocoto-demo
    spec:
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 0
        fsGroup: 0
      containers:
        - name: tomcat
          image: registry.example.com/rocoto-tomcat:0.1.0   # built from ../image/Dockerfile
          ports:
            - name: https
              containerPort: 8443
          env:
            - name: ROCOTO_CONFIG
              value: /etc/rocoto/rocoto.yaml
            - name: CATALINA_OPTS
              value: "-XX:MaxRAMPercentage=75"
          volumeMounts:
            - name: config
              mountPath: /etc/rocoto
              readOnly: true
            - name: users
              mountPath: /etc/rocoto-secrets
              readOnly: true
            - name: tls
              mountPath: /etc/rocoto-tls
              readOnly: true
          startupProbe:
            httpGet: { path: /rocoto/api/v1/health/live, port: https, scheme: HTTPS }
            periodSeconds: 5
            failureThreshold: 30
          livenessProbe:
            httpGet: { path: /rocoto/api/v1/health/live, port: https, scheme: HTTPS }
            periodSeconds: 20
          readinessProbe:
            httpGet: { path: /rocoto/api/v1/health/live, port: https, scheme: HTTPS }
            periodSeconds: 10
          resources:
            requests: { cpu: 100m, memory: 384Mi }
            limits: { memory: 768Mi }
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop: ["ALL"]
      volumes:
        - name: config
          configMap:
            name: rocoto-config
        - name: users
          secret:
            secretName: rocoto-users
            defaultMode: 0440        # owner root, group 0 = the group of the container user
        - name: tls
          secret:
            secretName: rocoto-tls   # kubernetes.io/tls: tls.crt, tls.key (+ ca.crt) - see README
            defaultMode: 0440
```

[`examples/kubernetes/manifests/40-service.yaml`](../../examples/kubernetes/manifests/40-service.yaml)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: rocoto-demo
  namespace: rocoto-demo
  labels:
    app.kubernetes.io/name: rocoto-demo
spec:
  selector:
    app.kubernetes.io/name: rocoto-demo
  ports:
    - name: https
      port: 8443
      targetPort: https
```

[`examples/kubernetes/manifests/50-servicemonitor.yaml`](../../examples/kubernetes/manifests/50-servicemonitor.yaml)

```yaml
# Requires the Prometheus Operator CRDs (monitoring.coreos.com/v1). Depending on how your
# Prometheus resource selects ServiceMonitors you may need an extra label, e.g. "release: kube-prometheus-stack".
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: rocoto-demo
  namespace: rocoto-demo
  labels:
    app.kubernetes.io/name: rocoto-demo
spec:
  selector:
    matchLabels:
      app.kubernetes.io/name: rocoto-demo
  endpoints:
    - port: https
      scheme: https
      path: /rocoto/metrics
      interval: 30s
      # Sent as "Authorization: Bearer <token>". Older operator versions: use
      #   bearerTokenSecret: { name: rocoto-prometheus-token, key: token }
      authorization:
        type: Bearer
        credentials:
          name: rocoto-prometheus-token
          key: token
      tlsConfig:
        serverName: rocoto-demo.rocoto-demo.svc
        ca:
          secret:
            name: rocoto-tls
            key: ca.crt
```

## Commands

```sh
# image (from the repository root)
docker build -f examples/kubernetes/image/Dockerfile -t registry.example.com/rocoto-tomcat:0.1.0 .
docker push registry.example.com/rocoto-tomcat:0.1.0

cd examples/kubernetes
kubectl apply -f manifests/00-namespace.yaml

# TLS Secret: cert-manager Certificate with secretName rocoto-tls, or a self-signed demo certificate
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout tls.key -out tls.crt \
  -subj "/CN=rocoto-demo.rocoto-demo.svc" \
  -addext "subjectAltName=DNS:rocoto-demo.rocoto-demo.svc,DNS:rocoto-demo.rocoto-demo.svc.cluster.local,DNS:localhost"
kubectl -n rocoto-demo create secret generic rocoto-tls --type=kubernetes.io/tls \
  --from-file=tls.crt --from-file=tls.key --from-file=ca.crt=tls.crt

# your own user store and scraper token (or, for a quick demo, apply manifests/20-*.yaml and 21-*.yaml)
java -jar ../../rocoto-tools/target/rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
java -jar ../../rocoto-tools/target/rocoto-tools.jar token create prometheus --roles rocoto-reader \
     --mbeans "java.lang:*;java.nio:*;Catalina:*" --file rocoto-users.yaml
kubectl -n rocoto-demo create secret generic rocoto-users --from-file=rocoto-users.yaml
kubectl -n rocoto-demo create secret generic rocoto-prometheus-token --from-literal=token='rct_prometheus_...'

kubectl apply -f manifests/10-configmap.yaml -f manifests/30-deployment.yaml -f manifests/40-service.yaml
kubectl apply -f manifests/50-servicemonitor.yaml
kubectl -n rocoto-demo rollout status deployment/rocoto-demo
```

## Expected output

```sh
kubectl -n rocoto-demo port-forward svc/rocoto-demo 8443:8443 &

curl -s --cacert tls.crt https://localhost:8443/rocoto/api/v1/health/live
# {"status":"UP"}

curl -s --cacert tls.crt -u ada:demo-password-123 https://localhost:8443/rocoto/api/v1/server
# {"rocoto":{"version":"0.1.0-SNAPSHOT","authMode":"builtin"},"server":{"adapter":"tomcat",...},
#  "caller":{"name":"ada","roles":["rocoto-admin"],"authenticatedBy":"basic"}}

curl -s --cacert tls.crt -u ada:demo-password-123 https://localhost:8443/rocoto/api/v1/health
# {"status":"UP","version":"0.1.0-SNAPSHOT","adapter":"tomcat","visibleMBeans":...,"activeSubscriptions":0,"warnings":[]}
```

In Prometheus the target `serviceMonitor/rocoto-demo/rocoto-demo/0` is `UP`.

**Not executed:** neither Docker nor a cluster were available when this page was written. The
manifests were checked for YAML syntax; the response bodies are those of the same Rocoto build on
an embedded Tomcat, where the HTTPS connector with PEM files was verified as well.

## Notes

- `ROCOTO_CONFIG` pointing to a missing file, or an invalid `rocoto.yaml`, makes Rocoto fail
  closed (`503`); the startup probe then never succeeds and the rollout stops - intended.
- `usersFile` is an absolute path into the Secret mount. Mount Secrets as directories; `subPath`
  mounts are not updated when the Secret changes, directory mounts are, and Rocoto re-reads the
  user store when the file changes.
- Secret values for other settings: `${file:/etc/rocoto-secrets/<key>}` or `${env:NAME}`.
- No `audit.file`: audit events are always logged through the `io.rocoto.audit` logger and end up
  in the container log.
- Lockout counters and subscriptions are per pod. With several replicas a client may need more
  failed attempts before it is locked out; streaming connections stay on the pod they reached.
- For WildFly mount the ConfigMap the same way and keep `ROCOTO_CONFIG`, or mount it as
  `/opt/jboss/wildfly/standalone/configuration/rocoto.yaml`.

## What to look at next

- [prometheus-grafana](prometheus-grafana.md), [wildfly-mtls-ocsp](wildfly-mtls-ocsp.md)
- User guide: [Security hardening](../guide/11-security-hardening.md)

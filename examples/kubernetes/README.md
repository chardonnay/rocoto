# Kubernetes deployment

A Tomcat pod with `rocoto.war`, configured the Kubernetes way:

| Manifest | Content |
|----------|---------|
| `manifests/00-namespace.yaml` | namespace `rocoto-demo` |
| `manifests/10-configmap.yaml` | `rocoto.yaml`, mounted at `/etc/rocoto` and located through `ROCOTO_CONFIG` |
| `manifests/20-secret-users.yaml` | `rocoto-users.yaml` (hashes) - **demo content, replace it** |
| `manifests/21-secret-prometheus-token.yaml` | the clear-text API token for Prometheus - **demo content** |
| `manifests/30-deployment.yaml` | Deployment: non-root, probes on `/rocoto/api/v1/health/live` over HTTPS |
| `manifests/40-service.yaml` | ClusterIP Service, port `https` (8443) |
| `manifests/50-servicemonitor.yaml` | Prometheus Operator `ServiceMonitor` with a bearer token Secret and a CA |
| `image/Dockerfile`, `image/server.xml` | the image: Tomcat 10.1, HTTPS-only connector reading PEM files from a TLS Secret |

Demo credentials (demo only, identical to the other examples): users `rita` (`rocoto-reader`) and
`ada` (`rocoto-admin`) with the password `demo-password-123`, token
`rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY` (`rocoto-reader`).

The image and the manifests were applied to a single-node k3s cluster (v1.34, Docker 29, September 2026): rollout with
the HTTPS probes, the checks below through `kubectl port-forward`, audit lines in the pod log.
The `ServiceMonitor` was accepted by the Prometheus Operator CRD (v0.76); no operator scraped it.

## Credentials require TLS

Rocoto refuses passwords, tokens and JWTs on requests for which `request.isSecure()` is false
(`403 Credentials are only accepted over TLS`). **Do not "fix" this with
`auth.allowInsecureTransport` in a cluster** - that flag is for local demos. There are two proper
ways:

**A. TLS in the pod (what these manifests do).** Tomcat has an HTTPS connector; certificate and
key come from a `kubernetes.io/tls` Secret. Probes, the ServiceMonitor and an ingress with TLS
re-encryption or passthrough all talk HTTPS to the pod.

**B. TLS terminated in front of the pod** (ingress controller, service mesh sidecar). The pod
receives plain HTTP, so the application server has to be told to trust the proxy's
`X-Forwarded-Proto` header; then `request.isSecure()` is true and Rocoto accepts credentials.

- Tomcat - inside `<Engine>` or `<Host>` of `server.xml`; restrict `internalProxies` to the
  addresses of your ingress controller, otherwise any client can forge the header:

  ```xml
  <Valve className="org.apache.catalina.valves.RemoteIpValve"
         internalProxies="10\.\d+\.\d+\.\d+"
         remoteIpHeader="X-Forwarded-For"
         protocolHeader="X-Forwarded-Proto"
         protocolHeaderHttpsValue="https"/>
  ```

- WildFly / Undertow - on the HTTP listener the proxy connects to (review and adapt before use;
  written against the WildFly 27+ management model):

  ```text
  /subsystem=undertow/server=default-server/http-listener=default:write-attribute(name=proxy-address-forwarding, value=true)
  ```

  Only enable it on a listener that is reachable exclusively through the proxy.

With variant B, keep `auth.builtin.lockout.addressMaxAttempts` in mind: without the valve all
clients share the proxy's address. Also restrict pod-to-pod traffic with a NetworkPolicy, because
the hop between proxy and pod is unencrypted.

## Steps

```sh
# 0. Build the WAR (Rocoto is not on Maven Central) and the image; push it to your registry
mvn install -DskipTests                         # in the repository root
docker build -f examples/kubernetes/image/Dockerfile -t registry.example.com/rocoto-tomcat:0.1.0 .
docker push registry.example.com/rocoto-tomcat:0.1.0
# then set the image name in manifests/30-deployment.yaml

cd examples/kubernetes
kubectl apply -f manifests/00-namespace.yaml

# 1. TLS Secret. With cert-manager: create a Certificate with secretName rocoto-tls and the DNS
#    names below - it also fills ca.crt. Without it, a self-signed demo certificate:
openssl req -x509 -newkey rsa:2048 -nodes -days 30 -keyout tls.key -out tls.crt \
  -subj "/CN=rocoto-demo.rocoto-demo.svc" \
  -addext "subjectAltName=DNS:rocoto-demo.rocoto-demo.svc,DNS:rocoto-demo.rocoto-demo.svc.cluster.local,DNS:localhost"
kubectl -n rocoto-demo create secret generic rocoto-tls --type=kubernetes.io/tls \
  --from-file=tls.crt --from-file=tls.key --from-file=ca.crt=tls.crt

# 2. Your own user store instead of manifests/20-secret-users.yaml (recommended)
java -jar ../../rocoto-tools/target/rocoto-tools.jar user add ada --roles rocoto-admin --file rocoto-users.yaml
java -jar ../../rocoto-tools/target/rocoto-tools.jar token create prometheus --roles rocoto-reader \
     --mbeans "java.lang:*;java.nio:*;Catalina:*" --file rocoto-users.yaml      # prints the token once
kubectl -n rocoto-demo create secret generic rocoto-users --from-file=rocoto-users.yaml
kubectl -n rocoto-demo create secret generic rocoto-prometheus-token --from-literal=token='rct_prometheus_...'
#    (for a quick demo apply manifests/20-*.yaml and 21-*.yaml instead)

# 3. Everything else
kubectl apply -f manifests/10-configmap.yaml -f manifests/30-deployment.yaml -f manifests/40-service.yaml
kubectl apply -f manifests/50-servicemonitor.yaml      # needs the Prometheus Operator CRDs
kubectl -n rocoto-demo rollout status deployment/rocoto-demo
```

## Check

```sh
kubectl -n rocoto-demo port-forward svc/rocoto-demo 8443:8443 &

curl -s --cacert tls.crt https://localhost:8443/rocoto/api/v1/health/live
# {"status":"UP"}

curl -s --cacert tls.crt -u ada:demo-password-123 https://localhost:8443/rocoto/api/v1/server
# {"rocoto":{"version":"0.1.0","authMode":"builtin"},"server":{"adapter":"tomcat",...},
#  "caller":{"name":"ada","roles":["rocoto-admin"],"authenticatedBy":"basic"}}

curl -s --cacert tls.crt -H "Authorization: Bearer rct_prometheus_y7BJvUVB6oGbdKRNYXEgQ4YeKZ_MSEVIwUQuDmjmnLY" \
     https://localhost:8443/rocoto/metrics | head

# The audit trail is part of the container log
kubectl -n rocoto-demo logs deployment/rocoto-demo | grep '"outcome"'
```

## Notes

- `ROCOTO_CONFIG` must point to an existing file, otherwise Rocoto refuses to start and answers
  every request with `503` (fail closed) - the probes then keep the pod out of the Service.
- Alternatives to the environment variable: mount the ConfigMap as
  `/usr/local/tomcat/conf/rocoto.yaml` (Tomcat) or
  `/opt/jboss/wildfly/standalone/configuration/rocoto.yaml` (WildFly) with `subPath`.
- Single secrets can also be referenced from `rocoto.yaml` with `${file:/path}` or `${env:NAME}`,
  e.g. `truststorePassword: ${file:/etc/rocoto-secrets/truststore-password}`.
- The user store is re-read when the file changes. Note that `subPath` mounts are never updated
  by the kubelet; mount the whole Secret as a directory as the Deployment does.
- The Secret is mounted with mode `0440` and group 0. Rocoto logs a warning if the user store is
  readable by "others".
- Several replicas are independent: lockout counters and streaming subscriptions are per pod.

## What to look at next

Cookbook: [kubernetes-deployment](../../docs/examples/kubernetes-deployment.md),
[prometheus-grafana](../../docs/examples/prometheus-grafana.md).

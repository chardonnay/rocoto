#!/usr/bin/env bash
# rocoto.war is plain Java bytecode without native libraries: the same file must work on every
# CPU architecture. This test proves it for linux/arm64 (Apple Silicon, Graviton, ...) and
# linux/amd64 (Intel/AMD): it checks that the artifacts contain no native code, then runs the ONE
# built WAR in Tomcat containers of both architectures (the foreign one is emulated by Docker:
# Docker Desktop out of the box, on Linux after "docker run --privileged --rm tonistiigi/binfmt
# --install all") and executes the checks of examples/tomcat-builtin/test.sh against each.
#
# Usage: ci/arch/arch-test.sh [arm64] [amd64]          (default: both)
# TOMCAT_IMAGE selects the image, TOMCAT_JAVA_OPTS is passed to the JVM (see the tomcat-versions job).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="${TOMCAT_IMAGE:-tomcat:10.1-jdk17}"
NAME="rocoto-arch-test"
PORT="${PORT:-18084}"
EX="$ROOT/examples/tomcat-builtin"
FAILED=0
[ $# -gt 0 ] || set -- arm64 amd64

trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
check() { if [ "$2" = "$3" ]; then echo "  ok    $1"; else echo "  FAIL  $1 (expected '$2', got '$3')"; FAILED=1; fi; }

echo "Artifacts"
for f in rocoto-war/target/rocoto.war rocoto-tools/target/rocoto-tools.jar; do
  natives="$(unzip -Z1 "$ROOT/$f" | grep -ciE '\.(so|dylib|dll|jnilib)$' || true)"
  check "$f contains no native libraries" 0 "$natives"
done
for jar in $(unzip -Z1 "$ROOT/rocoto-war/target/rocoto.war" 'WEB-INF/lib/*.jar'); do
  natives="$(unzip -p "$ROOT/rocoto-war/target/rocoto.war" "$jar" | python3 -c '
import sys, zipfile, io, re
names = zipfile.ZipFile(io.BytesIO(sys.stdin.buffer.read())).namelist()
print(sum(1 for n in names if re.search(r"\.(so|dylib|dll|jnilib)$", n, re.I)))')"
  check "$jar contains no native libraries" 0 "$natives"
done
echo "  sha256 $(shasum -a 256 "$ROOT/rocoto-war/target/rocoto.war" | cut -d' ' -f1)  rocoto.war (the same file is used for every architecture)"

for arch in "$@"; do
  case "$arch" in arm64) machine=aarch64 ;; amd64) machine=x86_64 ;; *) echo "unknown architecture $arch"; exit 2 ;; esac
  echo "linux/$arch"
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker pull -q --platform "linux/$arch" "$IMAGE" >/dev/null
  docker run -d --platform "linux/$arch" --name "$NAME" -p "$PORT:8080" \
    ${TOMCAT_JAVA_OPTS:+-e "JAVA_OPTS=$TOMCAT_JAVA_OPTS"} \
    -v "$ROOT/rocoto-war/target/rocoto.war:/usr/local/tomcat/webapps/rocoto.war:ro" \
    -v "$ROOT/examples/demo-app/target/demo-app.war:/usr/local/tomcat/webapps/demo-app.war:ro" \
    -v "$EX/conf/rocoto.yaml:/usr/local/tomcat/conf/rocoto.yaml:ro" \
    -v "$EX/conf/rocoto-users.yaml:/usr/local/tomcat/conf/rocoto-users.yaml:ro" \
    "$IMAGE" >/dev/null
  for _ in $(seq 1 150); do     # emulation is slow
    [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/rocoto/api/v1/health/live" || true)" = "200" ] && break
    sleep 2
  done
  check "container CPU" "$machine" "$(docker exec "$NAME" uname -m)"
  check "os.arch seen through Rocoto" "$machine" "$(curl -s -u rita:demo-password-123 \
    "http://localhost:$PORT/rocoto/api/v1/mbeans/java.lang%3Atype%3DOperatingSystem/attributes/Arch" | jq -r .value | sed 's/^amd64$/x86_64/')"
  ARCH_BODY="$(curl -s -u rita:demo-password-123 "http://localhost:$PORT/rocoto/api/v1/mbeans/java.lang%3Atype%3DOperatingSystem/attributes/Arch")"
  if ! echo "$ARCH_BODY" | jq -e '.value != null' >/dev/null 2>&1; then
    # diagnostics: old JDKs in old images are known to fail on some MXBeans inside containers
    echo "  ---- response: $ARCH_BODY"
    echo "  ---- HeapMemoryUsage: $(curl -s -u rita:demo-password-123 -H 'Content-Type: application/json' \
      -d '{"mbean":"java.lang:type=Memory","attribute":"HeapMemoryUsage"}' "http://localhost:$PORT/rocoto/api/v1/read" | cut -c1-400)"
    echo "  ---- $(docker exec "$NAME" java -version 2>&1 | tr '\n' ' ')"
    echo "  ---- host kernel $(uname -r), cgroup: $(docker exec "$NAME" sh -c 'stat -fc %T /sys/fs/cgroup' 2>&1)"
    docker logs "$NAME" 2>&1 | grep -E "SEVERE|Exception|Caused by" | head -n 15 | sed 's/^/  ---- /'
  fi
  if ROCOTO_URL="http://localhost:$PORT/rocoto" "$EX/test.sh" > /tmp/rocoto-arch-$arch.log 2>&1; then
    check "examples/tomcat-builtin/test.sh ($(grep -c '^ok' /tmp/rocoto-arch-$arch.log) checks)" passed passed
  else
    grep FAIL /tmp/rocoto-arch-$arch.log || true
    check "examples/tomcat-builtin/test.sh" passed failed
  fi
done

if [ "$FAILED" = "1" ]; then echo "architecture test FAILED"; exit 1; fi
echo "architecture test passed"

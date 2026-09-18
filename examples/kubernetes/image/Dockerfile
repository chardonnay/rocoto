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

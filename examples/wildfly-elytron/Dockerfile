# LOCAL DEMO ONLY - never use in production: configure-rocoto.cli creates users with public passwords.
# Pin a release tag (quay.io/wildfly/wildfly:<version>-jdk17) for reproducible builds.
FROM quay.io/wildfly/wildfly:latest-jdk17

COPY --chown=jboss:root configure-rocoto.cli jboss-web.xml /tmp/rocoto/

RUN $JBOSS_HOME/bin/jboss-cli.sh --file=/tmp/rocoto/configure-rocoto.cli \
 && rm -rf $JBOSS_HOME/standalone/configuration/standalone_xml_history/current /tmp/rocoto

# rocoto.war, demo-app.war, rocoto.yaml and rocoto-users.yaml are mounted by docker-compose.yaml.
# To bake them into the image instead:
#   COPY rocoto.war demo-app.war $JBOSS_HOME/standalone/deployments/
#   COPY conf/rocoto.yaml conf/rocoto-users.yaml $JBOSS_HOME/standalone/configuration/

# CI smoke test image: real WildFly with Rocoto in auth.mode "chain".
# NOT a production setup - it accepts credentials over plain HTTP so the test needs no certificates.
ARG WILDFLY_IMAGE=quay.io/wildfly/wildfly:latest
FROM ${WILDFLY_IMAGE}

# An Elytron user in the default ApplicationRealm (application-security-domain "other"),
# with the group/role rocoto-admin. Demo password, only ever used inside the CI container.
RUN /opt/jboss/wildfly/bin/add-user.sh -a -u carl -p 'Container-Secret-1!' -g rocoto-admin

# Statistics feed the normalized resources and the wildfly metrics profile.
RUN /opt/jboss/wildfly/bin/jboss-cli.sh --commands="embed-server --std-out=echo,\
/subsystem=undertow:write-attribute(name=statistics-enabled,value=true),\
/subsystem=datasources/data-source=ExampleDS:write-attribute(name=statistics-enabled,value=true),\
stop-embedded-server"

COPY --chown=jboss:root rocoto.yaml rocoto-users.yaml /opt/jboss/wildfly/standalone/configuration/
COPY --chown=jboss:root rocoto.war /opt/jboss/wildfly/standalone/deployments/

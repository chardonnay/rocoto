package io.rocoto.security.policy;

import java.util.List;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.core.request.Operation;
import io.rocoto.core.request.Target;
import io.rocoto.security.Glob;

/**
 * Built-in deny list of MBeans and operations that turn a JMX bridge into remote code execution,
 * arbitrary file access or credential theft. It is evaluated before any policy rule and
 * <strong>cannot be overridden by configuration</strong>.
 *
 * <p>Every entry blocks WRITE and EXEC; reading stays subject to the normal policy.
 */
public final class Denylist {

    private record Entry(ObjectName mbean, Glob member, String why) {
    }

    private static final List<Entry> ENTRIES = List.of(
            // logback JMXConfigurator: reloadByURL loads attacker-controlled XML -> JNDI/XXE -> RCE
            entry("ch.qos.logback.classic:*", "*", "logback JMXConfigurator allows loading remote configuration"),
            // log4j2: setConfigText / setConfigLocationUri load attacker-controlled configuration
            entry("org.apache.logging.log4j2:*", "*", "log4j2 configuration can be replaced remotely"),
            // jcmd over JMX: jvmtiAgentLoad, compiler directives, heap dumps to arbitrary paths ...
            entry("com.sun.management:type=DiagnosticCommand", "*", "diagnostic commands can load native agents"),
            entry("com.sun.management:type=HotSpotDiagnostic", "*", "heap dumps and VM option changes"),
            // Flight Recorder can write recordings to arbitrary paths
            entry("jdk.management.jfr:*", "*", "flight recorder writes files to arbitrary locations"),
            // MLet: getMBeansFromURL loads remote code
            entry("*:*", "getMBeansFromURL", "MLet loads remote code"),
            entry("*:*", "addURL", "MLet loads remote code"),
            entry("javax.management.loading:*", "*", "MLet loads remote code"),
            entry("JMImplementation:*", "*", "MBeanServer delegate"),
            // WildFly / JBoss EAP: security subsystem, credential stores and the management interface itself
            entry("jboss.as:subsystem=elytron,*", "*", "Elytron security configuration"),
            entry("jboss.as.expr:subsystem=elytron,*", "*", "Elytron security configuration"),
            entry("jboss.as:core-service=management,*", "*", "management interface and access control"),
            entry("jboss.as.expr:core-service=management,*", "*", "management interface and access control"),
            entry("jboss.as:core-service=vault", "*", "vault"),
            // Tomcat: MBeanFactory can create realms, valves and connectors; Users:* edits the user database
            entry("Catalina:type=MBeanFactory", "*", "creates arbitrary Tomcat components"),
            entry("Users:*", "*", "Tomcat user database"),
            // Rocoto protects itself
            entry("io.rocoto:*", "*", "Rocoto internals"));

    private Denylist() {
    }

    /** @return the reason if the target is blocked, otherwise {@code null} */
    public static String blocked(Target target) {
        Operation op = target.operation();
        if (!op.isMutating()) {
            return null;
        }
        for (Entry e : ENTRIES) {
            if (e.mbean.apply(target.mbean()) && (target.member() == null || e.member.matches(target.member()))) {
                return e.why;
            }
        }
        return null;
    }

    private static Entry entry(String mbean, String member, String why) {
        try {
            return new Entry(new ObjectName(mbean), Glob.of(member), why);
        } catch (MalformedObjectNameException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

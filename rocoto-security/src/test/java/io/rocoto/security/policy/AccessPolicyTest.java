package io.rocoto.security.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

import io.rocoto.core.config.ConfigException;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.core.request.Target;
import io.rocoto.security.RocotoIdentity;

class AccessPolicyTest {

    private static final RocotoIdentity ADMIN = new RocotoIdentity("a", Set.of(AccessPolicy.ROLE_ADMIN), "test");

    @Test
    void secureDefaultIsReadOnly() throws Exception {
        AccessPolicy policy = AccessPolicy.secureDefault();
        ObjectName memory = new ObjectName("java.lang:type=Memory");
        assertTrue(policy.decide(ADMIN, Target.read(memory, "HeapMemoryUsage")).allowed());
        assertFalse(policy.decide(ADMIN, Target.exec(memory, "gc")).allowed());
        assertFalse(policy.decide(ADMIN, Target.write(memory, "Verbose")).allowed());
    }

    @Test
    void knownAttackSurfacesAreOnTheDenyList() throws Exception {
        String[][] dangerous = {
            {"ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator", "reloadByURL"},
            {"com.sun.management:type=DiagnosticCommand", "jvmtiAgentLoad"},
            {"com.sun.management:type=HotSpotDiagnostic", "dumpHeap"},
            {"DefaultDomain:type=MLet", "getMBeansFromURL"},
            {"jdk.management.jfr:type=FlightRecorder", "copyTo"},
            {"org.apache.logging.log4j2:type=1b6d3586", "setConfigText"},
            {"jboss.as:subsystem=elytron,security-domain=ApplicationDomain", "addIdentity"},
            {"jboss.as:core-service=management,access=authorization", "write"},
            {"Catalina:type=MBeanFactory", "createJNDIRealm"},
            {"Users:type=UserDatabase,database=UserDatabase", "createUser"},
        };
        for (String[] d : dangerous) {
            assertNotNull(Denylist.blocked(Target.exec(new ObjectName(d[0]), d[1])), d[0]);
        }
        assertNull(Denylist.blocked(Target.exec(new ObjectName("java.lang:type=Memory"), "gc")));
        assertNull(Denylist.blocked(Target.read(new ObjectName("com.sun.management:type=HotSpotDiagnostic"),
                "DiagnosticOptions")));
    }

    @Test
    void invalidRulesAreRejectedAtStartup() {
        assertThrows(ConfigException.class, () -> AccessPolicy.fromConfig(ConfigLoader.parse(
                "rules:\n  - allow: [exec]\n    mbean: 'java.lang:*'")), "allow without roles");
        assertThrows(ConfigException.class, () -> AccessPolicy.fromConfig(ConfigLoader.parse(
                "rules:\n  - roles: [x]\n    allow: [exec]\n    deny: [read]\n    mbean: 'java.lang:*'")));
        assertThrows(ConfigException.class, () -> AccessPolicy.fromConfig(ConfigLoader.parse(
                "rules:\n  - roles: [x]\n    allow: [launch]\n    mbean: 'java.lang:*'")));
        assertThrows(ConfigException.class, () -> AccessPolicy.fromConfig(ConfigLoader.parse(
                "rules:\n  - roles: [x]\n    allow: [exec]\n    mbean: 'not an object name'")));
    }

    @Test
    void maskerKeepsDefaultsWhenExtended() {
        ValueMasker masker = ValueMasker.fromConfig(ConfigLoader.parse("mask: ['*pin*']"));
        assertTrue(masker.isMasked("Password"));
        assertTrue(masker.isMasked("keyStorePASSWORD"));
        assertTrue(masker.isMasked("credential-reference"));
        assertTrue(masker.isMasked("cardPin"));
        assertFalse(masker.isMasked("PoolSize"));
    }
}

package io.rocoto.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.jmx.MBeanServerAccess;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.audit.AuditEvent;
import io.rocoto.security.audit.AuditLogger;
import io.rocoto.security.policy.AccessPolicy;
import io.rocoto.security.policy.ValueMasker;

class RequestPipelineTest {

    private static final MBeanServer SERVER = ManagementFactory.getPlatformMBeanServer();
    private static ObjectName demo;

    private final List<AuditEvent> events = new ArrayList<>();
    private RequestPipeline pipeline;

    private static final CallContext READER = ctx("rita", AccessPolicy.ROLE_READER);
    private static final CallContext OPERATOR = ctx("otto", AccessPolicy.ROLE_OPERATOR);
    private static final CallContext ADMIN = ctx("ada", AccessPolicy.ROLE_ADMIN);
    private static final CallContext NOBODY = ctx("nobody");

    private static CallContext ctx(String name, String... roles) {
        return new CallContext(new RocotoIdentity(name, Set.of(roles), "test"), "127.0.0.1", "test");
    }

    @BeforeAll
    static void register() throws Exception {
        demo = new ObjectName("io.rocoto.test:type=Demo,case=security");
        SERVER.registerMBean(new Demo(), demo);
    }

    @AfterAll
    static void unregister() throws Exception {
        SERVER.unregisterMBean(demo);
    }

    @BeforeEach
    void setUp() {
        Config policy = ConfigLoader.parse("""
                rules:
                  - roles: [rocoto-operator, rocoto-admin]
                    allow: [exec]
                    mbean: "io.rocoto.test:type=Demo,*"
                    operations: [greet, login]
                  - roles: [rocoto-admin]
                    allow: [write]
                    mbean: "io.rocoto.test:type=Demo,*"
                  - roles: [rocoto-admin]
                    allow: [exec]
                    mbean: "com.sun.management:type=DiagnosticCommand"
                  - deny: [read]
                    mbean: "java.lang:type=Runtime"
                    attributes: [SystemProperties, InputArguments]
                mask: ["*connectionurl*"]
                """);
        AuditLogger audit = new AuditLogger(null, false, false) {
            @Override
            public void log(AuditEvent event) {
                events.add(event);
            }
        };
        pipeline = new RequestPipeline(new MBeanServerAccess(List.of(SERVER)), AccessPolicy.fromConfig(policy),
                ValueMasker.fromConfig(policy), audit);
    }

    @Test
    void readIsAllowedForReadersOnly() {
        assertEquals(1, pipeline.read(READER, demo, "Level"));
        assertThrows(AccessDeniedException.class, () -> pipeline.read(NOBODY, demo, "Level"));
        assertEquals(AuditEvent.Outcome.DENIED, events.get(events.size() - 1).outcome());
    }

    @Test
    void writeAndExecAreDeniedByDefault() {
        assertThrows(AccessDeniedException.class, () -> pipeline.write(READER, demo, "Level", 5L));
        assertThrows(AccessDeniedException.class, () -> pipeline.write(OPERATOR, demo, "Level", 5L));
        assertThrows(AccessDeniedException.class, () -> pipeline.exec(READER, demo, "greet", null, List.of("x")));
        assertEquals(1, pipeline.read(READER, demo, "Level"));
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(e -> e.outcome() == AuditEvent.Outcome.DENIED));
    }

    @Test
    void allowRulesOpenExactlyWhatTheyName() {
        assertEquals("hello bob", pipeline.exec(OPERATOR, demo, "greet", null, List.of("bob")));
        assertEquals(1, pipeline.write(ADMIN, demo, "Level", 9L));
        assertEquals(9, pipeline.read(READER, demo, "Level"));
        pipeline.write(ADMIN, demo, "Level", 1L);
        assertEquals(AuditEvent.Outcome.ALLOWED, events.get(0).outcome());
        assertEquals("otto", events.get(0).user());
    }

    @Test
    void builtInDenyListBeatsAllowRules() throws Exception {
        ObjectName diag = new ObjectName("com.sun.management:type=DiagnosticCommand");
        assertThrows(AccessDeniedException.class,
                () -> pipeline.exec(ADMIN, diag, "jvmtiAgentLoad", null, List.of("/tmp/evil.so")));
        assertTrue(events.get(0).reason().contains("built-in deny list"));
    }

    @Test
    void secretsAreMaskedEverywhere() {
        assertEquals(ValueConverter.MASK, pipeline.read(ADMIN, demo, "DbPassword"));

        Map<String, Object> all = pipeline.readAll(ADMIN, demo, new HashMap<>());
        assertEquals(ValueConverter.MASK, all.get("DbPassword"));
        String json = JsonWriter.toJson(pipeline.converter().build().convert(all));
        assertFalse(json.contains("s3cr3t"), json);
        assertFalse(json.contains("hunter2"), json);
        assertTrue(json.contains("jdbc:h2:mem"));

        // Secrets do not reach the audit log either - neither as written value nor as operation argument.
        pipeline.write(ADMIN, demo, "DbPassword", "n3w-s3cr3t");
        pipeline.exec(ADMIN, demo, "login", null, List.of("bob", "p4ss"));
        String audit = JsonWriter.toJson(events.stream().map(AuditEvent::toMap).toList());
        assertFalse(audit.contains("n3w-s3cr3t"), audit);
        // operation arguments are not recorded unless audit.execArguments is switched on
        assertFalse(audit.contains("p4ss"), audit);
        assertTrue(audit.contains("\"member\":\"login\""));
        pipeline.write(ADMIN, demo, "DbPassword", "s3cr3t");
    }

    @Test
    void denyRulesHideAttributesFromReadAllAndDescribe() throws Exception {
        ObjectName runtime = new ObjectName("java.lang:type=Runtime");
        assertThrows(AccessDeniedException.class, () -> pipeline.read(ADMIN, runtime, "SystemProperties"));
        Map<String, Object> all = pipeline.readAll(ADMIN, runtime, new HashMap<>());
        assertFalse(all.containsKey("SystemProperties"));
        assertTrue(all.containsKey("VmName"));
        Map<?, ?> attributes = (Map<?, ?>) pipeline.describe(ADMIN, runtime).get("attributes");
        assertFalse(attributes.containsKey("InputArguments"));
    }

    @Test
    void searchOnlyReturnsVisibleMBeans() throws Exception {
        ObjectName all = new ObjectName("io.rocoto.test:*");
        assertTrue(pipeline.search(READER, all).contains(demo));
        assertTrue(pipeline.search(NOBODY, all).isEmpty());
    }

    @Test
    void tokenScopeRestrictsEvenAdmins() throws Exception {
        RocotoIdentity scoped = new RocotoIdentity("token:prom", Set.of(AccessPolicy.ROLE_ADMIN), "token",
                List.of(new ObjectName("java.lang:*")));
        CallContext ctx = new CallContext(scoped, "127.0.0.1", "test");
        assertThrows(AccessDeniedException.class, () -> pipeline.read(ctx, demo, "Level"));
        pipeline.read(ctx, new ObjectName("java.lang:type=Runtime"), "VmName");
    }

    @Test
    void failuresAreAudited() {
        assertThrows(RuntimeException.class, () -> pipeline.write(ADMIN, demo, "Level", "not-a-number"));
        assertEquals(AuditEvent.Outcome.FAILED, events.get(events.size() - 1).outcome());
    }
}

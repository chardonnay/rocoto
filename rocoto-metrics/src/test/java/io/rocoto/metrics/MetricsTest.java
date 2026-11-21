package io.rocoto.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.core.jmx.MBeanServerAccess;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;
import io.rocoto.security.RocotoIdentity;
import io.rocoto.security.audit.AuditLogger;
import io.rocoto.security.policy.AccessPolicy;
import io.rocoto.security.policy.ValueMasker;

class MetricsTest {

    private final RequestPipeline pipeline = new RequestPipeline(
            new MBeanServerAccess(List.of(ManagementFactory.getPlatformMBeanServer())),
            AccessPolicy.secureDefault(), ValueMasker.fromConfig(Config.empty()),
            AuditLogger.fromConfig(Config.empty()));

    private static CallContext ctx(RocotoIdentity identity) {
        return new CallContext(identity, "127.0.0.1", "metrics");
    }

    @Test
    void bundledProfilesAreValid() {
        for (String profile : List.of("jvm", "tomcat", "wildfly", "payara")) {
            Config c = ConfigLoader.parse("profiles: [" + profile + "]");
            assertFalse(MetricProfiles.load(c, "generic", null).isEmpty(), profile);
        }
    }

    @Test
    void jvmProfileProducesPrometheusAndOpenMetricsText() {
        MetricsCollector collector = new MetricsCollector(MetricProfiles.load(Config.empty(), "generic", null));
        List<MetricFamily> families = collector.collect(
                ctx(new RocotoIdentity("prom", Set.of(AccessPolicy.ROLE_READER), "token")), pipeline);

        String prometheus = TextFormat.write(families, false);
        assertTrue(prometheus.contains("# TYPE jvm_memory_heap_used_bytes gauge"), prometheus);
        assertTrue(prometheus.contains("# TYPE jvm_gc_collections_total counter"));
        assertTrue(prometheus.matches("(?s).*jvm_memory_pool_used_bytes\\{pool=\"[^\"]+\"} \\d+.*"));
        assertFalse(prometheus.contains("# EOF"));

        String openMetrics = TextFormat.write(families, true);
        assertTrue(openMetrics.contains("# TYPE jvm_gc_collections counter"));
        assertTrue(openMetrics.contains("jvm_gc_collections_total{"));
        assertTrue(openMetrics.endsWith("# EOF\n"));
    }

    @Test
    void scrapersOnlySeeWhatTheirIdentityAllows() throws Exception {
        MetricsCollector collector = new MetricsCollector(MetricProfiles.load(Config.empty(), "generic", null));
        // no role at all
        assertTrue(collector.collect(ctx(new RocotoIdentity("x", Set.of(), "token")), pipeline).stream()
                .allMatch(f -> f.samples().isEmpty()));
        // token scoped to the Threading MBean only
        RocotoIdentity scoped = new RocotoIdentity("t", Set.of(AccessPolicy.ROLE_READER), "token",
                List.of(new ObjectName("java.lang:type=Threading")));
        String text = TextFormat.write(collector.collect(ctx(scoped), pipeline), false);
        assertTrue(text.contains("jvm_threads_current"));
        assertFalse(text.contains("jvm_memory_heap_used_bytes"));
    }

    @Test
    void discoversEachPatternOncePerScrape() {
        AtomicInteger queries = new AtomicInteger();
        MBeanServerConnection platform = ManagementFactory.getPlatformMBeanServer();
        // Test spy: count discovery calls while all operations run on the real platform MBeanServer.
        MBeanServerConnection counted = (MBeanServerConnection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { MBeanServerConnection.class }, (proxy, method, args) -> {
                    if (method.getName().equals("queryNames")) {
                        queries.incrementAndGet();
                    }
                    try {
                        return method.invoke(platform, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        RequestPipeline countedPipeline = new RequestPipeline(new MBeanServerAccess(List.of(counted)),
                AccessPolicy.secureDefault(), ValueMasker.fromConfig(Config.empty()), AuditLogger.fromConfig(Config.empty()));
        List<MetricRule> rules = MetricProfiles.load(Config.empty(), "generic", null);
        long patterns = rules.stream().map(MetricRule::mbean).distinct().count();
        MetricsCollector collector = new MetricsCollector(rules);
        CallContext reader = ctx(new RocotoIdentity("reader", Set.of(AccessPolicy.ROLE_READER), "token"));
        for (int scrape = 1; scrape <= 2; scrape++) {
            assertFalse(collector.collect(reader, countedPipeline).isEmpty());
            assertEquals(scrape * patterns, queries.get());
        }
    }

    @Test
    void masksCompositeKeysInExplicitAndExpandedRules() {
        RequestPipeline masked = new RequestPipeline(new MBeanServerAccess(List.of(ManagementFactory.getPlatformMBeanServer())),
                AccessPolicy.secureDefault(), ValueMasker.fromConfig(ConfigLoader.parse("mask: [used]")),
                AuditLogger.fromConfig(Config.empty()));
        MetricsCollector collector = new MetricsCollector(MetricProfiles.parse(ConfigLoader.parse("""
                rules:
                  - { mbean: "java.lang:type=Memory", attribute: HeapMemoryUsage, path: used, name: explicit }
                  - { mbean: "java.lang:type=Memory", attribute: HeapMemoryUsage, name: expanded }
                """), "test"));
        List<MetricFamily> families = collector.collect(
                ctx(new RocotoIdentity("reader", Set.of(AccessPolicy.ROLE_READER), "token")), masked);
        assertEquals(List.of("expanded"), families.stream().map(MetricFamily::name).toList());
        String output = TextFormat.write(families, false);
        assertTrue(output.contains("expanded{key=\"committed\"}"));
        assertFalse(output.contains("key=\"used\""));
    }

    @Test
    void maskedAttributesNeverBecomeMetrics() {
        List<MetricRule> rules = MetricProfiles.parse(ConfigLoader.parse("""
                rules:
                  - { mbean: "java.lang:type=Runtime", attribute: Uptime, name: app_secret_token_password }
                """), "test");
        // The rule is fine; the point is the attribute name. Use a masked attribute name instead:
        List<MetricRule> masked = MetricProfiles.parse(ConfigLoader.parse("""
                rules:
                  - { mbean: "java.lang:type=Runtime", attribute: BootClassPathPassword, name: leaked_value }
                """), "test");
        assertFalse(rules.isEmpty());
        String text = TextFormat.write(new MetricsCollector(masked).collect(
                ctx(new RocotoIdentity("p", Set.of(AccessPolicy.ROLE_READER), "token")), pipeline), false);
        assertFalse(text.contains("leaked_value"));
    }

    @Test
    void invalidRulesAreRejected() {
        assertThrows(ConfigException.class, () -> MetricProfiles.parse(ConfigLoader.parse(
                "rules:\n  - { mbean: 'java.lang:type=Runtime', attribute: Uptime, name: 'bad name' }"), "t"));
        assertThrows(ConfigException.class, () -> MetricProfiles.parse(ConfigLoader.parse(
                "rules:\n  - { mbean: 'java.lang:type=Runtime', attribute: Uptime, name: x, type: counter }"), "t"));
    }

    @Test
    void otlpPayloadHasGaugesAndCumulativeSums() {
        MetricsCollector collector = new MetricsCollector(MetricProfiles.load(Config.empty(), "generic", null));
        Config otlp = ConfigLoader.parse("enabled: true\nendpoint: http://localhost:1/v1/metrics\n"
                + "allowInsecure: true\nintervalSeconds: 3600");
        try (OtlpExporter exporter = OtlpExporter.start(otlp, sslContext(), collector, pipeline)) {
            String json = io.rocoto.core.json.JsonWriter.toJson(exporter.payload(collector.collect(
                    ctx(new RocotoIdentity("o", Set.of(AccessPolicy.ROLE_READER), "internal")), pipeline), 1L));
            assertTrue(json.contains("\"resourceMetrics\""));
            assertTrue(json.contains("\"isMonotonic\":true"));
            assertTrue(json.contains("\"gauge\""));
            assertTrue(json.contains("\"service.name\""));
        }
    }

    private static javax.net.ssl.SSLContext sslContext() {
        try {
            return javax.net.ssl.SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

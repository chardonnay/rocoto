package io.rocoto.api;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Properties;
import java.util.logging.Logger;

import javax.management.MBeanServer;

import io.rocoto.adapters.ServerAdapter;
import io.rocoto.adapters.ServerAdapters;
import io.rocoto.api.stream.SubscriptionManager;
import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.core.jmx.MBeanServerAccess;
import io.rocoto.metrics.MetricProfiles;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.metrics.OtlpExporter;
import io.rocoto.security.RequestPipeline;
import io.rocoto.security.RocotoSecurity;
import io.rocoto.security.tls.OutboundTls;

/** Everything a running Rocoto instance consists of. One per web application. */
public final class RocotoRuntime implements AutoCloseable {

    public static final String CONTEXT_ATTRIBUTE = "io.rocoto.runtime";

    private static final Logger LOG = Logger.getLogger(RocotoRuntime.class.getName());
    private static volatile RocotoRuntime current;

    private final Config config;
    private final String configSource;
    private final RocotoSecurity security;
    private final ServerAdapter adapter;
    private final RequestPipeline pipeline;
    private final MetricsCollector metrics;
    private final OtlpExporter otlp;
    private final SubscriptionManager subscriptions;
    private final io.rocoto.core.format.DocumentFormats formats;
    private final TlsSelfProbe tlsProbe;
    private final String version;

    private RocotoRuntime(ConfigLoader.Loaded loaded, ClassLoader classLoader) {
        this.config = loaded.config();
        this.configSource = loaded.source();
        this.version = readVersion();
        this.security = RocotoSecurity.fromConfig(config, loaded.directory());
        MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
        this.adapter = ServerAdapters.select(platform, config.string("server.adapter", null), classLoader);
        this.pipeline = security.pipeline(new MBeanServerAccess(adapter.mbeanServers(platform)));
        Config metricsConfig = config.sub("metrics");
        this.metrics = metricsConfig.bool("enabled", true)
                ? new MetricsCollector(MetricProfiles.load(metricsConfig, adapter.id(), loaded.directory()))
                : null;
        // The outbound TLS context is only built when something uses it: the JVM's default trust
        // store may be unusable (GlassFish 8 points it to a domain store without CA certificates).
        Config otlpConfig = metricsConfig.sub("otlp");
        this.otlp = metrics == null || !otlpConfig.bool("enabled", false) ? null : OtlpExporter.start(otlpConfig,
                OutboundTls.context(config.sub("tls.outbound")), metrics, pipeline);
        this.subscriptions = new SubscriptionManager(pipeline, config.sub("stream"), security::stillValid);
        this.tlsProbe = TlsSelfProbe.start(config.sub("tls.selfProbe"));
        Operations.maxPatternMBeans = config.integer("api.maxPatternMBeans", 1000);
        try {
            this.formats = io.rocoto.core.format.DocumentFormats.of(config.has("api.formats")
                    ? config.strings("api.formats") : io.rocoto.core.format.DocumentFormats.ALL);
        } catch (IllegalArgumentException e) {
            throw new io.rocoto.core.config.ConfigException("api.formats: " + e.getMessage());
        }
    }

    /** Builds the runtime. Any configuration error aborts start-up: Rocoto fails closed. */
    public static RocotoRuntime start(ClassLoader classLoader) {
        ConfigLoader.Loaded loaded = ConfigLoader.load(classLoader);
        RocotoRuntime runtime = new RocotoRuntime(loaded, classLoader);
        current = runtime;
        LOG.info("Rocoto " + runtime.version + " started (configuration: " + loaded.source() + ")");
        return runtime;
    }

    /** For components without access to the servlet context (WebSocket endpoints). */
    public static RocotoRuntime current() {
        return current;
    }

    /** Output formats enabled with {@code api.formats}. */
    public io.rocoto.core.format.DocumentFormats formats() {
        return formats;
    }

    public Config config() {
        return config;
    }

    public String configSource() {
        return configSource;
    }

    public RocotoSecurity security() {
        return security;
    }

    public ServerAdapter adapter() {
        return adapter;
    }

    public RequestPipeline pipeline() {
        return pipeline;
    }

    /** @return the collector, or {@code null} if {@code metrics.enabled} is false */
    public MetricsCollector metrics() {
        return metrics;
    }

    public SubscriptionManager subscriptions() {
        return subscriptions;
    }

    /** @return the TLS watchdog, or {@code null} if {@code tls.selfProbe.url} is not set */
    public TlsSelfProbe tlsProbe() {
        return tlsProbe;
    }

    public String version() {
        return version;
    }

    private static String readVersion() {
        try (InputStream in = RocotoRuntime.class.getResourceAsStream("version.properties")) {
            Properties p = new Properties();
            if (in != null) {
                p.load(in);
            }
            return p.getProperty("version", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

    @Override
    public void close() {
        if (current == this) {
            current = null;
        }
        subscriptions.close();
        if (tlsProbe != null) {
            tlsProbe.close();
        }
        if (otlp != null) {
            otlp.close();
        }
        security.close();
    }
}

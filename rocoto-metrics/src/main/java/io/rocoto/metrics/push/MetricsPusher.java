package io.rocoto.metrics.push;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.core.config.Config;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;
import io.rocoto.security.RocotoIdentity;

/**
 * Base of all push exporters: a daemon thread collects the metrics every {@code intervalSeconds}
 * and hands them to {@link #push}. Pushers have no request to take an identity from; each runs as
 * the internal identity {@code <sink>-exporter} with the roles from its {@code roles} setting
 * (default {@code rocoto-reader}) and reads through the request pipeline like every other client.
 */
public abstract class MetricsPusher implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(MetricsPusher.class.getName());

    private final String sink;
    private final MetricsCollector collector;
    private final RequestPipeline pipeline;
    private final Supplier<List<MetricFamily>> extra;
    private final CallContext context;
    private final long intervalSeconds;
    private ScheduledExecutorService scheduler;
    private volatile boolean failing;

    protected MetricsPusher(String sink, Config config, long defaultIntervalSeconds, MetricsCollector collector,
            RequestPipeline pipeline, Supplier<List<MetricFamily>> extra) {
        this.sink = sink;
        this.collector = collector;
        this.pipeline = pipeline;
        this.extra = extra;
        List<String> roles = config.has("roles") ? config.strings("roles") : List.of("rocoto-reader");
        this.context = new CallContext(new RocotoIdentity(sink + "-exporter", Set.copyOf(roles), "internal"),
                "local", "metrics");
        this.intervalSeconds = Math.max(1, config.longValue("intervalSeconds", defaultIntervalSeconds));
    }

    /** Human readable target for the log, e.g. {@code graphite.example.com:2003}. */
    protected abstract String target();

    /** Sends one collection. Exceptions are logged; the next run tries again. */
    protected abstract void push(List<MetricFamily> families, long nowMillis) throws Exception;

    protected final MetricsPusher start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rocoto-" + sink + "-exporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::runOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        LOG.info("Rocoto pushes metrics to " + sink + " at " + target() + " every " + intervalSeconds + "s");
        return this;
    }

    /** One collection and push; package-visible for tests. */
    protected final void runOnce() {
        try {
            List<MetricFamily> families = new ArrayList<>(collector.collect(context, pipeline));
            if (extra != null) {
                families.addAll(extra.get());
            }
            push(families, System.currentTimeMillis());
            if (failing) {
                LOG.info("Metrics push to " + sink + " works again");
                failing = false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Log the first failure loudly, repetitions quietly, so an unreachable target does not flood the log.
            LOG.log(failing ? Level.FINE : Level.WARNING, "Metrics push to " + sink + " (" + target() + ") failed: "
                    + e.getMessage());
            failing = true;
        }
    }

    public String sink() {
        return sink;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}

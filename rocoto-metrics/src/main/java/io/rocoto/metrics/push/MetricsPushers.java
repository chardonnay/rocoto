package io.rocoto.metrics.push;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import io.rocoto.core.config.Config;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.metrics.OtlpExporter;
import io.rocoto.security.RequestPipeline;

/**
 * Starts the configured push exporters ({@code metrics.push.otlp|graphite|statsd|influx}).
 * {@code metrics.otlp} is still accepted for OTLP when {@code metrics.push.otlp} is absent.
 */
public final class MetricsPushers implements AutoCloseable {

    private final List<AutoCloseable> running;

    private MetricsPushers(List<AutoCloseable> running) {
        this.running = running;
    }

    /**
     * @param metrics    the {@code metrics} section
     * @param sslContext outbound TLS, created only if a pusher needs it
     * @param extra      additional families pushed along (TLS self-probe), may be {@code null}
     */
    public static MetricsPushers start(Config metrics, Supplier<SSLContext> sslContext, MetricsCollector collector,
            RequestPipeline pipeline, Supplier<List<MetricFamily>> extra) {
        List<AutoCloseable> running = new ArrayList<>();
        try {
            Config push = metrics.sub("push");
            Config otlp = push.has("otlp") ? push.sub("otlp") : metrics.sub("otlp");
            if (otlp.bool("enabled", false)) {
                running.add(OtlpExporter.start(otlp, sslContext.get(), collector, pipeline, extra));
            }
            if (push.sub("graphite").bool("enabled", false)) {
                running.add(new GraphitePusher(push.sub("graphite"), collector, pipeline, extra, sslContext).start());
            }
            if (push.sub("statsd").bool("enabled", false)) {
                running.add(new StatsdPusher(push.sub("statsd"), collector, pipeline, extra).start());
            }
            if (push.sub("influx").bool("enabled", false)) {
                running.add(new InfluxPusher(push.sub("influx"), collector, pipeline, extra, sslContext).start());
            }
        } catch (RuntimeException e) {
            new MetricsPushers(running).close();
            throw e;
        }
        return new MetricsPushers(running);
    }

    public int count() {
        return running.size();
    }

    @Override
    public void close() {
        for (AutoCloseable c : running) {
            try {
                c.close();
            } catch (Exception e) {
                // shutting down anyway
            }
        }
    }
}

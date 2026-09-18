package io.rocoto.metrics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;
import io.rocoto.security.RocotoIdentity;

/**
 * Pushes the collected metrics to an OpenTelemetry collector using OTLP/HTTP with JSON encoding.
 * Deliberately implemented without the OpenTelemetry SDK to keep the WAR free of heavy dependencies.
 *
 * <p>The exporter has no request to take an identity from, so it runs as the internal identity
 * {@code otlp-exporter} with the roles from {@code metrics.otlp.roles} – still through the pipeline.
 */
public final class OtlpExporter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(OtlpExporter.class.getName());

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Map<String, String> resourceAttributes;
    private final HttpClient client;
    private final MetricsCollector collector;
    private final RequestPipeline pipeline;
    private final CallContext context;
    private final long startNanos = System.currentTimeMillis() * 1_000_000L;
    private final ScheduledExecutorService scheduler;

    private OtlpExporter(URI endpoint, Map<String, String> headers, Map<String, String> resourceAttributes,
            HttpClient client, MetricsCollector collector, RequestPipeline pipeline, CallContext context) {
        this.endpoint = endpoint;
        this.headers = headers;
        this.resourceAttributes = resourceAttributes;
        this.client = client;
        this.collector = collector;
        this.pipeline = pipeline;
        this.context = context;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rocoto-otlp-exporter");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param otlp the {@code metrics.otlp} section
     * @return the started exporter, or {@code null} if OTLP is not enabled
     */
    public static OtlpExporter start(Config otlp, SSLContext sslContext, MetricsCollector collector,
            RequestPipeline pipeline) {
        if (!otlp.bool("enabled", false)) {
            return null;
        }
        String url = otlp.string("endpoint", null);
        if (url == null) {
            throw new ConfigException("metrics.otlp.endpoint is required, e.g. https://collector:4318/v1/metrics");
        }
        URI endpoint = URI.create(url);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !otlp.bool("allowInsecure", false)) {
            throw new ConfigException("metrics.otlp.endpoint must use https (or set metrics.otlp.allowInsecure)");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Config headerConfig = otlp.sub("headers");
        for (String key : headerConfig.asMap().keySet()) {
            headers.put(key, headerConfig.string(key, ""));
        }
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("service.name", otlp.string("serviceName", "rocoto"));
        otlp.sub("resourceAttributes").asMap().forEach((k, v) -> resource.put(k, String.valueOf(v)));

        List<String> roles = otlp.has("roles") ? otlp.strings("roles") : List.of("rocoto-reader");
        CallContext ctx = new CallContext(new RocotoIdentity("otlp-exporter", Set.copyOf(roles), "internal"),
                "local", "metrics");
        HttpClient client = HttpClient.newBuilder().sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(5)).build();
        OtlpExporter exporter = new OtlpExporter(endpoint, headers, resource, client, collector, pipeline, ctx);
        long interval = Math.max(5, otlp.longValue("intervalSeconds", 60));
        exporter.scheduler.scheduleWithFixedDelay(exporter::export, interval, interval, TimeUnit.SECONDS);
        LOG.info("OTLP metrics export to " + endpoint + " every " + interval + "s");
        return exporter;
    }

    void export() {
        try {
            String body = JsonWriter.toJson(payload(collector.collect(context, pipeline),
                    System.currentTimeMillis() * 1_000_000L));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(request::header);
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                LOG.warning("OTLP collector answered " + response.statusCode());
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "OTLP export failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Builds an ExportMetricsServiceRequest in OTLP JSON encoding. */
    Map<String, Object> payload(List<MetricFamily> families, long nowNanos) {
        List<Object> metrics = new ArrayList<>();
        for (MetricFamily f : families) {
            List<Object> points = new ArrayList<>();
            for (MetricFamily.Sample s : f.samples()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("attributes", attributes(s.labels()));
                point.put("startTimeUnixNano", Long.toString(startNanos));
                point.put("timeUnixNano", Long.toString(nowNanos));
                point.put("asDouble", s.value());
                points.add(point);
            }
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("name", f.name());
            metric.put("description", f.help());
            if (f.type() == MetricRule.Type.COUNTER) {
                Map<String, Object> sum = new LinkedHashMap<>();
                sum.put("dataPoints", points);
                sum.put("aggregationTemporality", 2); // cumulative
                sum.put("isMonotonic", true);
                metric.put("sum", sum);
            } else {
                metric.put("gauge", Map.of("dataPoints", points));
            }
            metrics.add(metric);
        }
        Map<String, Object> scopeMetrics = new LinkedHashMap<>();
        scopeMetrics.put("scope", Map.of("name", "io.rocoto"));
        scopeMetrics.put("metrics", metrics);
        Map<String, Object> resourceMetrics = new LinkedHashMap<>();
        resourceMetrics.put("resource", Map.of("attributes", attributes(resourceAttributes)));
        resourceMetrics.put("scopeMetrics", List.of(scopeMetrics));
        return Map.of("resourceMetrics", List.of(resourceMetrics));
    }

    private static List<Object> attributes(Map<String, String> values) {
        List<Object> out = new ArrayList<>();
        values.forEach((k, v) -> {
            Map<String, Object> kv = new LinkedHashMap<>();
            kv.put("key", k);
            kv.put("value", Map.of("stringValue", v));
            out.add(kv);
        });
        return out;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

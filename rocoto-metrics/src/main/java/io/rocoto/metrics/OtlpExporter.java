package io.rocoto.metrics;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.metrics.format.MetricFormats;
import io.rocoto.metrics.format.OtlpJson;
import io.rocoto.metrics.push.MetricsPusher;
import io.rocoto.security.RequestPipeline;

/**
 * Pushes the collected metrics to an OpenTelemetry collector using OTLP/HTTP with JSON encoding.
 * Deliberately implemented without the OpenTelemetry SDK to keep the WAR free of heavy dependencies.
 *
 * <p>Runs as the internal identity {@code otlp-exporter} with the roles from {@code roles} – still
 * through the pipeline (see {@link MetricsPusher}).
 */
public final class OtlpExporter extends MetricsPusher {

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Map<String, String> resourceAttributes;
    private final HttpClient client;
    private final long startNanos = System.currentTimeMillis() * 1_000_000L;

    private OtlpExporter(Config otlp, URI endpoint, Map<String, String> headers, HttpClient client,
            MetricsCollector collector, RequestPipeline pipeline, Supplier<List<MetricFamily>> extra) {
        super("otlp", otlp, 60, collector, pipeline, extra);
        this.endpoint = endpoint;
        this.headers = headers;
        this.resourceAttributes = MetricFormats.resourceAttributes(otlp);
        this.client = client;
    }

    /**
     * @param otlp the {@code metrics.push.otlp} (or legacy {@code metrics.otlp}) section
     * @return the started exporter, or {@code null} if OTLP is not enabled
     */
    public static OtlpExporter start(Config otlp, SSLContext sslContext, MetricsCollector collector,
            RequestPipeline pipeline) {
        return start(otlp, sslContext, collector, pipeline, null);
    }

    public static OtlpExporter start(Config otlp, SSLContext sslContext, MetricsCollector collector,
            RequestPipeline pipeline, Supplier<List<MetricFamily>> extra) {
        if (!otlp.bool("enabled", false)) {
            return null;
        }
        String url = otlp.string("endpoint", null);
        if (url == null) {
            throw new ConfigException("metrics.push.otlp.endpoint is required, e.g. https://collector:4318/v1/metrics");
        }
        URI endpoint = URI.create(url);
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) && !otlp.bool("allowInsecure", false)) {
            throw new ConfigException("metrics.push.otlp.endpoint must use https (or set allowInsecure)");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Config headerConfig = otlp.sub("headers");
        for (String key : headerConfig.asMap().keySet()) {
            headers.put(key, headerConfig.string(key, ""));
        }
        HttpClient client = HttpClient.newBuilder().sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(5)).build();
        OtlpExporter exporter = new OtlpExporter(otlp, endpoint, headers, client, collector, pipeline, extra);
        exporter.start();
        return exporter;
    }

    @Override
    protected String target() {
        return endpoint.toString();
    }

    @Override
    protected void push(List<MetricFamily> families, long nowMillis) throws Exception {
        String body = JsonWriter.toJson(payload(families, nowMillis * 1_000_000L));
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("OTLP collector answered " + response.statusCode());
        }
    }

    /** Builds an ExportMetricsServiceRequest in OTLP JSON encoding. */
    Map<String, Object> payload(List<MetricFamily> families, long nowNanos) {
        return OtlpJson.request(families, resourceAttributes, startNanos, nowNanos);
    }
}

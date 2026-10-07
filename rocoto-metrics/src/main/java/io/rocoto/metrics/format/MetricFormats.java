package io.rocoto.metrics.format;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.rocoto.core.config.Config;

/**
 * Formats of {@code GET /metrics}. Graphite and StatsD naming follows the corresponding push
 * section ({@code metrics.push.graphite.prefix/tags}, {@code metrics.push.statsd.prefix/tags}),
 * so pulled and pushed series have the same names.
 */
public final class MetricFormats {

    public static final List<String> IDS = List.of("prometheus", "openmetrics", "influx", "graphite", "statsd", "otlp");

    private final Config metrics;

    /** @param metrics the {@code metrics} section of rocoto.yaml */
    public MetricFormats(Config metrics) {
        this.metrics = metrics;
    }

    /**
     * @param precision Influx timestamp precision from the request, or {@code null}
     * @return the format, or {@code null} for an unknown id
     */
    public MetricFormat byId(String id, String precision) {
        Config graphite = metrics.sub("push.graphite");
        Config statsd = metrics.sub("push.statsd");
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "prometheus" -> new PrometheusFormat(false);
            case "openmetrics" -> new PrometheusFormat(true);
            case "influx" -> new InfluxFormat(precision != null ? precision : "ns");
            case "graphite" -> new GraphiteFormat(graphite.string("prefix", "rocoto"), graphite.bool("tags", false));
            case "statsd" -> new StatsdFormat(statsd.string("prefix", "rocoto"), statsd.bool("tags", false), false);
            case "otlp" -> new OtlpFormat(resourceAttributes(metrics.sub("push.otlp").asMap().isEmpty()
                    ? metrics.sub("otlp") : metrics.sub("push.otlp")));
            default -> null;
        };
    }

    /** Prometheus text unless the client asks for OpenMetrics. */
    public MetricFormat negotiate(String accept) {
        return byId(accept != null && accept.contains("application/openmetrics-text") ? "openmetrics" : "prometheus",
                null);
    }

    public static Map<String, String> resourceAttributes(Config otlp) {
        Map<String, String> resource = new LinkedHashMap<>();
        resource.put("service.name", otlp.string("serviceName", "rocoto"));
        otlp.sub("resourceAttributes").asMap().forEach((k, v) -> resource.put(k, String.valueOf(v)));
        return resource;
    }
}

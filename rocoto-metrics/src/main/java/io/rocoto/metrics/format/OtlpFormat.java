package io.rocoto.metrics.format;

import java.util.List;
import java.util.Map;

import io.rocoto.core.json.JsonWriter;
import io.rocoto.metrics.MetricFamily;

/** OTLP/JSON for pull clients; the same document the OTLP push exporter sends. */
final class OtlpFormat implements MetricFormat {

    private static final long START_NANOS = System.currentTimeMillis() * 1_000_000L;

    private final Map<String, String> resourceAttributes;

    OtlpFormat(Map<String, String> resourceAttributes) {
        this.resourceAttributes = resourceAttributes;
    }

    @Override
    public String id() {
        return "otlp";
    }

    @Override
    public String contentType() {
        return "application/json";
    }

    @Override
    public void write(List<MetricFamily> families, long nowMillis, Appendable out) {
        JsonWriter.write(OtlpJson.request(families, resourceAttributes, START_NANOS, nowMillis * 1_000_000L), out);
    }
}

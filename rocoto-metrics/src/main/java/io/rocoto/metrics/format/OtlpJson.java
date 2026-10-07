package io.rocoto.metrics.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricRule;

/** Builds an OTLP {@code ExportMetricsServiceRequest} in the OTLP/JSON encoding. */
public final class OtlpJson {

    private OtlpJson() {
    }

    public static Map<String, Object> request(List<MetricFamily> families, Map<String, String> resourceAttributes,
            long startNanos, long nowNanos) {
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
}

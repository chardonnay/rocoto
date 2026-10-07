package io.rocoto.metrics.format;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.TextFormat;

/** Prometheus text format 0.0.4 or OpenMetrics 1.0. */
final class PrometheusFormat implements MetricFormat {

    private final boolean openMetrics;

    PrometheusFormat(boolean openMetrics) {
        this.openMetrics = openMetrics;
    }

    @Override
    public String id() {
        return openMetrics ? "openmetrics" : "prometheus";
    }

    @Override
    public String contentType() {
        return openMetrics ? TextFormat.OPENMETRICS : TextFormat.PROMETHEUS;
    }

    @Override
    public void write(List<MetricFamily> families, long nowMillis, Appendable out) {
        try {
            out.append(TextFormat.write(families, openMetrics));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

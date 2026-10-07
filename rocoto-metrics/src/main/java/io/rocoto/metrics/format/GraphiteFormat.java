package io.rocoto.metrics.format;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import io.rocoto.metrics.MetricFamily;

/**
 * Graphite plaintext protocol: {@code path.to.metric value timestamp} (seconds).
 * Hierarchical mode appends the label values to the path ({@code rocoto.jvm_gc_collections_total.G1_Young_Generation});
 * tag mode uses Graphite 1.1 tags ({@code jvm_gc_collections_total;gc=G1_Young_Generation}).
 */
public final class GraphiteFormat implements MetricFormat {

    private final String prefix;
    private final boolean tags;

    public GraphiteFormat(String prefix, boolean tags) {
        this.prefix = prefix;
        this.tags = tags;
    }

    @Override
    public String id() {
        return "graphite";
    }

    @Override
    public String contentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    public void write(List<MetricFamily> families, long nowMillis, Appendable out) {
        long seconds = nowMillis / 1000;
        try {
            for (MetricFamily f : families) {
                for (MetricFamily.Sample s : f.samples()) {
                    if (Double.isNaN(s.value()) || Double.isInfinite(s.value())) {
                        continue;
                    }
                    out.append(name(f.name(), s.labels())).append(' ').append(Names.number(s.value())).append(' ')
                            .append(Long.toString(seconds)).append('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String name(String metric, Map<String, String> labels) {
        if (!tags) {
            return Names.path(prefix, metric, labels);
        }
        StringBuilder sb = new StringBuilder(Names.prefix(prefix)).append(Names.segment(metric));
        labels.forEach((k, v) -> {
            if (!v.isEmpty()) {
                sb.append(';').append(Names.segment(k)).append('=').append(Names.tagValue(v, ";~"));
            }
        });
        return sb.toString();
    }
}

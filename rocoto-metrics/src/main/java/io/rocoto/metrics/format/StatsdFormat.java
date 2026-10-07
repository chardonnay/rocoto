package io.rocoto.metrics.format;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricRule;

/**
 * StatsD: {@code name:value|g} for gauges, {@code name:delta|c} for counters (push only – a counter
 * in StatsD is an increment, so the format remembers the previous value of each series).
 * {@code dogstatsd} adds tags as {@code |#key:value}; otherwise label values become path segments.
 *
 * <p>StatsD reads {@code -5|g} as "decrease by five"; negative gauges are therefore sent as
 * {@code 0|g} followed by the negative value.
 */
public final class StatsdFormat implements MetricFormat {

    private final String prefix;
    private final boolean dogstatsd;
    private final boolean counterDeltas;
    private final Map<String, Double> previous = new HashMap<>();

    /**
     * @param counterDeltas {@code true} (push): counters as {@code |c} deltas; {@code false}
     *                      (pull, stateless): counters are reported as gauges
     */
    public StatsdFormat(String prefix, boolean dogstatsd, boolean counterDeltas) {
        this.prefix = prefix;
        this.dogstatsd = dogstatsd;
        this.counterDeltas = counterDeltas;
    }

    @Override
    public String id() {
        return "statsd";
    }

    @Override
    public String contentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    public void write(List<MetricFamily> families, long nowMillis, Appendable out) {
        try {
            for (String line : lines(families)) {
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One StatsD line per entry; pushers pack them into datagrams. */
    public synchronized List<String> lines(List<MetricFamily> families) {
        List<String> lines = new ArrayList<>();
        for (MetricFamily f : families) {
            boolean counter = f.type() == MetricRule.Type.COUNTER && counterDeltas;
            for (MetricFamily.Sample s : f.samples()) {
                double v = s.value();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    continue;
                }
                String name = dogstatsd ? Names.prefix(prefix) + Names.segment(f.name()) : Names.path(prefix, f.name(), s.labels());
                String tags = dogstatsd ? tags(s.labels()) : "";
                if (counter) {
                    String key = f.name() + s.labels();
                    Double last = previous.put(key, v);
                    if (last == null) {
                        continue; // first sight: no baseline for a delta yet
                    }
                    double delta = v >= last ? v - last : v; // a smaller value means the counter was reset
                    if (delta > 0) {
                        lines.add(name + ":" + Names.number(delta) + "|c" + tags);
                    }
                } else {
                    if (v < 0) {
                        lines.add(name + ":0|g" + tags);
                    }
                    lines.add(name + ":" + Names.number(v) + "|g" + tags);
                }
            }
        }
        return lines;
    }

    private static String tags(Map<String, String> labels) {
        StringBuilder sb = new StringBuilder();
        labels.forEach((k, v) -> {
            if (!v.isEmpty()) {
                sb.append(sb.length() == 0 ? "|#" : ",").append(Names.segment(k)).append(':')
                        .append(Names.tagValue(v, ",|#"));
            }
        });
        return sb.toString();
    }
}

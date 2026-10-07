package io.rocoto.metrics.format;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.rocoto.metrics.MetricFamily;

/**
 * InfluxDB line protocol: {@code measurement,tag=value value=1.5 1696500000000000000}.
 * The metric name is the measurement, labels are tags, the sample is the field {@code value}.
 * Line breaks cannot be escaped in the protocol and are replaced by blanks, so a label value can
 * never start a second line.
 */
public final class InfluxFormat implements MetricFormat {

    private final TimeUnit precision;

    /** @param precision {@code ns}, {@code us}, {@code ms} or {@code s} */
    public InfluxFormat(String precision) {
        this.precision = precision(precision);
    }

    public static TimeUnit precision(String precision) {
        return switch (precision == null ? "ns" : precision.toLowerCase(Locale.ROOT)) {
            case "ns", "n" -> TimeUnit.NANOSECONDS;
            case "us", "u" -> TimeUnit.MICROSECONDS;
            case "ms" -> TimeUnit.MILLISECONDS;
            case "s" -> TimeUnit.SECONDS;
            default -> throw new IllegalArgumentException("precision must be one of ns, us, ms, s");
        };
    }

    public TimeUnit precision() {
        return precision;
    }

    @Override
    public String id() {
        return "influx";
    }

    @Override
    public String contentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    public void write(List<MetricFamily> families, long nowMillis, Appendable out) {
        long ts = precision.convert(nowMillis, TimeUnit.MILLISECONDS);
        try {
            for (MetricFamily f : families) {
                for (MetricFamily.Sample s : f.samples()) {
                    if (Double.isNaN(s.value()) || Double.isInfinite(s.value())) {
                        continue; // not representable as an Influx float
                    }
                    out.append(escape(f.name(), false));
                    for (Map.Entry<String, String> l : s.labels().entrySet()) {
                        if (l.getValue().isEmpty()) {
                            continue; // empty tag values are invalid
                        }
                        out.append(',').append(escape(l.getKey(), true)).append('=').append(escape(l.getValue(), true));
                    }
                    out.append(" value=").append(field(s.value())).append(' ').append(Long.toString(ts)).append('\n');
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String field(double v) {
        String n = Names.number(v);
        return n.contains(".") || n.contains("E") ? n : n + ".0"; // keep the field type float throughout
    }

    /** Measurements escape comma and blank; tag keys and values also the equals sign. */
    static String escape(String s, boolean tag) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                sb.append("\\ ");
            } else if (c == ',' || c == ' ' || (tag && c == '=') || c == '\\') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

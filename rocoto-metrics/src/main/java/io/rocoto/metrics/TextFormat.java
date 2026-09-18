package io.rocoto.metrics;

import java.util.List;
import java.util.Map;

/** Prometheus text format 0.0.4 and OpenMetrics 1.0 writer. */
public final class TextFormat {

    public static final String PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8";
    public static final String OPENMETRICS = "application/openmetrics-text; version=1.0.0; charset=utf-8";

    private TextFormat() {
    }

    public static boolean wantsOpenMetrics(String acceptHeader) {
        return acceptHeader != null && acceptHeader.contains("application/openmetrics-text");
    }

    public static String write(List<MetricFamily> families, boolean openMetrics) {
        StringBuilder sb = new StringBuilder(4096);
        for (MetricFamily f : families) {
            if (f.samples().isEmpty()) {
                continue;
            }
            boolean counter = f.type() == MetricRule.Type.COUNTER;
            // OpenMetrics names the family without the _total suffix, the samples carry it.
            String familyName = counter && openMetrics ? f.name().substring(0, f.name().length() - "_total".length())
                    : f.name();
            sb.append("# HELP ").append(familyName).append(' ').append(escapeHelp(f.help())).append('\n');
            sb.append("# TYPE ").append(familyName).append(' ').append(counter ? "counter" : "gauge").append('\n');
            for (MetricFamily.Sample s : f.samples()) {
                sb.append(f.name());
                if (!s.labels().isEmpty()) {
                    sb.append('{');
                    boolean first = true;
                    for (Map.Entry<String, String> l : s.labels().entrySet()) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        sb.append(l.getKey()).append("=\"").append(escapeLabel(l.getValue())).append('"');
                    }
                    sb.append('}');
                }
                sb.append(' ').append(format(s.value())).append('\n');
            }
        }
        if (openMetrics) {
            sb.append("# EOF\n");
        }
        return sb.toString();
    }

    private static String format(double v) {
        if (v == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (v == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    private static String escapeHelp(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n");
    }

    private static String escapeLabel(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}

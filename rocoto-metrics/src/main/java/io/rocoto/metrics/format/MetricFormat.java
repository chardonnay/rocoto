package io.rocoto.metrics.format;

import java.util.List;

import io.rocoto.metrics.MetricFamily;

/** Renders collected metric families in one wire format. */
public interface MetricFormat {

    /** Name used in {@code /metrics?format=}. */
    String id();

    String contentType();

    /** @param nowMillis timestamp of the collection, for formats that carry one */
    void write(List<MetricFamily> families, long nowMillis, Appendable out);

    default String toString(List<MetricFamily> families, long nowMillis) {
        StringBuilder sb = new StringBuilder(4096);
        write(families, nowMillis, sb);
        return sb.toString();
    }
}

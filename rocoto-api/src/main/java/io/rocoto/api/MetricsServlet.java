package io.rocoto.api;

import java.io.IOException;
import java.util.List;

import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.format.MetricFormat;
import io.rocoto.metrics.format.MetricFormats;
import io.rocoto.security.CallContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /metrics}: Prometheus text or OpenMetrics (content negotiation), or with
 * {@code ?format=} InfluxDB line protocol ({@code influx}, {@code &precision=ns|us|ms|s}),
 * Graphite plaintext ({@code graphite}), StatsD ({@code statsd}) or OTLP/JSON ({@code otlp}).
 * The scraper authenticates like any other client – typically with a scoped API token – and only
 * receives samples its identity may read.
 */
public class MetricsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            RocotoRuntime runtime = Http.runtime(getServletContext());
            if (runtime.metrics() == null) {
                throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Metrics are disabled");
            }
            MetricFormat format = format(runtime, request);
            CallContext ctx = Http.context(request, "metrics");
            List<MetricFamily> families = new java.util.ArrayList<>(
                    runtime.metrics().collect(ctx, runtime.pipeline()));
            if (runtime.tlsProbe() != null) {
                families.addAll(runtime.tlsProbe().metrics());
            }
            response.setStatus(200);
            response.setContentType(format.contentType());
            response.setHeader("Vary", "Accept");
            format.write(families, System.currentTimeMillis(), response.getWriter());
        } catch (RuntimeException e) {
            Http.writeError(response, e);
        }
    }

    private static MetricFormat format(RocotoRuntime runtime, HttpServletRequest request) {
        String id = request.getParameter("format");
        if (id == null) {
            return runtime.metricFormats().negotiate(request.getHeader("Accept"));
        }
        MetricFormat format;
        try {
            format = runtime.metricFormats().byId(id, request.getParameter("precision"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        }
        if (format == null) {
            throw new ApiException(HttpServletResponse.SC_NOT_ACCEPTABLE,
                    "Unsupported metrics format '" + id + "'; available: " + MetricFormats.IDS);
        }
        return format;
    }
}

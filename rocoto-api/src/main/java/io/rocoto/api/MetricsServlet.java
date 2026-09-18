package io.rocoto.api;

import java.io.IOException;
import java.util.List;

import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.TextFormat;
import io.rocoto.security.CallContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code GET /metrics} in Prometheus text or OpenMetrics format (content negotiation).
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
            CallContext ctx = Http.context(request, "metrics");
            List<MetricFamily> families = runtime.metrics().collect(ctx, runtime.pipeline());
            boolean openMetrics = TextFormat.wantsOpenMetrics(request.getHeader("Accept"));
            response.setStatus(200);
            response.setContentType(openMetrics ? TextFormat.OPENMETRICS : TextFormat.PROMETHEUS);
            response.getWriter().write(TextFormat.write(families, openMetrics));
        } catch (RuntimeException e) {
            Http.writeError(response, e);
        }
    }
}

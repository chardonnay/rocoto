package io.rocoto.api.stream;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

import io.rocoto.api.ApiException;
import io.rocoto.api.Http;
import io.rocoto.api.RocotoRuntime;
import io.rocoto.api.stream.SubscriptionManager.Subscription;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.CallContext;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Server-Sent Events: {@code GET /api/v1/subscribe?mbean=...&attribute=...}.
 *
 * <p>Query parameters: {@code mbean} (required), {@code attribute} (repeatable), {@code interval}
 * (ms), {@code onChange} (default true), {@code above}, {@code below}, {@code notifications}.
 * Events: {@code value}, {@code notification}, {@code error}, {@code heartbeat}.
 */
public class SseServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            RocotoRuntime runtime = Http.runtime(getServletContext());
            CallContext ctx = Http.context(request, "stream");
            SubscriptionSpec spec = specFrom(request, runtime.subscriptions().minIntervalMillis());

            response.setStatus(200);
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("X-Accel-Buffering", "no"); // keep reverse proxies from buffering the stream

            AsyncContext async = request.startAsync();
            async.setTimeout(0);
            SseSink sink = new SseSink(async, response.getWriter());
            Subscription subscription;
            try {
                subscription = runtime.subscriptions().subscribe(ctx, spec, runtime.adapter().captureIdentity(), sink);
            } catch (RuntimeException e) {
                response.setContentType("application/json");
                Http.writeError(response, e);
                async.complete();
                return;
            }
            async.addListener(new AsyncListener() {
                @Override
                public void onComplete(AsyncEvent event) {
                    subscription.cancel();
                }

                @Override
                public void onTimeout(AsyncEvent event) {
                    subscription.cancel();
                }

                @Override
                public void onError(AsyncEvent event) {
                    subscription.cancel();
                }

                @Override
                public void onStartAsync(AsyncEvent event) {
                    // not used
                }
            });
        } catch (RuntimeException e) {
            Http.writeError(response, e);
        }
    }

    static SubscriptionSpec specFrom(HttpServletRequest request, long defaultInterval) {
        String[] attributes = request.getParameterValues("attribute");
        return new SubscriptionSpec(Http.objectName(request.getParameter("mbean")),
                attributes == null ? List.of() : List.of(attributes),
                longParam(request, "interval", Math.max(1000, defaultInterval)),
                !"false".equalsIgnoreCase(request.getParameter("onChange")),
                doubleParam(request, "above"), doubleParam(request, "below"),
                "true".equalsIgnoreCase(request.getParameter("notifications")));
    }

    private static long longParam(HttpServletRequest request, String name, long defaultValue) {
        String v = request.getParameter(name);
        try {
            return v == null ? defaultValue : Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "'" + name + "' must be a number");
        }
    }

    private static Double doubleParam(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        try {
            return v == null ? null : Double.valueOf(v);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "'" + name + "' must be a number");
        }
    }

    private static final class SseSink implements Sink {

        private final AsyncContext async;
        private final PrintWriter writer;
        private boolean closed;

        SseSink(AsyncContext async, PrintWriter writer) {
            this.async = async;
            this.writer = writer;
        }

        @Override
        public synchronized void send(String event, Map<String, Object> data) throws IOException {
            if (closed) {
                throw new IOException("stream closed");
            }
            writer.write("event: " + event + "\n");
            writer.write("data: " + JsonWriter.toJson(data) + "\n\n");
            writer.flush();
            if (writer.checkError()) {
                throw new IOException("client disconnected");
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                try {
                    async.complete();
                } catch (IllegalStateException e) {
                    // already completed by the container
                }
            }
        }
    }
}

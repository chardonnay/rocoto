package io.rocoto.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.rocoto.embedded.Exchange;
import io.rocoto.embedded.RocotoEngine;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.SSLSessionInfo;
import io.undertow.util.HeaderValues;
import io.undertow.util.HttpString;
import io.undertow.util.StatusCodes;

/**
 * Rocoto as an Undertow {@link HttpHandler} (undertow-core, no servlet layer). Requests below the
 * engine's context path are answered by Rocoto, everything else goes to {@code next}:
 * <pre>
 * RocotoEngine rocoto = RocotoEngine.start("/rocoto");
 * Undertow.builder().addHttpsListener(8443, "0.0.0.0", sslContext)
 *         .setHandler(new RocotoUndertowHandler(rocoto, myApplicationHandler)).build().start();
 * </pre>
 */
public final class RocotoUndertowHandler implements HttpHandler {

    private final RocotoEngine engine;
    private final HttpHandler next;

    /** @param next handler for requests outside of Rocoto's context path; {@code null} answers 404 */
    public RocotoUndertowHandler(RocotoEngine engine, HttpHandler next) {
        this.engine = engine;
        this.next = next;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        if (!engine.matches(rawPath(exchange))) {
            if (next != null) {
                next.handleRequest(exchange);
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
                exchange.endExchange();
            }
            return;
        }
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);        // Rocoto blocks (JMX calls, request body): worker thread
            return;
        }
        exchange.startBlocking();
        UndertowExchange adapter = new UndertowExchange(exchange);
        engine.handle(adapter);
        if (!adapter.ended.get()) {
            exchange.dispatch();            // Server-Sent Events: keep the exchange open until end()
        }
    }

    private static String rawPath(HttpServerExchange exchange) {
        String uri = exchange.getRequestURI();
        int scheme = uri.indexOf("://");
        if (scheme > 0 && !uri.startsWith("/")) {        // absolute-form request target
            int slash = uri.indexOf('/', scheme + 3);
            uri = slash < 0 ? "/" : uri.substring(slash);
        }
        return uri;
    }

    private static final class UndertowExchange implements Exchange {

        private final HttpServerExchange exchange;
        private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final AtomicBoolean ended = new AtomicBoolean();

        UndertowExchange(HttpServerExchange exchange) {
            this.exchange = exchange;
            for (HeaderValues values : exchange.getRequestHeaders()) {
                headers.put(values.getHeaderName().toString(), new ArrayList<>(values));
            }
        }

        @Override
        public String method() {
            return exchange.getRequestMethod().toString();
        }

        @Override
        public String rawPath() {
            return RocotoUndertowHandler.rawPath(exchange);
        }

        @Override
        public String rawQuery() {
            String query = exchange.getQueryString();
            return query == null || query.isEmpty() ? null : query;
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return exchange.getInputStream();
        }

        @Override
        public String protocol() {
            return exchange.getProtocol().toString();
        }

        @Override
        public String scheme() {
            return exchange.getRequestScheme();
        }

        @Override
        public String serverName() {
            return exchange.getHostName();
        }

        @Override
        public int serverPort() {
            return exchange.getHostPort();
        }

        @Override
        public String remoteAddress() {
            return exchange.getSourceAddress().getAddress() == null ? exchange.getSourceAddress().getHostString()
                    : exchange.getSourceAddress().getAddress().getHostAddress();
        }

        @Override
        public boolean secure() {
            return exchange.getConnection().getSslSessionInfo() != null;
        }

        @Override
        public X509Certificate[] clientCertificates() {
            SSLSessionInfo ssl = exchange.getConnection().getSslSessionInfo();
            if (ssl == null) {
                return null;
            }
            try {
                List<X509Certificate> chain = new ArrayList<>();
                for (Certificate certificate : ssl.getPeerCertificates()) {
                    if (certificate instanceof X509Certificate x509) {
                        chain.add(x509);
                    }
                }
                return chain.isEmpty() ? null : chain.toArray(X509Certificate[]::new);
            } catch (Exception e) {
                return null;        // no client certificate was presented
            }
        }

        @Override
        public void begin(int status, Map<String, List<String>> responseHeaders, long contentLength) {
            exchange.setStatusCode(status);
            responseHeaders.forEach((name, values) -> exchange.getResponseHeaders().putAll(new HttpString(name), values));
            if (contentLength >= 0) {
                exchange.setResponseContentLength(contentLength);
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            exchange.getOutputStream().write(buffer, offset, length);
            exchange.getOutputStream().flush();
        }

        @Override
        public void end() {
            if (ended.compareAndSet(false, true)) {
                try {
                    exchange.getOutputStream().close();
                } catch (IOException | RuntimeException e) {
                    // the client is gone
                }
                exchange.endExchange();
            }
        }

        @Override
        public void onClosed(Runnable callback) {
            exchange.getConnection().addCloseListener(connection -> callback.run());
        }
    }
}

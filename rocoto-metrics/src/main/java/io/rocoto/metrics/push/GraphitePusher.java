package io.rocoto.metrics.push;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.metrics.format.GraphiteFormat;
import io.rocoto.security.RequestPipeline;

/** Graphite plaintext over TCP (Carbon, port 2003), optionally TLS. One connection per push. */
final class GraphitePusher extends MetricsPusher {

    private final String host;
    private final int port;
    private final Supplier<SSLContext> tls;
    private final GraphiteFormat format;

    GraphitePusher(Config c, MetricsCollector collector, RequestPipeline pipeline, Supplier<List<MetricFamily>> extra,
            Supplier<SSLContext> sslContext) {
        super("graphite", c, 60, collector, pipeline, extra);
        this.host = c.string("host", null);
        if (host == null) {
            throw new ConfigException("metrics.push.graphite.host is required");
        }
        this.port = c.integer("port", 2003);
        this.tls = c.bool("tls", false) ? sslContext : null;
        this.format = new GraphiteFormat(c.string("prefix", "rocoto"), c.bool("tags", false));
    }

    @Override
    protected String target() {
        return host + ":" + port + (tls != null ? " (TLS)" : "");
    }

    @Override
    protected void push(List<MetricFamily> families, long nowMillis) throws Exception {
        byte[] payload = format.toString(families, nowMillis).getBytes(StandardCharsets.UTF_8);
        try (Socket socket = tls != null ? tls.get().getSocketFactory().createSocket() : new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(10_000);
            if (socket instanceof SSLSocket ssl) {
                javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS"); // verify the host name
                ssl.setSSLParameters(params);
                ssl.startHandshake();
            }
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        }
    }
}

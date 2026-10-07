package io.rocoto.metrics.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.core.config.ConfigLoader;
import io.rocoto.core.jmx.MBeanServerAccess;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricProfiles;
import io.rocoto.metrics.MetricRule;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.security.RequestPipeline;
import io.rocoto.security.audit.AuditLogger;
import io.rocoto.security.policy.AccessPolicy;
import io.rocoto.security.policy.ValueMasker;

/** Each pusher against a real local receiver. */
class PushersTest {

    private final RequestPipeline pipeline = new RequestPipeline(
            new MBeanServerAccess(List.of(ManagementFactory.getPlatformMBeanServer())),
            AccessPolicy.secureDefault(), ValueMasker.fromConfig(Config.empty()), AuditLogger.fromConfig(Config.empty()));
    private final MetricsCollector collector = new MetricsCollector(MetricProfiles.load(Config.empty(), "generic", null));
    private static final Supplier<SSLContext> NO_TLS = () -> {
        throw new AssertionError("no TLS expected");
    };

    private static Supplier<List<MetricFamily>> extra() {
        MetricFamily f = new MetricFamily("rocoto_tls_cert_expiry_seconds", MetricRule.Type.GAUGE, "expiry");
        f.addSample(java.util.Map.of(), 86400);
        return () -> List.of(f);
    }

    @Test
    void graphiteOverTcp() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            Config c = ConfigLoader.parse("host: 127.0.0.1\nport: " + server.getLocalPort() + "\nprefix: test.app");
            new GraphitePusher(c, collector, pipeline, extra(), NO_TLS).runOnce();
            String text = received.get(10, TimeUnit.SECONDS);
            assertTrue(text.contains("test.app.jvm_threads_current "), text);
            assertTrue(text.contains("test.app.rocoto_tls_cert_expiry_seconds 86400 "), text);
            assertTrue(text.lines().allMatch(l -> l.matches("[\\w.-]+ -?[0-9.E-]+ \\d+")), text);
        }
    }

    @Test
    void statsdOverUdp() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(10_000);
            Config c = ConfigLoader.parse("host: 127.0.0.1\nport: " + server.getLocalPort() + "\nmaxPacketBytes: 512");
            new StatsdPusher(c, collector, pipeline, null).runOnce();
            StringBuilder all = new StringBuilder();
            int packets = 0;
            try {
                while (true) {
                    DatagramPacket p = new DatagramPacket(new byte[2048], 2048);
                    server.receive(p);
                    assertTrue(p.getLength() <= 512, "packet too large: " + p.getLength());
                    all.append(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8)).append('\n');
                    packets++;
                    server.setSoTimeout(500);
                }
            } catch (java.net.SocketTimeoutException done) {
                // all packets read
            }
            assertTrue(packets > 1, "lines are split over several datagrams");
            assertTrue(all.toString().contains("rocoto.jvm_threads_current:"), all.toString());
            assertTrue(all.toString().lines().filter(l -> !l.isEmpty()).allMatch(l -> l.matches("[\\w.-]+:-?[0-9.E-]+\\|[gc]")),
                    all.toString());
        }
    }

    @Test
    void influxV2OverHttp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CompletableFuture<String[]> received = new CompletableFuture<>();
        server.createContext("/api/v2/write", exchange -> {
            received.complete(new String[] {exchange.getRequestURI().toString(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)});
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            Config c = ConfigLoader.parse("url: http://127.0.0.1:" + server.getAddress().getPort() + "\n"
                    + "allowInsecure: true\norg: ops\nbucket: jvm metrics\ntoken: t0ken\nprecision: ms");
            new InfluxPusher(c, collector, pipeline, null, NO_TLS).runOnce();
            String[] r = received.get(10, TimeUnit.SECONDS);
            assertEquals("/api/v2/write?org=ops&bucket=jvm+metrics&precision=ms", r[0]);
            assertEquals("Token t0ken", r[1]);
            assertTrue(r[2].contains("jvm_threads_current value="), r[2]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void invalidPushConfigurationIsRejected() {
        assertThrows(ConfigException.class, () -> MetricsPushers.start(
                ConfigLoader.parse("push:\n  graphite: { enabled: true }"), NO_TLS, collector, pipeline, null));
        assertThrows(ConfigException.class, () -> MetricsPushers.start(ConfigLoader.parse(
                "push:\n  influx: { enabled: true, url: 'http://influx:8086', org: o, bucket: b }"),
                NO_TLS, collector, pipeline, null), "plain http needs allowInsecure");
        assertThrows(ConfigException.class, () -> MetricsPushers.start(ConfigLoader.parse(
                "push:\n  influx: { enabled: true, url: 'https://influx:8086', version: 2 }"),
                () -> null, collector, pipeline, null), "org and bucket are required");
        assertEquals(0, MetricsPushers.start(Config.empty(), NO_TLS, collector, pipeline, null).count(),
                "nothing enabled, nothing started, no TLS context built");
    }
}

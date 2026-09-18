package io.rocoto.api;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricRule;
import io.rocoto.security.tls.TlsProbe;

/**
 * Optional watchdog for the server's own HTTPS listener ({@code tls.selfProbe}). TLS is
 * terminated by the application server's connector, so Rocoto cannot staple OCSP responses
 * itself – but it can notice when the connector stops doing so, or when a certificate is about
 * to expire, and report that through {@code /metrics} and {@code /api/v1/health}.
 */
public final class TlsSelfProbe implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TlsSelfProbe.class.getName());

    private final String host;
    private final int port;
    private final boolean expectStapling;
    private final long warnDays;
    private final ScheduledExecutorService scheduler;
    private volatile TlsProbe.Result last;
    private volatile String lastError;

    private TlsSelfProbe(String host, int port, boolean expectStapling, long warnDays, long intervalSeconds) {
        this.host = host;
        this.port = port;
        this.expectStapling = expectStapling;
        this.warnDays = warnDays;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rocoto-tls-probe");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::run, 5, intervalSeconds, TimeUnit.SECONDS);
    }

    /** @param probe the {@code tls.selfProbe} section; returns {@code null} when no URL is configured */
    public static TlsSelfProbe start(Config probe) {
        String url = probe.string("url", null);
        if (url == null) {
            return null;
        }
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ConfigException("tls.selfProbe.url must be an https URL");
        }
        return new TlsSelfProbe(uri.getHost(), uri.getPort() < 0 ? 443 : uri.getPort(),
                probe.bool("expectStapling", true), probe.longValue("warnDaysBeforeExpiry", 21),
                Math.max(30, probe.longValue("intervalSeconds", 300)));
    }

    private void run() {
        try {
            last = TlsProbe.probe(host, port, 5000);
            lastError = null;
        } catch (java.io.IOException | RuntimeException e) {
            lastError = e.getMessage();
            LOG.warning("TLS self probe of " + host + ":" + port + " failed: " + e.getMessage());
        }
    }

    public List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        TlsProbe.Result r = last;
        if (lastError != null) {
            warnings.add("TLS self probe failed: " + lastError);
        }
        if (r == null) {
            return warnings;
        }
        if (expectStapling && !r.stapled()) {
            warnings.add("the HTTPS listener does not staple an OCSP response");
        }
        if (r.ocsp() != null && r.ocsp().certStatus() != null && !"good".equals(r.ocsp().certStatus())) {
            warnings.add("stapled OCSP response reports certificate status '" + r.ocsp().certStatus() + "'");
        }
        Instant expiry = r.earliestExpiry();
        if (expiry != null && expiry.isBefore(Instant.now().plus(Duration.ofDays(warnDays)))) {
            warnings.add("a certificate of the HTTPS listener expires on " + expiry);
        }
        return warnings;
    }

    public List<MetricFamily> metrics() {
        TlsProbe.Result r = last;
        if (r == null) {
            return List.of();
        }
        List<MetricFamily> families = new ArrayList<>();
        Instant expiry = r.earliestExpiry();
        long now = Instant.now().getEpochSecond();
        if (expiry != null) {
            families.add(gauge("rocoto_tls_cert_expiry_seconds", "Seconds until the first certificate of the chain expires",
                    Map.of(), expiry.getEpochSecond() - now));
        }
        String status = !r.stapled() ? "none" : r.ocsp() == null || r.ocsp().certStatus() == null ? "unknown"
                : r.ocsp().certStatus();
        families.add(gauge("rocoto_tls_ocsp_stapled", "1 if the HTTPS listener staples an OCSP response",
                Map.of("status", status), r.stapled() ? 1 : 0));
        if (r.ocsp() != null && r.ocsp().nextUpdate() != null) {
            families.add(gauge("rocoto_tls_ocsp_next_update_seconds",
                    "Seconds until the stapled OCSP response must be refreshed", Map.of(),
                    r.ocsp().nextUpdate().getEpochSecond() - now));
        }
        return families;
    }

    private static MetricFamily gauge(String name, String help, Map<String, String> labels, double value) {
        MetricFamily family = new MetricFamily(name, MetricRule.Type.GAUGE, help);
        family.addSample(labels, value);
        return family;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}

package io.rocoto.metrics.push;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;

import io.rocoto.core.config.Config;
import io.rocoto.core.config.ConfigException;
import io.rocoto.metrics.MetricFamily;
import io.rocoto.metrics.MetricsCollector;
import io.rocoto.metrics.format.InfluxFormat;
import io.rocoto.security.RequestPipeline;

/**
 * InfluxDB line protocol over the HTTP write API.
 * <ul>
 *   <li>{@code version: 2} (default, also InfluxDB 3 and Cloud): {@code POST /api/v2/write?org=&bucket=&precision=},
 *       {@code Authorization: Token <token>};</li>
 *   <li>{@code version: 1}: {@code POST /write?db=&precision=}, optional Basic credentials.</li>
 * </ul>
 */
final class InfluxPusher extends MetricsPusher {

    private final URI endpoint;
    private final String authorization;
    private final InfluxFormat format;
    private final HttpClient client;

    InfluxPusher(Config c, MetricsCollector collector, RequestPipeline pipeline, Supplier<List<MetricFamily>> extra,
            Supplier<SSLContext> sslContext) {
        super("influx", c, 60, collector, pipeline, extra);
        String url = c.string("url", null);
        if (url == null) {
            throw new ConfigException("metrics.push.influx.url is required, e.g. https://influx.example.com:8086");
        }
        URI base = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        boolean https = "https".equalsIgnoreCase(base.getScheme());
        if (!https && !c.bool("allowInsecure", false)) {
            throw new ConfigException("metrics.push.influx.url must use https (or set metrics.push.influx.allowInsecure)");
        }
        try {
            this.format = new InfluxFormat(c.string("precision", "ns"));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("metrics.push.influx.precision: " + e.getMessage());
        }
        int version = c.integer("version", 2);
        if (version == 2) {
            String org = required(c, "org");
            String bucket = required(c, "bucket");
            this.endpoint = URI.create(base + "/api/v2/write?org=" + enc(org) + "&bucket=" + enc(bucket)
                    + "&precision=" + v2Precision(format.precision()));
            String token = c.string("token", null);
            this.authorization = token == null ? null : "Token " + token;
        } else if (version == 1) {
            this.endpoint = URI.create(base + "/write?db=" + enc(required(c, "database"))
                    + "&precision=" + v1Precision(format.precision()));
            String user = c.string("username", null);
            this.authorization = user == null ? null : "Basic " + Base64.getEncoder().encodeToString(
                    (user + ":" + c.string("password", "")).getBytes(StandardCharsets.UTF_8));
        } else {
            throw new ConfigException("metrics.push.influx.version must be 1 or 2");
        }
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        if (https) {
            builder.sslContext(sslContext.get());
        }
        this.client = builder.build();
    }

    @Override
    protected String target() {
        return endpoint.getScheme() + "://" + endpoint.getAuthority() + endpoint.getPath();
    }

    @Override
    protected void push(List<MetricFamily> families, long nowMillis) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(format.toString(families, nowMillis)));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            String body = response.body();
            throw new IllegalStateException("InfluxDB answered " + response.statusCode() + ": "
                    + (body.length() > 200 ? body.substring(0, 200) : body));
        }
    }

    private static String required(Config c, String key) {
        String v = c.string(key, null);
        if (v == null || v.isBlank()) {
            throw new ConfigException("metrics.push.influx." + key + " is required");
        }
        return v;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String v2Precision(TimeUnit unit) {
        return switch (unit) {
            case MICROSECONDS -> "us";
            case MILLISECONDS -> "ms";
            case SECONDS -> "s";
            default -> "ns";
        };
    }

    private static String v1Precision(TimeUnit unit) {
        return switch (unit) {
            case MICROSECONDS -> "u";
            case MILLISECONDS -> "ms";
            case SECONDS -> "s";
            default -> "n";
        };
    }
}

package com.example.rocoto.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Talks to Rocoto with nothing but the JDK ({@code java.net.http}).
 *
 * <p>Environment: {@code ROCOTO_URL} (default {@code http://localhost:8080/rocoto}) and either
 * {@code ROCOTO_TOKEN} or {@code ROCOTO_USER} + {@code ROCOTO_PASSWORD}.
 *
 * <p>The JDK has no JSON parser, so this example prints the raw JSON documents. In a real
 * application parse them with the JSON library you already use.
 */
public final class RocotoClientExample {

    private static final String ORDERS = "com.example.demo:type=Orders";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;
    private final String authorization;

    private RocotoClientExample(String baseUrl, String authorization) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.authorization = authorization;
    }

    public static void main(String[] args) throws Exception {
        String url = env("ROCOTO_URL", "http://localhost:8080/rocoto");
        RocotoClientExample client = new RocotoClientExample(url, authorizationFromEnvironment());

        System.out.println("== who am I");
        System.out.println(client.get("/api/v1/server").body());

        System.out.println("== read one attribute (resource style, ObjectName URL-encoded)");
        System.out.println(client.get("/api/v1/mbeans/" + encode("java.lang:type=Memory")
                + "/attributes/HeapMemoryUsage").body());

        System.out.println("== read (RPC style, no escaping needed)");
        System.out.println(client.post("/api/v1/read",
                "{\"mbean\":\"" + ORDERS + "\",\"attributes\":[\"OrdersProcessed\",\"QueueDepth\"]}").body());

        System.out.println("== bulk: every item carries its own status");
        System.out.println(client.post("/api/v1/bulk", """
                [ {"type":"read","mbean":"java.lang:type=Threading","attribute":"ThreadCount"},
                  {"type":"read","mbean":"java.lang:type=Runtime","attribute":"DoesNotExist"},
                  {"type":"search","pattern":"java.lang:type=GarbageCollector,*"} ]""").body());

        System.out.println("== exec (needs an allow rule for the caller's role)");
        HttpResponse<String> exec = client.post("/api/v1/exec",
                "{\"mbean\":\"" + ORDERS + "\",\"operation\":\"drainQueue\",\"arguments\":[5]}");
        System.out.println(describe(exec));

        System.out.println("== error handling");
        System.out.println(describe(client.get("/api/v1/mbeans/" + encode("nope:type=None"))));

        System.out.println("== Server-Sent Events: three values of QueueDepth");
        client.sse("/api/v1/subscribe?mbean=" + encode(ORDERS)
                + "&attribute=QueueDepth&interval=1000&onChange=false", 3);

        System.out.println("== WebSocket: three values of ThreadCount");
        client.webSocket(3);
    }

    // ---- plain requests ------------------------------------------------------------------

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Rocoto only accepts request bodies sent as application/json (anything else is a 415). */
    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization);
    }

    /** Errors are JSON as well: {"status":403,"error":"Access denied by policy"}. */
    private static String describe(HttpResponse<String> response) {
        return switch (response.statusCode()) {
            case 200 -> "OK " + response.body();
            case 401 -> "401 - wrong or missing credentials";
            case 403 -> "403 - denied by policy (or credentials sent over plain HTTP): " + response.body();
            case 404 -> "404 - no such MBean, attribute or operation: " + response.body();
            case 429 -> "429 - locked after too many failed logins, retry after "
                    + response.headers().firstValue("Retry-After").orElse("?") + " s";
            default -> response.statusCode() + " " + response.body();
        };
    }

    // ---- streaming -----------------------------------------------------------------------

    /** Consumes {@code count} "value" events. Closing the line stream closes the connection. */
    private void sse(String path, int count) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", authorization)
                .header("Accept", "text/event-stream").GET().build(); // no timeout: the stream is endless
        HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        try (Stream<String> lines = response.body()) {
            if (response.statusCode() != 200) {
                // denied or unknown targets fail before the stream starts, with a JSON error body
                System.out.println(response.statusCode() + " " + String.join("", lines.toList()));
                return;
            }
            String[] event = { "message" };
            int[] seen = { 0 };
            lines.takeWhile(line -> seen[0] < count).forEach(line -> {
                if (line.startsWith("event: ")) {
                    event[0] = line.substring(7);
                } else if (line.startsWith("data: ")) {
                    System.out.println(event[0] + " " + line.substring(6));
                    if (event[0].equals("value") || event[0].equals("error")) {
                        seen[0]++;
                    }
                }
            });
        }
    }

    private void webSocket(int count) throws Exception {
        URI uri = URI.create(baseUrl.replaceFirst("^http", "ws") + "/api/v1/ws");
        CompletableFuture<Void> done = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();
            private int seen;

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    System.out.println(buffer);
                    buffer.setLength(0);
                    if (++seen >= count) {
                        done.complete(null);
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                done.completeExceptionally(error);
            }
        };
        // The upgrade request is authenticated like any other request.
        WebSocket socket = http.newWebSocketBuilder().header("Authorization", authorization)
                .buildAsync(uri, listener).get(10, TimeUnit.SECONDS);
        socket.sendText("{\"action\":\"subscribe\",\"id\":\"threads\",\"mbean\":\"java.lang:type=Threading\","
                + "\"attributes\":[\"ThreadCount\"],\"interval\":1000,\"onChange\":false}", true);
        done.get(30, TimeUnit.SECONDS);
        socket.sendText("{\"action\":\"unsubscribe\",\"id\":\"threads\"}", true);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static String authorizationFromEnvironment() {
        String token = System.getenv("ROCOTO_TOKEN");
        if (token != null && !token.isBlank()) {
            return "Bearer " + token;
        }
        String user = System.getenv("ROCOTO_USER");
        String password = System.getenv("ROCOTO_PASSWORD");
        if (user == null || password == null) {
            throw new IllegalStateException("Set ROCOTO_TOKEN, or ROCOTO_USER and ROCOTO_PASSWORD");
        }
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** ObjectNames inside a URL path must be encoded into ONE segment. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

package io.rocoto.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** auth.mode=builtin: the whole product over HTTP against the real WAR. */
class BuiltinAuthIT {

    private static final String MEMORY = URLEncoder.encode("java.lang:type=Memory", StandardCharsets.UTF_8);
    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer("""
                auth:
                  mode: builtin
                  allowInsecureTransport: true   # the test server speaks plain HTTP
                  builtin:
                    lockout: { maxAttempts: 4, lockSeconds: 60 }
                policy:
                  rules:
                    - roles: [rocoto-operator, rocoto-admin]
                      allow: [exec]
                      mbean: "java.lang:type=Memory"
                      operations: [gc]
                    - roles: [rocoto-admin]
                      allow: [write]
                      mbean: "java.lang:type=Memory"
                      attributes: [Verbose]
                    - roles: [rocoto-admin]
                      allow: [exec]
                      mbean: "com.sun.management:type=DiagnosticCommand"
                audit:
                  file: "%DIR%/audit.log"
                stream:
                  minIntervalMillis: 100
                """, false);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    // ---- authentication ------------------------------------------------------------------

    @Test
    void nothingIsReachableAnonymously() throws Exception {
        for (String path : List.of("/api/v1/server", "/api/v1/mbeans", "/api/v1/health", "/api/v1/health/live",
                "/metrics", "/jolokia/version", "/jolokia/", "/api/v1/subscribe?mbean=java.lang:type=Memory",
                "/api/v1/openapi.json", "/")) {
            HttpResponse<String> r = server.get(path, null);
            assertEquals(401, r.statusCode(), path);
            assertTrue(r.headers().allValues("WWW-Authenticate").stream().anyMatch(h -> h.startsWith("Basic")), path);
        }
        assertEquals(401, server.get("/api/v1/server", TestServer.basic("ada", "wrong")).statusCode());
        assertEquals(401, server.get("/api/v1/server", "Bearer rct_prometheus_wrong").statusCode());
        assertTrue(server.auditLog().contains("AUTH_FAILED"));
    }

    @Test
    void basicAndTokenAuthentication() throws Exception {
        Map<String, Object> who = TestServer.json(server.get("/api/v1/server", TestServer.basic(TestServer.ADMIN)));
        assertEquals("ada", ((Map<?, ?>) who.get("caller")).get("name"));
        assertEquals("basic", ((Map<?, ?>) who.get("caller")).get("authenticatedBy"));
        assertEquals("tomcat", ((Map<?, ?>) who.get("server")).get("adapter"));

        who = TestServer.json(server.get("/api/v1/server", "Bearer " + server.tokenReader));
        assertEquals("token:prometheus", ((Map<?, ?>) who.get("caller")).get("name"));
    }

    @Test
    void responsesCarryHardeningHeaders() throws Exception {
        HttpResponse<String> r = server.get("/api/v1/server", TestServer.basic(TestServer.READER));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        assertTrue(r.headers().firstValue("Set-Cookie").isEmpty());
    }

    @Test
    void openApiDocumentAndSchemaAreServed() throws Exception {
        Map<String, Object> openApi = TestServer.json(
                server.get("/api/v1/openapi.json", TestServer.basic(TestServer.READER)));
        assertEquals("3.0.3", openApi.get("openapi"));
        assertTrue(((Map<?, ?>) openApi.get("paths")).containsKey("/mbeans/{name}/attributes/{attribute}"));
        Map<String, Object> schema = TestServer.json(
                server.get("/api/v1/schema?mbean=java.lang:type=Memory", TestServer.basic(TestServer.READER)));
        assertTrue(((Map<?, ?>) schema.get("properties")).containsKey("HeapMemoryUsage"));
        Map<String, Object> health = TestServer.json(
                server.get("/api/v1/health", TestServer.basic(TestServer.READER)));
        assertEquals("WARN", health.get("status"), "allowInsecureTransport must show up as a warning");
    }

    // ---- CSRF / CORS ---------------------------------------------------------------------

    @Test
    void crossOriginAndFormPostsAreRejected() throws Exception {
        String admin = TestServer.basic(TestServer.ADMIN);
        assertEquals(403, server.get("/api/v1/server", admin, "Origin", "https://evil.example").statusCode());
        HttpResponse<String> preflight = server.send("OPTIONS", "/api/v1/exec", null, null, "",
                "Origin", "https://evil.example", "Access-Control-Request-Method", "POST");
        assertEquals(403, preflight.statusCode());
        assertTrue(preflight.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        // <img src=...> style request: no Origin header, but browsers label it
        assertEquals(403, server.get("/jolokia/read/java.lang:type=Memory/Verbose", admin,
                "Sec-Fetch-Site", "cross-site").statusCode());
        assertEquals(200, server.get("/api/v1/server", admin, "Sec-Fetch-Site", "same-origin").statusCode());
        // what an HTML form can send
        assertEquals(415, server.send("POST", "/api/v1/exec", admin, "application/x-www-form-urlencoded",
                "mbean=java.lang:type=Memory&operation=gc").statusCode());
        assertEquals(415, server.send("POST", "/api/v1/exec", admin, "text/plain",
                "{\"mbean\":\"java.lang:type=Memory\",\"operation\":\"gc\"}").statusCode());
    }

    // ---- read / list ---------------------------------------------------------------------

    @Test
    void readInBothStyles() throws Exception {
        String reader = TestServer.basic(TestServer.READER);
        Map<String, Object> rest = TestServer.json(
                server.get("/api/v1/mbeans/" + MEMORY + "/attributes/HeapMemoryUsage", reader));
        assertTrue(rest.get("value") instanceof Map<?, ?> usage && usage.containsKey("used"), String.valueOf(rest));

        Map<String, Object> rpc = TestServer.json(server.postJson("/api/v1/read", reader,
                "{\"mbean\":\"java.lang:type=MemoryPool,*\",\"attribute\":\"Name\"}"));
        assertFalse(((Map<?, ?>) rpc.get("values")).isEmpty());

        Map<String, Object> search = TestServer.json(server.get("/api/v1/mbeans?pattern=java.lang:type=Mem*", reader));
        assertTrue(((List<?>) search.get("mbeans")).contains("java.lang:type=Memory"));

        Map<String, Object> describe = TestServer.json(server.get("/api/v1/mbeans/" + MEMORY, reader));
        assertTrue(((Map<?, ?>) describe.get("attributes")).containsKey("Verbose"));

        assertEquals(404, server.get("/api/v1/mbeans/" + MEMORY + "/attributes/Nope", reader).statusCode());
        assertEquals(404, server.get("/api/v1/mbeans/nope%3Atype%3DNone", reader).statusCode());
        assertEquals(400, server.postJson("/api/v1/read", reader, "{not json").statusCode());
        HttpResponse<String> outOfRange = server.postJson("/api/v1/read", reader,
                "{\"mbean\":\"java.lang:type=Memory\",\"attribute\":\"Verbose\",\"extra\":1e2147483649}");
        assertEquals(400, outOfRange.statusCode(), outOfRange.body());
        assertTrue(outOfRange.body().contains("Decimal exponent out of range"), outOfRange.body());
    }

    @Test
    void tomcatContextNamesWithSlashesWorkInRpcStyle() throws Exception {
        String reader = TestServer.basic(TestServer.READER);
        Map<String, Object> sessions = TestServer.json(server.get("/api/v1/resources/sessions", reader));
        List<?> items = (List<?>) sessions.get("items");
        assertFalse(items.isEmpty());
        String mbean = (String) ((Map<?, ?>) items.get(0)).get("mbean");
        assertTrue(mbean.contains("/rocoto"), mbean);
        Map<String, Object> read = TestServer.json(server.postJson("/api/v1/read", reader,
                "{\"mbean\":\"" + mbean + "\",\"attribute\":\"activeSessions\"}"));
        assertNotNull(read.get("value"));
    }

    // ---- write / exec --------------------------------------------------------------------

    @Test
    void writeAndExecFollowThePolicy() throws Exception {
        String gc = "{\"mbean\":\"java.lang:type=Memory\",\"operation\":\"gc\"}";
        assertEquals(403, server.postJson("/api/v1/exec", TestServer.basic(TestServer.READER), gc).statusCode());
        assertEquals(200, server.postJson("/api/v1/exec", TestServer.basic(TestServer.OPERATOR), gc).statusCode());
        assertEquals(200, server.postJson("/api/v1/mbeans/" + MEMORY + "/operations/gc",
                TestServer.basic(TestServer.OPERATOR), "{}").statusCode());

        String verbose = "/api/v1/mbeans/" + MEMORY + "/attributes/Verbose";
        assertEquals(403, server.send("PUT", verbose, TestServer.basic(TestServer.OPERATOR), "application/json",
                "{\"value\":true}").statusCode());
        HttpResponse<String> put = server.send("PUT", verbose, TestServer.basic(TestServer.ADMIN),
                "application/json", "{\"value\":true}");
        assertEquals(200, put.statusCode(), put.body());
        assertEquals(Boolean.FALSE, TestServer.json(put).get("previousValue"));
        server.send("PUT", verbose, TestServer.basic(TestServer.ADMIN), "application/json", "{\"value\":false}");

        // not covered by any allow rule, even for admins
        assertEquals(403, server.postJson("/api/v1/exec", TestServer.basic(TestServer.ADMIN),
                "{\"mbean\":\"java.lang:type=Threading\",\"operation\":\"resetPeakThreadCount\"}").statusCode());

        String audit = server.auditLog();
        assertTrue(audit.contains("\"action\":\"EXEC\"") && audit.contains("\"outcome\":\"ALLOWED\""));
        assertTrue(audit.contains("\"outcome\":\"DENIED\""));
        assertTrue(audit.contains("\"user\":\"otto\""));
    }

    @Test
    void denyListCannotBeOverriddenByPolicy() throws Exception {
        // the test policy explicitly allows this for admins - the built-in deny list still wins
        HttpResponse<String> r = server.postJson("/api/v1/exec", TestServer.basic(TestServer.ADMIN),
                "{\"mbean\":\"com.sun.management:type=DiagnosticCommand\",\"operation\":\"vmVersion\"}");
        assertEquals(403, r.statusCode());
        assertFalse(r.body().contains("deny list"), "the reason stays in the audit log");
        assertTrue(server.auditLog().contains("built-in deny list"));
    }

    @Test
    void bulkReportsPerRequestStatus() throws Exception {
        Map<String, Object> bulk = TestServer.json(server.postJson("/api/v1/bulk",
                TestServer.basic(TestServer.READER), """
                [ {"type":"read","mbean":"java.lang:type=Runtime","attribute":"VmName"},
                  {"type":"exec","mbean":"java.lang:type=Memory","operation":"gc"},
                  {"type":"read","mbean":"java.lang:type=Runtime","attribute":"Missing"} ]"""));
        List<?> results = (List<?>) bulk.get("results");
        assertEquals(200L, ((Map<?, ?>) results.get(0)).get("status"));
        assertEquals(403L, ((Map<?, ?>) results.get(1)).get("status"));
        assertEquals(404L, ((Map<?, ?>) results.get(2)).get("status"));
    }

    // ---- token scope / metrics -----------------------------------------------------------

    @Test
    void metricsRespectTheScrapersIdentity() throws Exception {
        HttpResponse<String> full = server.get("/metrics", "Bearer " + server.tokenReader);
        assertEquals(200, full.statusCode());
        assertTrue(full.body().contains("jvm_memory_heap_used_bytes"));
        assertTrue(full.body().contains("tomcat_threads_busy{connector="), full.body());

        HttpResponse<String> scoped = server.get("/metrics", "Bearer " + server.tokenScoped,
                "Accept", "application/openmetrics-text");
        assertTrue(scoped.body().contains("jvm_threads_current"));
        assertFalse(scoped.body().contains("jvm_memory_heap_used_bytes"));
        assertTrue(scoped.body().endsWith("# EOF\n"));
        assertEquals(403, server.get("/api/v1/mbeans/" + MEMORY + "/attributes/Verbose",
                "Bearer " + server.tokenScoped).statusCode());
    }

    // ---- Jolokia compatibility -----------------------------------------------------------

    @Test
    void jolokiaProtocol() throws Exception {
        String reader = TestServer.basic(TestServer.READER);
        Map<String, Object> version = TestServer.json(server.get("/jolokia/version", reader));
        assertEquals(200L, version.get("status"));
        assertEquals("7.2", ((Map<?, ?>) version.get("value")).get("protocol"));

        Map<String, Object> read = TestServer.json(
                server.get("/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used", reader));
        assertTrue(read.get("value") instanceof Long, String.valueOf(read));
        assertEquals("read", ((Map<?, ?>) read.get("request")).get("type"));
        assertNotNull(read.get("timestamp"));

        Map<String, Object> missing = TestServer.json(server.get("/jolokia/read/java.lang:type=Nope/X", reader));
        assertEquals(404L, missing.get("status"));
        assertEquals("javax.management.InstanceNotFoundException", missing.get("error_type"));
        assertFalse(missing.containsKey("stacktrace"));

        HttpResponse<String> bulk = server.postJson("/jolokia", reader, """
                [ {"type":"read","mbean":"java.lang:type=Threading","attribute":["ThreadCount","PeakThreadCount"]},
                  {"type":"search","mbean":"java.lang:type=Memory*"},
                  {"type":"exec","mbean":"java.lang:type=Memory","operation":"gc"},
                  {"type":"read","mbean":"java.lang:type=Memory","attribute":"Verbose","target":{"url":"service:jmx:rmi:///jndi/ldap://evil/x"}} ]""");
        List<?> results = (List<?>) io.rocoto.core.json.JsonParser.parse(bulk.body());
        assertEquals(200L, ((Map<?, ?>) results.get(0)).get("status"));
        assertTrue(((Map<?, ?>) ((Map<?, ?>) results.get(0)).get("value")).containsKey("PeakThreadCount"));
        assertTrue(((List<?>) ((Map<?, ?>) results.get(1)).get("value")).contains("java.lang:type=Memory"));
        assertEquals(403L, ((Map<?, ?>) results.get(2)).get("status"), "exec is denied for readers");
        assertEquals(403L, ((Map<?, ?>) results.get(3)).get("status"), "proxy mode does not exist");

        // state changes by URL: fine for non-browser clients, refused for anything that looks like a browser
        assertEquals(200L, TestServer.json(server.get("/jolokia/exec/java.lang:type=Memory/gc",
                TestServer.basic(TestServer.OPERATOR))).get("status"));
        assertEquals(405, server.get("/jolokia/exec/java.lang:type=Memory/gc",
                TestServer.basic(TestServer.OPERATOR), "Sec-Fetch-Site", "same-origin").statusCode());
        Map<String, Object> exec = TestServer.json(server.postJson("/jolokia", TestServer.basic(TestServer.OPERATOR),
                "{\"type\":\"exec\",\"mbean\":\"java.lang:type=Memory\",\"operation\":\"gc\"}"));
        assertEquals(200L, exec.get("status"));

        Map<String, Object> list = TestServer.json(server.get("/jolokia/list/java.lang/type=Memory", reader));
        Map<?, ?> memory = (Map<?, ?>) list.get("value");
        assertTrue(((Map<?, ?>) memory.get("attr")).containsKey("HeapMemoryUsage"));
        assertTrue(((Map<?, ?>) memory.get("op")).containsKey("gc"));

        assertEquals(400, server.get("/jolokia/version?callback=alert", reader).statusCode());
    }

    // ---- streaming -----------------------------------------------------------------------

    @Test
    void serverSentEvents() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(server.uri(
                "/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=100"))
                .header("Authorization", TestServer.basic(TestServer.READER)).timeout(Duration.ofSeconds(20)).build();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        try (Stream<String> lines = response.body()) {
            List<String> data = lines.filter(l -> l.startsWith("data: ")).limit(3).toList();
            assertEquals(3, data.size());
            assertTrue(data.get(0).contains("\"Uptime\""));
        }
        // denied and unknown targets fail before the stream starts
        assertEquals(404, server.get("/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Nope",
                TestServer.basic(TestServer.READER)).statusCode());
        assertEquals(403, server.get("/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime",
                "Bearer " + server.tokenScoped).statusCode());
    }

    @Test
    void revokingATokenEndsItsRunningStream() throws Exception {
        java.nio.file.Path users = server.dir.resolve("rocoto-users.yaml");
        String original = java.nio.file.Files.readString(users);
        io.rocoto.security.auth.ApiTokens.Generated temp = io.rocoto.security.auth.ApiTokens.generate("temp");
        java.nio.file.Files.writeString(users, original
                + "  - { id: temp, hash: \"" + temp.storedHash() + "\", roles: [rocoto-reader] }\n");
        touch(users, 30);
        Thread.sleep(2200); // the user store checks for changes every two seconds

        HttpRequest request = HttpRequest.newBuilder(server.uri(
                "/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=100"))
                .header("Authorization", "Bearer " + temp.token()).timeout(Duration.ofSeconds(30)).build();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());

        java.nio.file.Files.writeString(users, original);
        touch(users, 60);
        try (Stream<String> lines = response.body()) {
            // the stream must terminate by itself with an error event once the token is gone
            List<String> all = lines.toList();
            assertTrue(all.stream().anyMatch(l -> l.startsWith("data: ") && l.contains("Uptime")));
            assertTrue(all.stream().anyMatch(l -> l.equals("event: error")), "stream ended with an error event");
            assertTrue(all.stream().anyMatch(l -> l.contains("no longer valid")));
        }
    }

    private static void touch(java.nio.file.Path file, int secondsAhead) throws java.io.IOException {
        java.nio.file.Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(secondsAhead)));
    }

    @Test
    void webSocketSubscriptions() throws Exception {
        URI ws = URI.create("ws://localhost:" + server.port() + "/rocoto/api/v1/ws");
        CompletableFuture<String> firstValue = new CompletableFuture<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    String message = buffer.toString();
                    buffer.setLength(0);
                    if (message.contains("\"event\":\"value\"") || message.contains("\"event\":\"error\"")) {
                        firstValue.complete(message);
                    }
                }
                webSocket.request(1);
                return null;
            }
        };
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", TestServer.basic(TestServer.READER))
                .buildAsync(ws, listener).get(10, TimeUnit.SECONDS);
        socket.sendText("{\"action\":\"subscribe\",\"id\":\"t\",\"mbean\":\"java.lang:type=Threading\","
                + "\"attributes\":[\"ThreadCount\"],\"interval\":100,\"onChange\":false}", true);
        String message = firstValue.get(10, TimeUnit.SECONDS);
        assertTrue(message.contains("\"id\":\"t\"") && message.contains("ThreadCount"), message);
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");

        // the upgrade request itself needs credentials
        try {
            HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(ws, new WebSocket.Listener() { })
                    .get(10, TimeUnit.SECONDS);
            org.junit.jupiter.api.Assertions.fail("anonymous WebSocket upgrade must be rejected");
        } catch (java.util.concurrent.ExecutionException expected) {
            assertTrue(expected.getCause() instanceof java.net.http.WebSocketHandshakeException e
                    && e.getResponse().statusCode() == 401, String.valueOf(expected.getCause()));
        }
    }

    // ---- lockout (runs against its own user so other tests are unaffected) ------------------

    @Test
    void repeatedFailuresLockTheAccount() throws Exception {
        int status = 0;
        for (int i = 0; i < 6; i++) {
            status = server.get("/api/v1/server", TestServer.basic("victim", "guess" + i)).statusCode();
        }
        assertEquals(429, status);
    }
}

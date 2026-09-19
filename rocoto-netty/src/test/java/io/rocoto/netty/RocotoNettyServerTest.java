package io.rocoto.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.rocoto.security.auth.PasswordHasher;

/** Starts the real standalone server with TLS and talks to it over the network. */
class RocotoNettyServerTest {

    private static final String PASSWORD = "Test-Password-12345";
    private static final String STORE_PASSWORD = "test-changeit-123";

    @TempDir
    static Path dir;
    private static RocotoNettyServer server;
    private static HttpClient client;
    private static String base;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path keystore = dir.resolve("server.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", keystore.toString(), "-storepass", STORE_PASSWORD).inheritIO().start();
        assertEquals(0, keytool.waitFor(), "keytool");

        String hash = PasswordHasher.hash(PASSWORD.toCharArray(), 100_000);
        Files.writeString(dir.resolve("rocoto-users.yaml"), """
                users:
                  - name: rita
                    password: %s
                    roles: [rocoto-reader]
                """.formatted(hash));
        Files.writeString(dir.resolve("rocoto.yaml"), """
                auth:
                  mode: builtin
                  builtin:
                    usersFile: rocoto-users.yaml
                embedded:
                  port: %d
                  tls:
                    keystore: %s
                    keystorePassword: %s
                """.formatted(port, keystore.toString().replace("\\", "/"), STORE_PASSWORD));
        System.setProperty("rocoto.config", dir.resolve("rocoto.yaml").toString());
        server = RocotoNettyServer.start(true);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            trust.load(in, STORE_PASSWORD.toCharArray());
        }
        KeyStore anchors = KeyStore.getInstance("PKCS12");
        anchors.load(null, null);
        anchors.setCertificateEntry("server", trust.getCertificate("server"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(anchors);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tmf.getTrustManagers(), null);
        client = HttpClient.newBuilder().sslContext(ssl).version(HttpClient.Version.HTTP_1_1).build();
        base = "https://localhost:" + port + "/rocoto";
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
        System.clearProperty("rocoto.config");
    }

    private static HttpRequest.Builder request(String path, String user) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
        if (user != null) {
            builder.header("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        }
        return builder;
    }

    private static HttpResponse<String> get(String path, String user) throws Exception {
        return client.send(request(path, user).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anonymousIsRejectedWithAChallenge() throws Exception {
        HttpResponse<String> response = get("/api/v1/server", null);
        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").isPresent());
    }

    @Test
    void builtinUserOverTls() throws Exception {
        HttpResponse<String> response = get("/api/v1/server", "rita");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"authenticatedBy\":\"basic\""), response.body());
        assertTrue(response.headers().firstValue("Content-Length").isPresent(), "buffered responses carry a length");
    }

    @Test
    void wrongPassword() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/server"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("rita:wrong".getBytes(StandardCharsets.UTF_8)))
                .build();
        assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void objectNameInThePath() throws Exception {
        HttpResponse<String> response = get("/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage", "rita");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"used\""), response.body());
    }

    @Test
    void postReadAndPolicy() throws Exception {
        HttpRequest read = request("/api/v1/read", "rita").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mbean\":\"java.lang:type=Memory\",\"attribute\":\"Verbose\"}")).build();
        HttpResponse<String> response = client.send(read, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"value\":false"), response.body());

        HttpRequest exec = request("/api/v1/exec", "rita").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"mbean\":\"java.lang:type=Memory\",\"operation\":\"gc\"}")).build();
        assertEquals(403, client.send(exec, HttpResponse.BodyHandlers.ofString()).statusCode());

        HttpRequest form = request("/api/v1/exec", "rita").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("a=b")).build();
        assertEquals(415, client.send(form, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void unknownPathsNeedAuthenticationFirst() throws Exception {
        assertEquals(401, get("/nope", null).statusCode());
        assertEquals(404, get("/nope", "rita").statusCode());
        HttpRequest outside = HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/other")).build();
        assertEquals(404, client.send(outside, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void metricsAndJolokia() throws Exception {
        HttpResponse<String> metrics = get("/metrics", "rita");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("jvm_memory_heap_used_bytes"), "JVM profile");
        HttpResponse<String> jolokia = get("/jolokia/read/java.lang:type=Memory/HeapMemoryUsage/used", "rita");
        assertTrue(jolokia.body().contains("\"status\":200"), jolokia.body());
    }

    @Test
    void serverSentEventsAreStreamed() throws Exception {
        HttpResponse<InputStream> response = client.send(
                request("/api/v1/subscribe?mbean=java.lang:type=Runtime&attribute=Uptime&interval=500&onChange=false", "rita")
                        .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        assertEquals("text/event-stream", response.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        int values = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while (values < 2 && (line = reader.readLine()) != null) {
                if (line.equals("event: value")) {
                    values++;
                }
            }
        }
        assertEquals(2, values, "two events arrive while the response is still open");
    }
}

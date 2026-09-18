package io.rocoto.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;

import io.rocoto.core.json.JsonParser;
import io.rocoto.security.auth.ApiTokens;
import io.rocoto.security.auth.PasswordHasher;

/** Embedded Tomcat with the real rocoto.war deployed at /rocoto. */
final class TestServer implements AutoCloseable {

    static final String READER = "rita";
    static final String OPERATOR = "otto";
    static final String ADMIN = "ada";
    static final String PASSWORD = "correct horse battery";

    final Path dir;
    final String tokenReader;
    final String tokenScoped;
    private final Tomcat tomcat;
    private final HttpClient client = HttpClient.newHttpClient();

    /**
     * @param rocotoYaml content of rocoto.yaml; {@code %DIR%} is replaced by the working directory
     */
    TestServer(String rocotoYaml, boolean withTomcatRealm) throws Exception {
        dir = Files.createTempDirectory("rocoto-it");
        String hash = PasswordHasher.hash(PASSWORD.toCharArray(), 100_000);
        ApiTokens.Generated reader = ApiTokens.generate("prometheus");
        ApiTokens.Generated scoped = ApiTokens.generate("scoped");
        tokenReader = reader.token();
        tokenScoped = scoped.token();
        Files.writeString(dir.resolve("rocoto-users.yaml"), """
                users:
                  - { name: rita, password: "%1$s", roles: [rocoto-reader] }
                  - { name: otto, password: "%1$s", roles: [rocoto-operator] }
                  - { name: ada, password: "%1$s", roles: [rocoto-admin] }
                tokens:
                  - { id: prometheus, hash: "%2$s", roles: [rocoto-reader] }
                  - { id: scoped, hash: "%3$s", roles: [rocoto-reader], mbeans: ["java.lang:type=Threading"] }
                """.formatted(hash, reader.storedHash(), scoped.storedHash()));
        Path config = dir.resolve("rocoto.yaml");
        Files.writeString(config, rocotoYaml.replace("%DIR%", dir.toString().replace("\\", "/")));
        System.setProperty("rocoto.config", config.toString());

        tomcat = new Tomcat();
        tomcat.setBaseDir(dir.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        if (withTomcatRealm) {
            tomcat.addUser("carl", "container-secret");
            tomcat.addRole("carl", "rocoto-admin");
            tomcat.addUser("rudi", "container-secret");
            tomcat.addRole("rudi", "some-unrelated-role");
        }
        Files.createDirectories(dir.resolve("tomcat/webapps"));
        Context context = tomcat.addWebapp("/rocoto", System.getProperty("rocoto.war"));
        context.setParentClassLoader(Thread.currentThread().getContextClassLoader());
        tomcat.start();
    }

    int port() {
        return tomcat.getConnector().getLocalPort();
    }

    URI uri(String path) {
        return URI.create("http://localhost:" + port() + "/rocoto" + path);
    }

    static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    static String basic(String user) {
        return basic(user, PASSWORD);
    }

    HttpResponse<String> get(String path, String authorization, String... headers) throws IOException,
            InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        return send(b, authorization, headers);
    }

    HttpResponse<String> send(String method, String path, String authorization, String contentType, String body,
            String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        return send(b, authorization, headers);
    }

    HttpResponse<String> postJson(String path, String authorization, String body) throws IOException,
            InterruptedException {
        return send("POST", path, authorization, "application/json", body);
    }

    private HttpResponse<String> send(HttpRequest.Builder b, String authorization, String... headers)
            throws IOException, InterruptedException {
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static Map<String, Object> json(HttpResponse<String> response) {
        return JsonParser.parseObject(response.body());
    }

    String auditLog() throws IOException {
        Path audit = dir.resolve("audit.log");
        return Files.exists(audit) ? Files.readString(audit) : "";
    }

    @Override
    public void close() throws Exception {
        tomcat.stop();
        tomcat.destroy();
        System.clearProperty("rocoto.config");
    }
}

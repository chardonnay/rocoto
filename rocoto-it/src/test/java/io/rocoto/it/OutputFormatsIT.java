package io.rocoto.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Output formats of the REST API, against the real WAR. */
class OutputFormatsIT {

    private static TestServer server;
    private static String reader;
    private static String operator;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer("""
                auth:
                  mode: builtin
                  allowInsecureTransport: true   # the test server speaks plain HTTP
                policy:
                  rules:
                    - roles: [rocoto-operator]
                      allow: [exec]
                      mbean: "java.lang:type=Memory"
                      operations: [gc]
                audit:
                  file: "%DIR%/audit.log"
                """, false);
        reader = TestServer.basic(TestServer.READER);
        operator = TestServer.basic(TestServer.OPERATOR);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    private static String contentType(HttpResponse<String> r) {
        return r.headers().firstValue("Content-Type").orElse("");
    }

    @Test
    void everyFormatByQueryParameter() throws Exception {
        Map<String, String> expected = Map.of(
                "json", "application/json",
                "xml", "application/xml",
                "yaml", "application/yaml",
                "toml", "application/toml",
                "html", "text/html",
                "csv", "text/csv",
                "text", "text/plain",
                "table", "text/plain");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            HttpResponse<String> r = server.get("/api/v1/resources/threadpools?format=" + e.getKey(), reader);
            assertEquals(200, r.statusCode(), e.getKey() + ": " + r.body());
            assertTrue(contentType(r).startsWith(e.getValue()), e.getKey() + " -> " + contentType(r));
            assertTrue(r.body().contains("http-nio"), e.getKey() + ": " + r.body());
            assertEquals("Accept", r.headers().firstValue("Vary").orElse(null));
        }
    }

    @Test
    void formatByAcceptHeader() throws Exception {
        HttpResponse<String> yaml = server.get("/api/v1/server", reader, "Accept", "application/yaml");
        assertTrue(contentType(yaml).startsWith("application/yaml"));
        assertTrue(yaml.body().contains("authenticatedBy: basic"), yaml.body());

        HttpResponse<String> browser = server.get("/api/v1/server", reader, "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        assertTrue(contentType(browser).startsWith("text/html"));

        HttpResponse<String> any = server.get("/api/v1/server", reader, "Accept", "*/*");
        assertTrue(contentType(any).startsWith("application/json"));
    }

    @Test
    void textAndTableShapes() throws Exception {
        String text = server.get("/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/HeapMemoryUsage?format=text",
                reader).body();
        assertTrue(text.contains("value.used: "), text);
        String table = server.get("/api/v1/resources/threadpools?format=table&ascii=true", reader).body();
        assertTrue(table.contains("| name"), table);
        assertTrue(table.contains("kind: threadpools"), table);
    }

    @Test
    void htmlPageIsLockedDownAndStyled() throws Exception {
        HttpResponse<String> html = server.get("/api/v1/resources/threadpools?format=html", reader);
        String csp = html.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("default-src 'none'") && csp.contains("style-src 'self'"), csp);
        assertFalse(csp.contains("script-src"), csp);
        assertFalse(html.body().contains("<script"));
        assertTrue(html.body().contains("href=\"/rocoto/api/v1/style.css\""));

        HttpResponse<String> css = server.get("/api/v1/style.css", reader);
        assertEquals(200, css.statusCode());
        assertTrue(contentType(css).startsWith("text/css"));
        assertEquals(401, server.get("/api/v1/style.css", null).statusCode(), "no anonymous access, not even here");
    }

    @Test
    void errorsComeInTheRequestedFormat() throws Exception {
        HttpResponse<String> notFound = server.get("/api/v1/mbeans/java.lang%3Atype%3DNope?format=xml", reader);
        assertEquals(404, notFound.statusCode());
        assertTrue(contentType(notFound).startsWith("application/xml"));
        assertTrue(notFound.body().contains("<status type=\"number\">404</status>"), notFound.body());
    }

    @Test
    void unknownFormatIsRejectedBeforeAnythingIsExecuted() throws Exception {
        HttpResponse<String> r = server.postJson("/api/v1/exec?format=pdf", operator,
                "{\"mbean\":\"java.lang:type=Memory\",\"operation\":\"gc\"}");
        assertEquals(406, r.statusCode());
        assertTrue(contentType(r).startsWith("application/json"));
        assertFalse(server.auditLog().contains("\"member\":\"gc\""), "gc must not have run");

        assertEquals(200, server.postJson("/api/v1/exec?format=yaml", operator,
                "{\"mbean\":\"java.lang:type=Memory\",\"operation\":\"gc\"}").statusCode());
    }

    @Test
    void openApiAsYaml() throws Exception {
        HttpResponse<String> r = server.get("/api/v1/openapi.json?format=yaml", reader);
        assertTrue(r.body().startsWith("openapi: 3."), r.body().substring(0, Math.min(80, r.body().length())));
    }
}

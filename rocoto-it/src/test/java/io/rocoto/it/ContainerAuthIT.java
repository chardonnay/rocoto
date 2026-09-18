package io.rocoto.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** auth.mode=chain: Rocoto credentials first, otherwise the application server's realm. */
class ContainerAuthIT {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer("""
                auth:
                  mode: chain
                  allowInsecureTransport: true
                """, true);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    @Test
    void containerUsersAreAuthenticatedByTheRealm() throws Exception {
        HttpResponse<String> r = server.get("/api/v1/server", TestServer.basic("carl", "container-secret"));
        assertEquals(200, r.statusCode(), r.body());
        Map<?, ?> caller = (Map<?, ?>) TestServer.json(r).get("caller");
        assertEquals("carl", caller.get("name"));
        assertEquals("container", caller.get("authenticatedBy"));
        assertEquals(List.of("rocoto-admin"), caller.get("roles"));
    }

    @Test
    void builtinCredentialsStillWork() throws Exception {
        Map<?, ?> caller = (Map<?, ?>) TestServer.json(
                server.get("/api/v1/server", TestServer.basic(TestServer.READER))).get("caller");
        assertEquals("basic", caller.get("authenticatedBy"));
        caller = (Map<?, ?>) TestServer.json(
                server.get("/api/v1/server", "Bearer " + server.tokenReader)).get("caller");
        assertEquals("token", caller.get("authenticatedBy"));
    }

    @Test
    void anonymousAndWrongCredentialsGetTheContainerChallenge() throws Exception {
        HttpResponse<String> anonymous = server.get("/api/v1/server", null);
        assertEquals(401, anonymous.statusCode());
        assertTrue(anonymous.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"));
        assertEquals(401, server.get("/api/v1/server", TestServer.basic("carl", "wrong")).statusCode());
    }

    @Test
    void containerUsersWithoutRocotoRolesSeeNothing() throws Exception {
        String rudi = TestServer.basic("rudi", "container-secret");
        assertEquals(403, server.get("/api/v1/mbeans/java.lang%3Atype%3DMemory/attributes/Verbose", rudi)
                .statusCode());
        Map<String, Object> search = TestServer.json(server.get("/api/v1/mbeans", rudi));
        assertTrue(((List<?>) search.get("mbeans")).isEmpty());
    }
}

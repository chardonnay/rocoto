package io.rocoto.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

/** What happens with no configuration at all, and with a broken one. */
class SecureDefaultsIT {

    @Test
    void defaultsRefuseCredentialsOverPlainHttp() throws Exception {
        try (TestServer server = new TestServer("# empty: all defaults\n", false)) {
            assertEquals(401, server.get("/api/v1/server", null).statusCode());
            HttpResponse<String> r = server.get("/api/v1/server", TestServer.basic(TestServer.ADMIN));
            assertEquals(403, r.statusCode());
            assertTrue(r.body().contains("TLS"), r.body());
        }
    }

    @Test
    void invalidConfigurationFailsClosed() throws Exception {
        try (TestServer server = new TestServer("auth:\n  mode: wide-open\n", false)) {
            assertEquals(503, server.get("/api/v1/server", TestServer.basic(TestServer.ADMIN)).statusCode());
            assertEquals(503, server.get("/metrics", null).statusCode());
        }
    }
}

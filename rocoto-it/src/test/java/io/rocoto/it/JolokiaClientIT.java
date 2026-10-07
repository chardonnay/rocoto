package io.rocoto.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.management.ObjectName;

import org.jolokia.client.JolokiaClient;
import org.jolokia.client.JolokiaClientBuilder;
import org.jolokia.client.exception.JolokiaRemoteException;
import org.jolokia.client.request.JolokiaExecRequest;
import org.jolokia.client.request.JolokiaListRequest;
import org.jolokia.client.request.JolokiaReadRequest;
import org.jolokia.client.response.JolokiaReadResponse;
import org.jolokia.client.request.JolokiaSearchRequest;
import org.jolokia.client.request.JolokiaVersionRequest;
import org.jolokia.client.response.JolokiaVersionResponse;
import org.jolokia.client.request.JolokiaWriteRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Protocol compatibility, proven with the unmodified Jolokia Java client. */
class JolokiaClientIT {

    private static TestServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new TestServer("""
                auth:
                  mode: builtin
                  allowInsecureTransport: true   # the test server speaks plain HTTP
                policy:
                  rules:
                    - roles: [rocoto-admin]
                      allow: [exec]
                      mbean: "java.lang:type=Memory"
                      operations: [gc]
                    - roles: [rocoto-admin]
                      allow: [write]
                      mbean: "java.lang:type=Memory"
                      attributes: [Verbose]
                """, false);
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
    }

    private static JolokiaClient client(String user) {
        return new JolokiaClientBuilder().url(server.uri("/jolokia").toString()).user(user).password(TestServer.PASSWORD).build();
    }

    @Test
    void versionReadSearchAndList() throws Exception {
        JolokiaClient reader = client(TestServer.READER);

        JolokiaVersionResponse version = reader.execute(new JolokiaVersionRequest());
        assertEquals("7.2", version.getProtocolVersion());
        assertTrue(version.getAgentVersion().startsWith("rocoto-"));

        JolokiaReadResponse heap = reader.execute(new JolokiaReadRequest("java.lang:type=Memory", "HeapMemoryUsage"));
        Map<String, Object> usage = heap.getValue();
        assertTrue(((Number) usage.get("used")).longValue() > 0);

        JolokiaReadRequest inner = new JolokiaReadRequest("java.lang:type=Memory", "HeapMemoryUsage");
        inner.setPath("used");
        assertTrue(((Number) reader.execute(inner).getValue()).longValue() > 0);

        JolokiaReadResponse pools = reader.execute(new JolokiaReadRequest("java.lang:type=MemoryPool,*", "Name"));
        assertTrue(pools.getObjectNames().size() > 1);

        org.jolokia.client.response.JolokiaSearchResponse search = reader.execute(new JolokiaSearchRequest("java.lang:type=Mem*"));
        List<Object> names = search.getMBeanNames();
        assertTrue(names.contains("java.lang:type=Memory"));

        org.jolokia.client.response.JolokiaListResponse listResponse =
                reader.execute(new JolokiaListRequest("java.lang/type=Memory"));
        // Rocoto answers relative to the path (checked over raw HTTP in BuiltinAuthIT); client 2.4+
        // places the answer under domain and key properties again, so it must arrive complete.
        Map<?, ?> tree = listResponse.getValue();
        assertNotNull(((Map<?, ?>) ((Map<?, ?>) tree.get("java.lang")).get("type=Memory")).get("attr"));
        assertNotNull(listResponse.getMBeanInfo(new ObjectName("java.lang:type=Memory")));
    }

    @Test
    void bulkRequests() throws Exception {
        List<JolokiaReadResponse> responses = client(TestServer.READER).execute(
                new JolokiaReadRequest("java.lang:type=Threading", "ThreadCount"),
                new JolokiaReadRequest("java.lang:type=Runtime", "VmName"));
        assertEquals(2, responses.size());
        assertNotNull(responses.get(1).getValue());
    }

    @Test
    void writeAndExecFollowThePolicy() throws Exception {
        ObjectName memory = new ObjectName("java.lang:type=Memory");
        JolokiaRemoteException denied = assertThrows(JolokiaRemoteException.class,
                () -> client(TestServer.READER).execute(new JolokiaExecRequest(memory, "gc")));
        assertEquals(403, denied.getStatus());

        JolokiaClient admin = client(TestServer.ADMIN);
        admin.execute(new JolokiaExecRequest(memory, "gc"));
        Object previous = admin.execute(new JolokiaWriteRequest(memory, "Verbose", true)).getValue();
        assertEquals(Boolean.FALSE, previous);
        admin.execute(new JolokiaWriteRequest(memory, "Verbose", false));
    }

    @Test
    void errorsArriveAsJolokiaExceptions() {
        JolokiaRemoteException missing = assertThrows(JolokiaRemoteException.class,
                () -> client(TestServer.READER).execute(new JolokiaReadRequest("java.lang:type=Nope", "X")));
        assertEquals(404, missing.getStatus());
        assertEquals("javax.management.InstanceNotFoundException", missing.getErrorType());
    }
}

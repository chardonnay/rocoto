package io.rocoto.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.management.ObjectName;

import org.jolokia.client.J4pClient;
import org.jolokia.client.exception.J4pRemoteException;
import org.jolokia.client.request.J4pExecRequest;
import org.jolokia.client.request.J4pListRequest;
import org.jolokia.client.request.J4pReadRequest;
import org.jolokia.client.request.J4pReadResponse;
import org.jolokia.client.request.J4pSearchRequest;
import org.jolokia.client.request.J4pVersionRequest;
import org.jolokia.client.request.J4pVersionResponse;
import org.jolokia.client.request.J4pWriteRequest;
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

    private static J4pClient client(String user) {
        return J4pClient.url(server.uri("/jolokia").toString()).user(user).password(TestServer.PASSWORD).build();
    }

    @Test
    void versionReadSearchAndList() throws Exception {
        J4pClient reader = client(TestServer.READER);

        J4pVersionResponse version = reader.execute(new J4pVersionRequest());
        assertEquals("7.2", version.getProtocolVersion());
        assertTrue(version.getAgentVersion().startsWith("rocoto-"));

        J4pReadResponse heap = reader.execute(new J4pReadRequest("java.lang:type=Memory", "HeapMemoryUsage"));
        Map<String, Object> usage = heap.getValue();
        assertTrue(((Number) usage.get("used")).longValue() > 0);

        J4pReadRequest inner = new J4pReadRequest("java.lang:type=Memory", "HeapMemoryUsage");
        inner.setPath("used");
        assertTrue(((Number) reader.execute(inner).getValue()).longValue() > 0);

        J4pReadResponse pools = reader.execute(new J4pReadRequest("java.lang:type=MemoryPool,*", "Name"));
        assertTrue(pools.getObjectNames().size() > 1);

        org.jolokia.client.request.J4pSearchResponse search = reader.execute(new J4pSearchRequest("java.lang:type=Mem*"));
        List<String> names = search.getMBeanNames();
        assertTrue(names.contains("java.lang:type=Memory"));

        Map<String, Object> list = reader.execute(new J4pListRequest("java.lang/type=Memory")).getValue();
        assertNotNull(list.get("attr"));
    }

    @Test
    void bulkRequests() throws Exception {
        List<J4pReadResponse> responses = client(TestServer.READER).execute(
                new J4pReadRequest("java.lang:type=Threading", "ThreadCount"),
                new J4pReadRequest("java.lang:type=Runtime", "VmName"));
        assertEquals(2, responses.size());
        assertNotNull(responses.get(1).getValue());
    }

    @Test
    void writeAndExecFollowThePolicy() throws Exception {
        ObjectName memory = new ObjectName("java.lang:type=Memory");
        J4pRemoteException denied = assertThrows(J4pRemoteException.class,
                () -> client(TestServer.READER).execute(new J4pExecRequest(memory, "gc")));
        assertEquals(403, denied.getStatus());

        J4pClient admin = client(TestServer.ADMIN);
        admin.execute(new J4pExecRequest(memory, "gc"));
        Object previous = admin.execute(new J4pWriteRequest(memory, "Verbose", true)).getValue();
        assertEquals(Boolean.FALSE, previous);
        admin.execute(new J4pWriteRequest(memory, "Verbose", false));
    }

    @Test
    void errorsArriveAsJolokiaExceptions() {
        J4pRemoteException missing = assertThrows(J4pRemoteException.class,
                () -> client(TestServer.READER).execute(new J4pReadRequest("java.lang:type=Nope", "X")));
        assertEquals(404, missing.getStatus());
        assertEquals("javax.management.InstanceNotFoundException", missing.getErrorType());
    }
}

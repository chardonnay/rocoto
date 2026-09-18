package io.rocoto.compat.jolokia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class JolokiaPathTest {

    @Test
    void splitsAndUnescapes() {
        assertEquals(List.of("read", "java.lang:type=Memory", "HeapMemoryUsage", "used"),
                JolokiaPath.split("/read/java.lang:type=Memory/HeapMemoryUsage/used"));
        assertEquals(List.of("read", "Catalina:type=Manager,context=/app,host=localhost", "activeSessions"),
                JolokiaPath.split("/read/Catalina:type=Manager,context=!/app,host=localhost/activeSessions"));
        assertEquals(List.of("exec", "a:b=c", "op", "wow!"), JolokiaPath.split("/exec/a:b=c/op/wow!!"));
        assertEquals(List.of(), JolokiaPath.split("/"));
    }
}

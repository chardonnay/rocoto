package io.rocoto.adapters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import org.junit.jupiter.api.Test;

class AdapterTest {

    private static ResourceReader readerFor(MBeanServer server, Set<String> deniedAttributes) {
        return new ResourceReader() {
            @Override
            public Set<ObjectName> search(String pattern) {
                try {
                    return server.queryNames(new ObjectName(pattern), null);
                } catch (Exception e) {
                    return Set.of();
                }
            }

            @Override
            public Object read(ObjectName name, String attribute) {
                if (deniedAttributes.contains(attribute)) {
                    return null;
                }
                try {
                    return server.getAttribute(name, attribute);
                } catch (Exception e) {
                    return null;
                }
            }
        };
    }

    @Test
    void tomcatIsDetectedByItsEngineAndNormalized() throws Exception {
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        server.registerMBean(new FakePool(), new ObjectName("Tomcat:type=Engine"));
        server.registerMBean(new FakePool(), new ObjectName("Tomcat:type=ThreadPool,name=\"http-nio-8080\""));
        server.registerMBean(new FakePool(),
                new ObjectName("Tomcat:type=ThreadPool,name=\"http-nio-8080\",subType=SocketProperties"));

        ServerAdapter adapter = ServerAdapters.select(server, null, getClass().getClassLoader());
        assertEquals("tomcat", adapter.id());

        List<Map<String, Object>> pools = adapter.resources(ResourceKind.THREADPOOLS, readerFor(server, Set.of()));
        assertEquals(1, pools.size());
        assertEquals("http-nio-8080", pools.get(0).get("name"));
        assertEquals(3, pools.get(0).get("busyThreads"));
        assertEquals(200, pools.get(0).get("maxThreads"));
    }

    @Test
    void deniedAttributesAreSimplyAbsent() throws Exception {
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        server.registerMBean(new FakePool(), new ObjectName("Catalina:type=Engine"));
        server.registerMBean(new FakePool(), new ObjectName("Catalina:type=ThreadPool,name=\"ajp\""));
        TomcatAdapter adapter = new TomcatAdapter();
        assertTrue(adapter.detect(server));
        Map<String, Object> row = adapter.resources(ResourceKind.THREADPOOLS,
                readerFor(server, Set.of("maxThreads"))).get(0);
        assertFalse(row.containsKey("maxThreads"));
        assertTrue(row.containsKey("busyThreads"));
    }

    @Test
    void unknownServersFallBackToGeneric() {
        MBeanServer server = MBeanServerFactory.newMBeanServer();
        String previous = System.clearProperty("jboss.home.dir");
        try {
            assertEquals("generic", ServerAdapters.select(server, null, getClass().getClassLoader()).id());
            assertEquals("wildfly", ServerAdapters.select(server, "wildfly", getClass().getClassLoader()).id());
        } finally {
            if (previous != null) {
                System.setProperty("jboss.home.dir", previous);
            }
        }
    }
}

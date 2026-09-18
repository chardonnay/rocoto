package io.rocoto.adapters;

import static io.rocoto.adapters.Rows.put;
import static io.rocoto.adapters.Rows.row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/**
 * Payara / GlassFish. Runtime data is published as AMX MBeans ({@code amx:*}) once AMX has been
 * started and the monitoring level of the respective module is at least {@code LOW}:
 * <pre>asadmin set-monitoring-level --module=jdbc-connection-pool,thread-pool,web-container --level=LOW</pre>
 * Without that the normalized view is empty, while plain MBean access keeps working.
 */
public class PayaraAdapter extends GenericAdapter {

    @Override
    public String id() {
        return "payara";
    }

    @Override
    public int priority() {
        return 150;
    }

    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            return System.getProperty("com.sun.aas.instanceRoot") != null
                    || !platformServer.queryNames(new ObjectName("amx-support:*"), null).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "Payara / GlassFish");
        put(info, "instanceRoot", System.getProperty("com.sun.aas.instanceRoot"));
        info.put("amxLoaded", !reader.search("amx:pp=,type=domain-root").isEmpty());
        return info;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case DATASOURCES -> {
                for (ObjectName n : reader.search("amx:type=jdbc-connection-pool-mon,*")) {
                    Map<String, Object> r = row(n.getKeyProperty("name"), n);
                    put(r, "activeConnections", stat(reader, n, "numconnused"));
                    put(r, "idleConnections", stat(reader, n, "numconnfree"));
                    put(r, "waitingThreads", stat(reader, n, "waitqueuelength"));
                    rows.add(r);
                }
            }
            case THREADPOOLS -> {
                for (ObjectName n : reader.search("amx:type=thread-pool-mon,*")) {
                    Map<String, Object> r = row(n.getKeyProperty("name"), n);
                    put(r, "currentThreads", stat(reader, n, "currentthreadcount"));
                    put(r, "busyThreads", stat(reader, n, "currentthreadsbusy"));
                    put(r, "maxThreads", stat(reader, n, "maxthreads"));
                    rows.add(r);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search("amx:type=application,*")) {
                    Map<String, Object> r = row(n.getKeyProperty("name"), n);
                    put(r, "contextPath", reader.read(n, "ContextRoot"));
                    put(r, "enabled", reader.read(n, "Enabled"));
                    rows.add(r);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search("amx:type=session-mon,*")) {
                    Map<String, Object> r = row(n.getKeyProperty("pp"), n);
                    put(r, "activeSessions", stat(reader, n, "activesessionscurrent"));
                    put(r, "expiredSessions", stat(reader, n, "expiredsessionstotal"));
                    put(r, "rejectedSessions", stat(reader, n, "rejectedsessionstotal"));
                    put(r, "createdSessions", stat(reader, n, "sessionstotal"));
                    rows.add(r);
                }
            }
            default -> {
            }
        }
        return rows;
    }

    /** AMX statistics are composite values; the number of interest is in {@code count} or {@code current}. */
    private static Object stat(ResourceReader reader, ObjectName name, String attribute) {
        Object value = reader.read(name, attribute);
        if (value instanceof CompositeData cd) {
            for (String key : List.of("count", "current")) {
                if (cd.containsKey(key)) {
                    return cd.get(key);
                }
            }
            return null;
        }
        return value;
    }
}

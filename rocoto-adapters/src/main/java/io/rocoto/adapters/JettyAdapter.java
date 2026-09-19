package io.rocoto.adapters;

import static io.rocoto.adapters.Rows.put;
import static io.rocoto.adapters.Rows.row;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Eclipse Jetty 12 (EE10 environment). Jetty only registers MBeans when its {@code jmx} module is
 * enabled ({@code java -jar $JETTY_HOME/start.jar --add-modules=jmx}); without it the normalized
 * view is empty, while JVM MBeans and all generic operations keep working. Request statistics
 * additionally need the {@code statistics} module.
 */
public class JettyAdapter extends GenericAdapter {

    @Override
    public String id() {
        return "jetty";
    }

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            return System.getProperty("jetty.base") != null || System.getProperty("jetty.home") != null
                    || !platformServer.queryNames(new ObjectName("org.eclipse.jetty.server:type=server,*"), null)
                            .isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "Eclipse Jetty");
        boolean jmx = false;
        for (ObjectName server : reader.search("org.eclipse.jetty.server:type=server,*")) {
            jmx = true;
            put(info, "version", reader.read(server, "version"));
        }
        put(info, "jettyBase", System.getProperty("jetty.base"));
        info.put("jmxModule", jmx);
        return info;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case THREADPOOLS -> {
                for (ObjectName n : reader.search("org.eclipse.jetty.util.thread:type=queuedthreadpool,*")) {
                    Map<String, Object> r = row(String.valueOf(reader.read(n, "name")), n);
                    put(r, "currentThreads", reader.read(n, "threads"));
                    put(r, "busyThreads", reader.read(n, "busyThreads"));
                    put(r, "maxThreads", reader.read(n, "maxThreads"));
                    put(r, "queueSize", reader.read(n, "queueSize"));
                    rows.add(r);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search("org.eclipse.jetty.ee*.webapp:type=webappcontext,*")) {
                    Object contextPath = reader.read(n, "contextPath");
                    Map<String, Object> r = row(name(contextPath), n);
                    put(r, "contextPath", contextPath);
                    put(r, "state", reader.read(n, "state"));
                    put(r, "enabled", "STARTED".equals(reader.read(n, "state")));
                    rows.add(r);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search("org.eclipse.jetty.ee*.servlet:type=sessionhandler,*")) {
                    String context = n.getKeyProperty("context");
                    Object sessionPath = reader.read(n, "sessionPath");
                    Map<String, Object> r = row(name(sessionPath), n);
                    put(r, "contextPath", sessionPath);
                    put(r, "createdSessions", reader.read(n, "sessionsCreated"));
                    for (ObjectName cache : reader.search(
                            "org.eclipse.jetty.session:type=defaultsessioncache,context=" + context + ",*")) {
                        put(r, "activeSessions", reader.read(cache, "sessionsCurrent"));
                        put(r, "maxActiveSessions", reader.read(cache, "sessionsMax"));
                    }
                    rows.add(r);
                }
            }
            default -> {
            }
        }
        return rows;
    }

    /**
     * The context key of the MBeans differs between Jetty versions ("rocoto" in 12.0,
     * "oejs_Server@<hash>/_rocoto" in 12.1), so rows are named after the context path.
     */
    private static String name(Object contextPath) {
        String path = contextPath == null ? "" : contextPath.toString();
        return path.isEmpty() || path.equals("/") ? "ROOT" : path.substring(1);
    }
}

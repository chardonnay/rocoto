package io.rocoto.adapters;

import static io.rocoto.adapters.Rows.put;
import static io.rocoto.adapters.Rows.row;
import static io.rocoto.adapters.Rows.unquote;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Open Liberty / WebSphere Liberty. Runtime statistics are published in the domain
 * {@code WebSphere} by the {@code monitor-1.0} feature; without it only the application MBeans
 * exist and the rest of the normalized view is empty, while JVM MBeans and all generic operations
 * keep working.
 */
public class LibertyAdapter extends GenericAdapter {

    @Override
    public String id() {
        return "liberty";
    }

    @Override
    public int priority() {
        return 140;
    }

    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            return System.getProperty("wlp.install.dir") != null
                    || !platformServer.queryNames(new ObjectName("WebSphere:feature=kernel,*"), null).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new LinkedHashMap<>();
        Properties product = productProperties();
        String name = product.getProperty("com.ibm.websphere.productName", "Open Liberty");
        // WebSphere Liberty calls itself "WebSphere Application Server", like the traditional product
        info.put("product", name.contains("Liberty") ? name : "WebSphere Liberty");
        put(info, "version", product.getProperty("com.ibm.websphere.productVersion"));
        put(info, "serverConfigDir", System.getProperty("server.config.dir"));
        info.put("monitorFeature", !reader.search("WebSphere:type=ThreadPoolStats,*").isEmpty());
        return info;
    }

    /** {@code ${wlp.install.dir}/lib/versions/*.properties} names the product and its version. */
    private static Properties productProperties() {
        Properties properties = new Properties();
        String install = System.getProperty("wlp.install.dir");
        if (install == null) {
            return properties;
        }
        for (String file : List.of("openliberty.properties", "WebSphereApplicationServer.properties")) {
            Path path = Path.of(install, "lib", "versions", file);
            if (Files.isReadable(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    properties.load(in);      // WebSphere Liberty overrides the Open Liberty entries
                } catch (Exception e) {
                    // the version is informational only
                }
            }
        }
        return properties;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case THREADPOOLS -> {
                for (ObjectName n : reader.search("WebSphere:type=ThreadPoolStats,*")) {
                    Map<String, Object> r = row(unquote(n.getKeyProperty("name")), n);
                    put(r, "currentThreads", reader.read(n, "PoolSize"));
                    put(r, "busyThreads", reader.read(n, "ActiveThreads"));
                    rows.add(r);
                }
            }
            case DATASOURCES -> {
                for (ObjectName n : reader.search("WebSphere:type=ConnectionPoolStats,*")) {
                    Map<String, Object> r = row(unquote(n.getKeyProperty("name")), n);
                    Object managed = reader.read(n, "ManagedConnectionCount");
                    Object free = reader.read(n, "FreeConnectionCount");
                    if (managed instanceof Number m && free instanceof Number f) {
                        put(r, "activeConnections", m.longValue() - f.longValue());
                    }
                    put(r, "idleConnections", free);
                    put(r, "waitingThreads", reader.read(n, "WaitingThreadCount"));
                    rows.add(r);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search(
                        "WebSphere:service=com.ibm.websphere.application.ApplicationMBean,*")) {
                    Map<String, Object> r = row(unquote(n.getKeyProperty("name")), n);
                    Object state = reader.read(n, "State");
                    put(r, "state", state);
                    put(r, "enabled", "STARTED".equals(state));
                    rows.add(r);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search("WebSphere:type=SessionStats,*")) {
                    // name = <virtual host>/<context root>
                    String name = unquote(n.getKeyProperty("name"));
                    Map<String, Object> r = row(name, n);
                    int slash = name.indexOf('/');
                    put(r, "contextPath", slash < 0 ? null : name.substring(slash));
                    put(r, "activeSessions", reader.read(n, "LiveCount"));
                    put(r, "createdSessions", reader.read(n, "CreateCount"));
                    put(r, "expiredSessions", reader.read(n, "InvalidatedCountbyTimeout"));
                    rows.add(r);
                }
            }
            default -> {
            }
        }
        return rows;
    }
}

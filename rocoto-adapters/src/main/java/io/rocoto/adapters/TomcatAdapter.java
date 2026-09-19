package io.rocoto.adapters;

import static io.rocoto.adapters.Rows.put;
import static io.rocoto.adapters.Rows.row;
import static io.rocoto.adapters.Rows.unquote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * Apache Tomcat and everything embedding it, including Apache TomEE. All MBeans live in the platform MBeanServer under
 * the engine's domain – {@code Catalina} for a standalone server, {@code Tomcat} when embedded.
 */
public class TomcatAdapter extends GenericAdapter {

    private volatile String domain = "Catalina";

    @Override
    public String id() {
        return "tomcat";
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            Set<ObjectName> engines = platformServer.queryNames(new ObjectName("*:type=Engine"), null);
            // WildFly and Payara do not register a Catalina engine, so this is unambiguous.
            if (engines.isEmpty()) {
                return false;
            }
            domain = engines.iterator().next().getDomain();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("product", "Apache Tomcat");
        for (ObjectName server : reader.search(domain + ":type=Server")) {
            Object serverInfo = reader.read(server, "serverInfo");
            put(info, "version", serverInfo);
            // TomEE reports "Apache Tomcat (TomEE)/<tomcat version> (<tomee version>)"
            if (String.valueOf(serverInfo).contains("TomEE")) {
                info.put("product", "Apache TomEE");
            }
        }
        info.put("jmxDomain", domain);
        return info;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case THREADPOOLS -> {
                for (ObjectName n : reader.search(domain + ":type=ThreadPool,*")) {
                    if (n.getKeyProperty("subType") != null) {
                        continue;
                    }
                    Map<String, Object> r = row(unquote(n.getKeyProperty("name")), n);
                    put(r, "currentThreads", reader.read(n, "currentThreadCount"));
                    put(r, "busyThreads", reader.read(n, "currentThreadsBusy"));
                    put(r, "maxThreads", reader.read(n, "maxThreads"));
                    put(r, "connections", reader.read(n, "connectionCount"));
                    put(r, "maxConnections", reader.read(n, "maxConnections"));
                    rows.add(r);
                }
            }
            case DATASOURCES -> {
                for (ObjectName n : reader.search(domain + ":type=DataSource,*")) {
                    if (n.getKeyProperty("connectionpool") != null) {
                        continue;
                    }
                    Map<String, Object> r = row(unquote(n.getKeyProperty("name")), n);
                    put(r, "context", n.getKeyProperty("context"));
                    put(r, "activeConnections", reader.read(n, "numActive"));
                    put(r, "idleConnections", reader.read(n, "numIdle"));
                    put(r, "maxConnections", reader.readFirst(n, "maxTotal", "maxActive"));
                    rows.add(r);
                }
                // Apache TomEE: container managed resources of tomee.xml / resources.xml
                for (ObjectName n : reader.search("openejb.management:ObjectType=datasources,*")) {
                    Map<String, Object> r = row(n.getKeyProperty("DataSource"), n);
                    put(r, "activeConnections", reader.read(n, "NumActive"));
                    put(r, "idleConnections", reader.read(n, "NumIdle"));
                    put(r, "maxConnections", reader.read(n, "MaxActive"));
                    put(r, "waitingThreads", reader.read(n, "WaitCount"));
                    rows.add(r);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search(domain + ":j2eeType=WebModule,*")) {
                    Object path = reader.read(n, "path");
                    Map<String, Object> r = row(String.valueOf(path == null || "".equals(path) ? "/" : path), n);
                    put(r, "contextPath", path);
                    put(r, "state", reader.read(n, "stateName"));
                    put(r, "startTime", reader.read(n, "startTime"));
                    rows.add(r);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search(domain + ":type=Manager,*")) {
                    Map<String, Object> r = row(n.getKeyProperty("context"), n);
                    put(r, "activeSessions", reader.read(n, "activeSessions"));
                    put(r, "maxActiveSessions", reader.read(n, "maxActive"));
                    put(r, "expiredSessions", reader.read(n, "expiredSessions"));
                    put(r, "rejectedSessions", reader.read(n, "rejectedSessions"));
                    put(r, "createdSessions", reader.read(n, "sessionCounter"));
                    rows.add(r);
                }
            }
            default -> {
            }
        }
        return rows;
    }
}

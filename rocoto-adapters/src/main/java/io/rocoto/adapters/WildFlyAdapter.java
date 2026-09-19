package io.rocoto.adapters;

import static io.rocoto.adapters.Rows.put;
import static io.rocoto.adapters.Rows.row;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;

/**
 * WildFly / JBoss EAP. The management model is exposed through the JMX subsystem in the platform
 * MBeanServer under the {@code jboss.as} (resolved values) and {@code jboss.as.expr} (expressions)
 * domains. Attribute names are the camel-cased form of the management model names
 * ({@code active-count} becomes {@code activeCount}).
 *
 * <p>Pool and web statistics are only populated when {@code statistics-enabled=true} is set on
 * the respective subsystem.
 */
public class WildFlyAdapter extends GenericAdapter {

    private static final Logger LOG = Logger.getLogger(WildFlyAdapter.class.getName());

    @Override
    public String id() {
        return "wildfly";
    }

    @Override
    public int priority() {
        return 200;
    }

    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            return platformServer.isRegistered(new ObjectName("jboss.as:management-root=server"))
                    || !platformServer.queryNames(new ObjectName("jboss.as:subsystem=*"), null).isEmpty()
                    || System.getProperty("jboss.home.dir") != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<MBeanServer> mbeanServers(MBeanServer platformServer) {
        // WildFly plugs its model controller MBeans into the platform MBeanServer; nothing else is needed,
        // and scanning for further MBeanServers would only pick up those of other deployments.
        return List.of(platformServer);
    }

    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "WildFly");
        for (ObjectName root : reader.search("jboss.as:management-root=server")) {
            put(info, "product", reader.read(root, "productName"));
            put(info, "version", reader.readFirst(root, "productVersion", "releaseVersion"));
            put(info, "serverState", reader.read(root, "serverState"));
            put(info, "launchType", reader.read(root, "launchType"));
        }
        return info;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case DATASOURCES -> {
                for (String type : List.of("data-source", "xa-data-source")) {
                    for (ObjectName n : reader.search("jboss.as:subsystem=datasources," + type + "=*")) {
                        String name = n.getKeyProperty(type);
                        Map<String, Object> r = row(name, n);
                        put(r, "jndiName", reader.read(n, "jndiName"));
                        put(r, "enabled", reader.read(n, "enabled"));
                        put(r, "maxConnections", reader.read(n, "maxPoolSize"));
                        for (ObjectName pool : reader.search(
                                "jboss.as:subsystem=datasources," + type + "=" + name + ",statistics=pool")) {
                            put(r, "activeConnections", reader.readFirst(pool, "inUseCount", "InUseCount"));
                            put(r, "idleConnections", reader.readFirst(pool, "idleCount", "IdleCount"));
                            put(r, "availableConnections", reader.readFirst(pool, "availableCount", "AvailableCount"));
                            put(r, "waitingThreads", reader.readFirst(pool, "waitCount", "WaitCount"));
                            put(r, "maxUsedConnections", reader.readFirst(pool, "maxUsedCount", "MaxUsedCount"));
                        }
                        rows.add(r);
                    }
                }
            }
            case THREADPOOLS -> {
                for (ObjectName n : reader.search("jboss.as:subsystem=io,worker=*")) {
                    Map<String, Object> r = row(n.getKeyProperty("worker"), n);
                    put(r, "busyThreads", reader.read(n, "busyTaskThreadCount"));
                    put(r, "currentThreads", reader.readFirst(n, "corePoolSize", "coreWorkerPoolSize"));
                    put(r, "maxThreads", reader.readFirst(n, "maxPoolSize", "maxWorkerPoolSize", "taskMaxThreads"));
                    put(r, "queueSize", reader.read(n, "queueSize"));
                    rows.add(r);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search("jboss.as:deployment=*")) {
                    Map<String, Object> r = row(n.getKeyProperty("deployment"), n);
                    put(r, "runtimeName", reader.read(n, "runtimeName"));
                    put(r, "enabled", reader.read(n, "enabled"));
                    put(r, "state", reader.read(n, "status"));
                    rows.add(r);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search("jboss.as:deployment=*,subsystem=undertow")) {
                    Map<String, Object> r = row(n.getKeyProperty("deployment"), n);
                    put(r, "contextPath", reader.read(n, "contextRoot"));
                    put(r, "activeSessions", reader.read(n, "activeSessions"));
                    put(r, "maxActiveSessions", reader.read(n, "maxActiveSessions"));
                    put(r, "expiredSessions", reader.read(n, "expiredSessions"));
                    put(r, "rejectedSessions", reader.read(n, "rejectedSessions"));
                    put(r, "createdSessions", reader.read(n, "sessionsCreated"));
                    rows.add(r);
                }
            }
            default -> {
            }
        }
        return rows;
    }

    /**
     * Captures the Elytron {@code SecurityIdentity} of the request so that streaming threads access
     * {@code jboss.as:*} MBeans as the caller and WildFly's management RBAC keeps applying.
     * Elytron is accessed reflectively: Rocoto has no compile-time dependency on it.
     */
    @Override
    public IdentityPropagator captureIdentity() {
        try {
            Class<?> domainClass = Class.forName("org.wildfly.security.auth.server.SecurityDomain");
            Object domain = domainClass.getMethod("getCurrent").invoke(null);
            if (domain == null) {
                return task -> task;
            }
            Object identity = domainClass.getMethod("getCurrentSecurityIdentity").invoke(domain);
            Method runAs = identity.getClass().getMethod("runAs", Runnable.class);
            return task -> () -> {
                try {
                    runAs.invoke(identity, task);
                } catch (ReflectiveOperationException e) {
                    LOG.log(Level.FINE, "Cannot propagate Elytron identity", e);
                    task.run();
                }
            };
        } catch (ReflectiveOperationException | LinkageError e) {
            // Elytron API not visible to the deployment: calls run with the deployment's identity.
            return task -> task;
        }
    }
}

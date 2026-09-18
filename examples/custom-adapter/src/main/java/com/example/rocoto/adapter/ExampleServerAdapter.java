package com.example.rocoto.adapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.adapters.GenericAdapter;
import io.rocoto.adapters.ResourceKind;
import io.rocoto.adapters.ResourceReader;

/**
 * Adapter for the fictional "Example Server", which announces itself with the MBean
 * {@code com.example.server:type=Runtime} and publishes its resources as
 *
 * <pre>
 * com.example.server:type=Runtime            ProductName, Version
 * com.example.server:type=ThreadPool,name=*  PoolSize, ActiveCount, MaximumPoolSize
 * com.example.server:type=DataSource,name=*  ActiveConnections, IdleConnections, MaxConnections
 * com.example.server:type=Application,name=* ContextPath, State, ActiveSessions
 * </pre>
 *
 * Extending {@link GenericAdapter} inherits {@code mbeanServers()} (platform MBeanServer plus every
 * other MBeanServer of the JVM) and the no-op {@code captureIdentity()}.
 */
public class ExampleServerAdapter extends GenericAdapter {

    private static final String DOMAIN = "com.example.server";

    /** Also the value for {@code server.adapter} in rocoto.yaml to force this adapter. */
    @Override
    public String id() {
        return "example-server";
    }

    /** Asked before the bundled adapters (WildFly 200, Tomcat 100, generic 0). */
    @Override
    public int priority() {
        return 300;
    }

    /** Must be cheap, side-effect free and never throw. Runs once while Rocoto starts. */
    @Override
    public boolean detect(MBeanServer platformServer) {
        try {
            return platformServer.isRegistered(new ObjectName(DOMAIN + ":type=Runtime"));
        } catch (MalformedObjectNameException e) {
            return false;
        }
    }

    /**
     * All reads go through the {@link ResourceReader}: it is backed by Rocoto's request pipeline,
     * so policy, masking and audit apply. A value the caller may not read comes back as null.
     */
    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("product", "Example Server");
        for (ObjectName runtime : reader.search(DOMAIN + ":type=Runtime")) {
            put(info, "product", reader.read(runtime, "ProductName"));
            put(info, "version", reader.read(runtime, "Version"));
        }
        return info;
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (kind) {
            case THREADPOOLS -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=ThreadPool,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "currentThreads", reader.read(n, "PoolSize"));
                    put(row, "busyThreads", reader.read(n, "ActiveCount"));
                    put(row, "maxThreads", reader.read(n, "MaximumPoolSize"));
                    rows.add(row);
                }
            }
            case DATASOURCES -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=DataSource,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "activeConnections", reader.read(n, "ActiveConnections"));
                    put(row, "idleConnections", reader.read(n, "IdleConnections"));
                    // attribute names differ between server versions? Ask for several spellings.
                    put(row, "maxConnections", reader.readFirst(n, "MaxConnections", "MaxPoolSize"));
                    rows.add(row);
                }
            }
            case DEPLOYMENTS -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=Application,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "contextPath", reader.read(n, "ContextPath"));
                    put(row, "state", reader.read(n, "State"));
                    rows.add(row);
                }
            }
            case SESSIONS -> {
                for (ObjectName n : reader.search(DOMAIN + ":type=Application,name=*")) {
                    Map<String, Object> row = row(n);
                    put(row, "activeSessions", reader.read(n, "ActiveSessions"));
                    rows.add(row);
                }
            }
            default -> {
                // a resource kind added by a newer Rocoto version: nothing to report
            }
        }
        return rows;
    }

    /** Same row layout as the bundled adapters: "name" and the source "mbean" first. */
    private static Map<String, Object> row(ObjectName source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", source.getKeyProperty("name"));
        row.put("mbean", source.getCanonicalName());
        return row;
    }

    /** Leaves out what is missing or denied instead of reporting null. */
    private static void put(Map<String, Object> row, String key, Object value) {
        if (value != null) {
            row.put(key, value);
        }
    }
}

package io.rocoto.adapters;

import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;

/** Fallback for unknown servers: platform MBeanServer plus any other MBeanServer in the JVM. */
public class GenericAdapter implements ServerAdapter {

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public boolean detect(MBeanServer platformServer) {
        return true;
    }

    @Override
    public List<MBeanServer> mbeanServers(MBeanServer platformServer) {
        List<MBeanServer> servers = new java.util.ArrayList<>();
        servers.add(platformServer);
        for (MBeanServer s : MBeanServerFactory.findMBeanServer(null)) {
            if (!servers.contains(s)) {
                servers.add(s);
            }
        }
        return servers;
    }

    @Override
    public Map<String, Object> serverInfo(ResourceReader reader) {
        return Map.of("product", "unknown");
    }

    @Override
    public List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader) {
        return List.of();
    }
}

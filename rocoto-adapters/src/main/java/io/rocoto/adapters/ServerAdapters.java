package io.rocoto.adapters;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import javax.management.MBeanServer;

/** Picks the adapter for the server Rocoto is running in. */
public final class ServerAdapters {

    private static final Logger LOG = Logger.getLogger(ServerAdapters.class.getName());

    private ServerAdapters() {
    }

    /**
     * @param forcedId value of {@code server.adapter} in rocoto.yaml, or {@code null}/{@code auto} to detect
     */
    public static ServerAdapter select(MBeanServer platformServer, String forcedId, ClassLoader classLoader) {
        List<ServerAdapter> adapters = new ArrayList<>();
        ServiceLoader.load(ServerAdapter.class, classLoader).forEach(adapters::add);
        adapters.sort(Comparator.comparingInt(ServerAdapter::priority).reversed());
        boolean auto = forcedId == null || forcedId.equalsIgnoreCase("auto");
        for (ServerAdapter adapter : adapters) {
            if (auto ? adapter.detect(platformServer) : adapter.id().equalsIgnoreCase(forcedId)) {
                if (!auto) {
                    adapter.detect(platformServer);
                }
                LOG.info("Rocoto server adapter: " + adapter.id());
                return adapter;
            }
        }
        if (!auto) {
            throw new IllegalArgumentException("Unknown server.adapter '" + forcedId + "'");
        }
        return new GenericAdapter();
    }
}

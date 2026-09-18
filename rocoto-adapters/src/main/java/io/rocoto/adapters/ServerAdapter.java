package io.rocoto.adapters;

import java.util.List;
import java.util.Map;

import javax.management.MBeanServer;

/**
 * Knowledge about one application server family. Implementations are discovered with
 * {@link java.util.ServiceLoader}, so additional servers can be supported by dropping a jar into
 * the WAR (see the custom adapter example).
 */
public interface ServerAdapter {

    /** Short identifier, also the name of the bundled metrics profile: {@code wildfly}, {@code tomcat}, ... */
    String id();

    /** Higher values are asked first; the generic adapter has priority 0. */
    default int priority() {
        return 100;
    }

    boolean detect(MBeanServer platformServer);

    /** MBeanServers to expose. All of them are in-process; Rocoto never connects to a remote one. */
    List<MBeanServer> mbeanServers(MBeanServer platformServer);

    /** Product name and version for {@code GET /api/v1/server}. */
    Map<String, Object> serverInfo(ResourceReader reader);

    /** The normalized view of one resource kind. Rows only contain what the caller may read. */
    List<Map<String, Object>> resources(ResourceKind kind, ResourceReader reader);

    /**
     * Captures the caller's security identity on the request thread and returns a wrapper that
     * re-establishes it on another thread (streaming). Servers without such a concept return the
     * task unchanged.
     */
    default IdentityPropagator captureIdentity() {
        return task -> task;
    }

    @FunctionalInterface
    interface IdentityPropagator {
        Runnable wrap(Runnable task);
    }
}

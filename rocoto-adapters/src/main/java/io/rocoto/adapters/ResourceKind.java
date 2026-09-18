package io.rocoto.adapters;

import java.util.Locale;

/** Resource types every application server has, whatever it calls them. */
public enum ResourceKind {
    DATASOURCES, THREADPOOLS, DEPLOYMENTS, SESSIONS;

    public String path() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ResourceKind fromPath(String path) {
        for (ResourceKind k : values()) {
            if (k.path().equalsIgnoreCase(path)) {
                return k;
            }
        }
        return null;
    }
}

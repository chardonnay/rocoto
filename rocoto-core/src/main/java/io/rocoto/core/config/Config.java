package io.rocoto.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only view on a configuration tree (parsed YAML). Values are addressed with dotted paths,
 * e.g. {@code auth.builtin.lockout.maxAttempts}.
 */
public final class Config {

    private static final Config EMPTY = new Config(Map.of());

    private final Map<String, Object> root;

    public Config(Map<String, Object> root) {
        this.root = root == null ? Map.of() : root;
    }

    public static Config empty() {
        return EMPTY;
    }

    public Object get(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = m.get(part);
        }
        return current;
    }

    public boolean has(String path) {
        return get(path) != null;
    }

    public String string(String path, String defaultValue) {
        Object v = get(path);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public boolean bool(String path, boolean defaultValue) {
        Object v = get(path);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        throw new ConfigException(path + " must be true or false");
    }

    public int integer(String path, int defaultValue) {
        Object v = get(path);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        throw new ConfigException(path + " must be a number");
    }

    public long longValue(String path, long defaultValue) {
        Object v = get(path);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        throw new ConfigException(path + " must be a number");
    }

    /** A scalar is accepted as a one-element list. */
    public List<String> strings(String path) {
        Object v = get(path);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
            return Collections.unmodifiableList(out);
        }
        return List.of(String.valueOf(v));
    }

    public Config sub(String path) {
        Object v = get(path);
        if (v == null) {
            return EMPTY;
        }
        if (v instanceof Map<?, ?> m) {
            return new Config(stringKeys(m));
        }
        throw new ConfigException(path + " must be a mapping");
    }

    public List<Config> subList(String path) {
        Object v = get(path);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new ConfigException(path + " must be a list");
        }
        List<Config> out = new ArrayList<>(l.size());
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ConfigException(path + " must be a list of mappings");
            }
            out.add(new Config(stringKeys(m)));
        }
        return out;
    }

    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(root);
    }

    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}

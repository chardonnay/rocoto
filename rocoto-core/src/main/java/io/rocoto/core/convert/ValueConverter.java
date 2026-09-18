package io.rocoto.core.convert;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

/**
 * Converts values returned by an {@code MBeanServer} into a JSON-ready tree
 * (maps, lists, strings, numbers, booleans, {@code null}).
 *
 * <p>Two number modes exist:
 * <ul>
 *   <li>{@link NumberMode#TYPED} (native API): integers outside the IEEE-754 safe range,
 *       {@link BigInteger} and {@link BigDecimal} are emitted as strings so no client silently
 *       loses precision.</li>
 *   <li>{@link NumberMode#PLAIN} (Jolokia compatibility endpoint): all numbers are JSON numbers.</li>
 * </ul>
 *
 * <p>Unknown object types are rendered with {@code toString()}. Rocoto never walks arbitrary
 * beans by reflection: doing so leaks internals and has been a source of vulnerabilities in
 * comparable tools.
 */
public final class ValueConverter {

    public enum NumberMode { TYPED, PLAIN }

    /** Replacement emitted for masked values. */
    public static final String MASK = "********";

    private static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private final NumberMode numberMode;
    private final int maxDepth;
    private final int maxCollectionSize;
    private final Predicate<String> maskedKey;
    private final boolean tabularAsRows;
    private final boolean objectNameAsMap;

    private ValueConverter(Builder b) {
        this.objectNameAsMap = b.objectNameAsMap;
        this.numberMode = b.numberMode;
        this.maxDepth = b.maxDepth;
        this.maxCollectionSize = b.maxCollectionSize;
        this.maskedKey = b.maskedKey;
        this.tabularAsRows = b.tabularAsRows;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Object convert(Object value) {
        return convert(value, 0);
    }

    private Object convert(Object value, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number n) {
            return convertNumber(n);
        }
        if (value instanceof Character c) {
            return c.toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof ObjectName on) {
            return objectNameAsMap ? Map.of("objectName", on.getCanonicalName()) : on.getCanonicalName();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Date d) {
            return Instant.ofEpochMilli(d.getTime()).toString();
        }
        if (value instanceof Calendar c) {
            return c.toInstant().toString();
        }
        if (value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (depth >= maxDepth) {
            // Depth limit reached: do not descend any further, but keep a hint of what was there.
            return value.toString();
        }
        if (value instanceof CompositeData cd) {
            return convertComposite(cd, depth);
        }
        if (value instanceof TabularData td) {
            return convertTabular(td, depth);
        }
        if (value instanceof Map<?, ?> m) {
            return convertMap(m, depth);
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(Math.min(c.size(), maxCollectionSize));
            for (Object o : c) {
                if (out.size() >= maxCollectionSize) {
                    break;
                }
                out.add(convert(o, depth + 1));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            int len = Math.min(Array.getLength(value), maxCollectionSize);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                out.add(convert(Array.get(value, i), depth + 1));
            }
            return out;
        }
        return value.toString();
    }

    private Object convertNumber(Number n) {
        if (numberMode == NumberMode.PLAIN) {
            return n;
        }
        if (n instanceof BigInteger || n instanceof BigDecimal) {
            return n.toString();
        }
        if (n instanceof Long l && (l > MAX_SAFE_INTEGER || l < -MAX_SAFE_INTEGER)) {
            return l.toString();
        }
        return n;
    }

    private Map<String, Object> convertComposite(CompositeData cd, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : cd.getCompositeType().keySet()) {
            out.put(key, maskedKey.test(key) ? MASK : convert(cd.get(key), depth + 1));
        }
        return out;
    }

    private Object convertTabular(TabularData td, int depth) {
        if (tabularAsRows) {
            List<Object> rows = new ArrayList<>();
            for (Object row : td.values()) {
                if (rows.size() >= maxCollectionSize) {
                    break;
                }
                rows.add(convert(row, depth + 1));
            }
            return rows;
        }
        // Jolokia layout: nested maps keyed by the index values.
        List<String> indexNames = td.getTabularType().getIndexNames();
        Map<String, Object> root = new LinkedHashMap<>();
        if (indexNames.equals(List.of("key"))
                && td.getTabularType().getRowType().keySet().equals(java.util.Set.of("key", "value"))) {
            // An MXBean Map<K,V>: Jolokia renders it as a plain JSON object.
            for (Object rowObj : td.values()) {
                if (root.size() >= maxCollectionSize) {
                    break;
                }
                CompositeData row = (CompositeData) rowObj;
                String key = String.valueOf(convert(row.get("key"), maxDepth));
                root.put(key, maskedKey.test(key) ? MASK : convert(row.get("value"), depth + 1));
            }
            return root;
        }
        int count = 0;
        for (Object rowObj : td.values()) {
            if (count++ >= maxCollectionSize) {
                break;
            }
            CompositeData row = (CompositeData) rowObj;
            Map<String, Object> level = root;
            for (int i = 0; i < indexNames.size() - 1; i++) {
                String k = String.valueOf(convert(row.get(indexNames.get(i)), maxDepth));
                @SuppressWarnings("unchecked")
                Map<String, Object> nextLevel =
                        (Map<String, Object>) level.computeIfAbsent(k, x -> new LinkedHashMap<String, Object>());
                level = nextLevel;
            }
            String last = String.valueOf(convert(row.get(indexNames.get(indexNames.size() - 1)), maxDepth));
            level.put(last, convert(row, depth + 1));
        }
        return root;
    }

    private Map<String, Object> convertMap(Map<?, ?> m, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (out.size() >= maxCollectionSize) {
                break;
            }
            Object rawKey = e.getKey();
            String key = rawKey instanceof ObjectName on ? on.getCanonicalName() : String.valueOf(rawKey);
            out.put(key, maskedKey.test(key) ? MASK : convert(e.getValue(), depth + 1));
        }
        return out;
    }

    public static final class Builder {
        private NumberMode numberMode = NumberMode.TYPED;
        private int maxDepth = 16;
        private int maxCollectionSize = 10_000;
        private Predicate<String> maskedKey = k -> false;
        private boolean tabularAsRows = true;
        private boolean objectNameAsMap;

        private Builder() {
        }

        public Builder numberMode(NumberMode mode) {
            this.numberMode = mode;
            return this;
        }

        public Builder maxDepth(int maxDepth) {
            this.maxDepth = Math.max(1, maxDepth);
            return this;
        }

        public Builder maxCollectionSize(int max) {
            this.maxCollectionSize = Math.max(1, max);
            return this;
        }

        /** Keys of composite data and maps for which the value must be replaced by {@link #MASK}. */
        public Builder maskedKey(Predicate<String> maskedKey) {
            this.maskedKey = maskedKey;
            return this;
        }

        /** {@code true}: tabular data as a list of rows (native API). {@code false}: Jolokia's nested maps. */
        public Builder tabularAsRows(boolean rows) {
            this.tabularAsRows = rows;
            return this;
        }

        /** Jolokia renders ObjectName values as {@code {"objectName": "..."}}. */
        public Builder objectNameAsMap(boolean asMap) {
            this.objectNameAsMap = asMap;
            return this;
        }

        public ValueConverter build() {
            return new ValueConverter(this);
        }
    }
}

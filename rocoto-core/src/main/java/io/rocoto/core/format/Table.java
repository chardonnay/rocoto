package io.rocoto.core.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A response tree seen as a table, for the CSV, text table and HTML formats.
 *
 * <p>Recognised shapes, in this order (all other top-level entries must be scalars and become
 * {@link #meta()}):
 * <ol>
 *   <li>one list-valued entry ({@code items}, {@code results}, {@code mbeans}, ...): one row per
 *       element; maps contribute their keys as columns, scalars a single column;</li>
 *   <li>one map-valued entry whose values are all maps (a pattern read): one row per key, the key in
 *       column {@code name};</li>
 *   <li>one map-valued entry with other values (a multi-attribute read): rows {@code name, value};</li>
 *   <li>anything else: the flattened tree as rows {@code name, value}.</li>
 * </ol>
 * Cells keep the raw tree node; each format renders nested values in its own way.
 *
 * @param caption name of the entry the rows come from, or {@code null}
 */
public record Table(Map<String, Object> meta, String caption, List<String> columns, List<List<Object>> rows) {

    private static final Set<String> PRIMARY_LISTS = Set.of("items", "results", "mbeans");

    public static Table of(Object tree) {
        if (tree instanceof List<?> list) {
            return fromList(Map.of(), null, list);
        }
        if (!(tree instanceof Map<?, ?> map)) {
            return keyValue(Map.of(), null, Map.of("value", tree == null ? "" : tree));
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        String structuredKey = null;
        Object structured = null;
        int structuredCount = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (Values.isScalar(e.getValue())) {
                meta.put(String.valueOf(e.getKey()), e.getValue());
            } else {
                structuredCount++;
                structuredKey = String.valueOf(e.getKey());
                structured = e.getValue();
            }
        }
        if (structuredCount == 1) {
            if (structured instanceof List<?> list && (!list.isEmpty() || PRIMARY_LISTS.contains(structuredKey))) {
                return fromList(meta, structuredKey, list);
            }
            if (structured instanceof Map<?, ?> inner && !inner.isEmpty()) {
                boolean allMaps = inner.values().stream().allMatch(v -> v instanceof Map);
                return allMaps ? fromMapOfMaps(meta, structuredKey, inner) : keyValue(meta, structuredKey, inner);
            }
        }
        if (structuredCount == 0) {
            return keyValue(Map.of(), null, map);
        }
        return keyValue(Map.of(), null, Values.flatten(map));
    }

    public boolean isKeyValue() {
        return columns.equals(List.of("name", "value"));
    }

    private static Table fromList(Map<String, Object> meta, String caption, List<?> list) {
        Set<String> columns = new LinkedHashSet<>();
        boolean scalars = false;
        for (Object element : list) {
            if (element instanceof Map<?, ?> m) {
                m.keySet().forEach(k -> columns.add(String.valueOf(k)));
            } else {
                scalars = true;
            }
        }
        String scalarColumn = caption == null ? "value" : caption;
        if (scalars) {
            columns.add(scalarColumn);
        }
        List<String> cols = new ArrayList<>(columns);
        List<List<Object>> rows = new ArrayList<>(list.size());
        for (Object element : list) {
            List<Object> row = new ArrayList<>(cols.size());
            for (String c : cols) {
                if (element instanceof Map<?, ?> m) {
                    row.add(m.get(c));
                } else {
                    row.add(c.equals(scalarColumn) ? element : null);
                }
            }
            rows.add(row);
        }
        return new Table(meta, caption, cols, rows);
    }

    private static Table fromMapOfMaps(Map<String, Object> meta, String caption, Map<?, ?> map) {
        Set<String> columns = new LinkedHashSet<>();
        columns.add("name");
        map.values().forEach(v -> ((Map<?, ?>) v).keySet().forEach(k -> columns.add(String.valueOf(k))));
        List<String> cols = new ArrayList<>(columns);
        List<List<Object>> rows = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Map<?, ?> inner = (Map<?, ?>) e.getValue();
            List<Object> row = new ArrayList<>(cols.size());
            row.add(String.valueOf(e.getKey()));
            for (String c : cols.subList(1, cols.size())) {
                row.add(inner.get(c));
            }
            rows.add(row);
        }
        return new Table(meta, caption, cols, rows);
    }

    private static Table keyValue(Map<String, Object> meta, String caption, Map<?, ?> map) {
        List<List<Object>> rows = new ArrayList<>(map.size());
        map.forEach((k, v) -> rows.add(java.util.Arrays.asList(String.valueOf(k), v)));
        return new Table(meta, caption, List.of("name", "value"), rows);
    }
}

package io.rocoto.adapters;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.management.ObjectName;

/** Helpers for building normalized resource rows. */
final class Rows {

    private Rows() {
    }

    static Map<String, Object> row(String name, ObjectName source) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("mbean", source.getCanonicalName());
        return row;
    }

    /** Adds the value only if it is present, so denied or missing attributes do not show up as null noise. */
    static void put(Map<String, Object> row, String key, Object value) {
        if (value != null) {
            row.put(key, value);
        }
    }

    static String unquote(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return ObjectName.unquote(value);
        }
        return value;
    }
}

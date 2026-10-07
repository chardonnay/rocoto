package io.rocoto.core.format;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.rocoto.core.json.JsonWriter;

/** Helpers shared by the formats: scalar rendering and flattening of nested trees. */
public final class Values {

    private static final Pattern SIMPLE_KEY = Pattern.compile("[A-Za-z0-9_@-]+");

    private Values() {
    }

    public static boolean isScalar(Object v) {
        return !(v instanceof Map) && !(v instanceof Collection);
    }

    /** One-line text for a value: scalars as is, structures as compact JSON, {@code null} as empty. */
    public static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (isScalar(v)) {
            return String.valueOf(v);
        }
        return JsonWriter.toJson(v);
    }

    /**
     * Flattens a tree to {@code path -> scalar} pairs. Map keys are joined with dots; keys that are
     * not simple identifiers (ObjectNames, keys with dots or blanks) are written as
     * {@code ["..."]}; list elements as {@code [i]}. Empty structures stay as {@code {}} / {@code []}.
     */
    public static Map<String, Object> flatten(Object tree) {
        Map<String, Object> out = new LinkedHashMap<>();
        flatten("", tree, out);
        return out;
    }

    private static void flatten(String prefix, Object v, Map<String, Object> out) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty() && !prefix.isEmpty()) {
                out.put(prefix, Map.of());
                return;
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                flatten(child(prefix, String.valueOf(e.getKey())), e.getValue(), out);
            }
        } else if (v instanceof List<?> l) {
            if (l.isEmpty()) {
                out.put(prefix.isEmpty() ? "[]" : prefix, List.of());
                return;
            }
            for (int i = 0; i < l.size(); i++) {
                flatten(prefix + "[" + i + "]", l.get(i), out);
            }
        } else {
            out.put(prefix.isEmpty() ? "value" : prefix, v);
        }
    }

    private static String child(String prefix, String key) {
        String part = SIMPLE_KEY.matcher(key).matches() ? key : "[" + quote(key) + "]";
        if (prefix.isEmpty() || part.startsWith("[")) {
            return prefix + part;
        }
        return prefix + "." + part;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Replaces line breaks and other control characters so a value stays on one line. */
    public static String oneLine(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c < 0x20 || c == 0x7f ? '�' : c);
            }
        }
        return sb.toString();
    }
}

package io.rocoto.core.format;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * TOML 1.0. Nested maps become {@code [tables]}, lists of maps {@code [[arrays of tables]]}, other
 * lists inline arrays. TOML has no null: {@code null} values are left out, which is the one place
 * where TOML output carries less information than JSON. A tree that is not a map is wrapped as
 * {@code value = ...}.
 */
final class TomlFormat implements DocumentFormat {

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    @Override
    public String id() {
        return "toml";
    }

    @Override
    public String contentType() {
        return "application/toml; charset=utf-8";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        Map<?, ?> root = tree instanceof Map<?, ?> m ? m : singleton(tree);
        try {
            table(out, List.of(), root, false);
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }

    private static Map<?, ?> singleton(Object tree) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", tree);
        return m;
    }

    /** Writes the body of one table: plain keys first, then sub-tables and arrays of tables. */
    private static void table(Appendable out, List<String> path, Map<?, ?> map, boolean arrayElement)
            throws IOException {
        List<Map.Entry<?, ?>> nested = new ArrayList<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof Map || isArrayOfTables(v)) {
                nested.add(e);
            } else {
                out.append(key(String.valueOf(e.getKey()))).append(" = ");
                inline(out, v);
                out.append('\n');
            }
        }
        for (Map.Entry<?, ?> e : nested) {
            List<String> child = new ArrayList<>(path);
            child.add(String.valueOf(e.getKey()));
            if (e.getValue() instanceof Map<?, ?> m) {
                out.append('\n').append('[').append(header(child)).append("]\n");
                table(out, child, m, false);
            } else {
                for (Object element : (List<?>) e.getValue()) {
                    out.append('\n').append("[[").append(header(child)).append("]]\n");
                    table(out, child, (Map<?, ?>) element, true);
                }
            }
        }
    }

    private static boolean isArrayOfTables(Object v) {
        return v instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(e -> e instanceof Map);
    }

    private static void inline(Appendable out, Object v) throws IOException {
        if (v instanceof String s) {
            out.append(string(s));
        } else if (v instanceof Boolean b) {
            out.append(b.toString());
        } else if (v instanceof Double d) {
            out.append(d.isNaN() ? "nan" : d.isInfinite() ? (d > 0 ? "inf" : "-inf") : floatText(d.toString()));
        } else if (v instanceof Float f) {
            out.append(f.isNaN() ? "nan" : f.isInfinite() ? (f > 0 ? "inf" : "-inf") : floatText(f.toString()));
        } else if (v instanceof java.math.BigDecimal bd) {
            out.append(floatText(bd.toString()));
        } else if (v instanceof java.math.BigInteger bi && bi.bitLength() >= 64) {
            out.append(string(bi.toString())); // TOML integers are 64 bit
        } else if (v instanceof Number n) {
            out.append(n.toString());
        } else if (v instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (element == null) {
                    continue;
                }
                if (!first) {
                    out.append(", ");
                }
                first = false;
                inline(out, element);
            }
            out.append(']');
        } else if (v instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() == null) {
                    continue;
                }
                out.append(first ? " " : ", ");
                first = false;
                out.append(key(String.valueOf(e.getKey()))).append(" = ");
                inline(out, e.getValue());
            }
            out.append(first ? "}" : " }");
        } else {
            out.append(string(String.valueOf(v)));
        }
    }

    /** TOML floats need a fractional part or an exponent. */
    private static String floatText(String s) {
        return s.contains(".") || s.contains("e") || s.contains("E") ? s : s + ".0";
    }

    private static String header(List<String> path) {
        List<String> keys = new ArrayList<>(path.size());
        path.forEach(p -> keys.add(key(p)));
        return String.join(".", keys);
    }

    private static String key(String k) {
        return BARE_KEY.matcher(k).matches() ? k : string(k);
    }

    private static String string(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}

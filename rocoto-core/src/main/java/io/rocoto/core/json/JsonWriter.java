package io.rocoto.core.json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Minimal JSON writer for trees made of {@link Map}, {@link Iterable}, {@link String},
 * {@link Number}, {@link Boolean} and {@code null}.
 *
 * <p>Rocoto ships its own writer on purpose: the WAR runs inside foreign application
 * servers and must not clash with whatever JSON library the server or its deployments bring.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(value, sb);
        return sb.toString();
    }

    public static void write(Object value, Appendable out) {
        try {
            writeValue(value, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeValue(Object value, Appendable out) throws IOException {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(s, out);
        } else if (value instanceof Boolean b) {
            out.append(b ? "true" : "false");
        } else if (value instanceof Number n) {
            writeNumber(n, out);
        } else if (value instanceof Map<?, ?> m) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), out);
                out.append(':');
                writeValue(e.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> it) {
            out.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeValue(o, out);
            }
            out.append(']');
        } else {
            // Anything that reaches the writer unconverted is rendered as a string, never reflected upon.
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeNumber(Number n, Appendable out) throws IOException {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())
                || n instanceof Float f && (f.isNaN() || f.isInfinite())) {
            // JSON has no representation for these; keep the information instead of emitting invalid JSON.
            writeString(n.toString(), out);
        } else {
            out.append(n.toString());
        }
    }

    static void writeString(String s, Appendable out) throws IOException {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    // Escape control characters and the characters that enable HTML/script injection
                    // when a response is mistakenly rendered by a browser.
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}

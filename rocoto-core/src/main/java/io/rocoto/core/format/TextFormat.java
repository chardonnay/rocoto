package io.rocoto.core.format;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** {@code path: value}, one line per scalar of the flattened tree. */
final class TextFormat implements DocumentFormat {

    @Override
    public String id() {
        return "text";
    }

    @Override
    public String contentType() {
        return "text/plain; charset=utf-8";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        try {
            for (Map.Entry<String, Object> e : Values.flatten(tree).entrySet()) {
                out.append(Values.oneLine(e.getKey())).append(": ").append(scalar(e.getValue())).append('\n');
            }
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }

    private static String scalar(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Map) {
            return "{}";
        }
        if (v instanceof List) {
            return "[]";
        }
        return Values.oneLine(String.valueOf(v));
    }
}

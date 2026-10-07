package io.rocoto.core.format;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * XML: {@code <response>} root, map entries as elements, list elements as {@code <item>}.
 * Keys that are not usable as element names (ObjectNames, keys with blanks, ...) become
 * {@code <entry key="...">}. {@code null} is {@code nil="true"}, the JSON type of numbers and
 * booleans is kept in a {@code type} attribute. No DOCTYPE, no entities; characters that XML 1.0
 * cannot carry are replaced by U+FFFD.
 */
final class XmlFormat implements DocumentFormat {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    @Override
    public String id() {
        return "xml";
    }

    @Override
    public String contentType() {
        return "application/xml; charset=utf-8";
    }

    @Override
    public void write(Object tree, FormatOptions options, Appendable out) {
        try {
            out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            element(out, "response", null, tree, 0);
        } catch (IOException e) {
            throw FormatException.wrap(e);
        }
    }

    private static void element(Appendable out, String name, String key, Object value, int depth) throws IOException {
        String indent = "  ".repeat(depth);
        out.append(indent).append('<').append(name);
        if (key != null) {
            out.append(" key=\"").append(escape(key)).append('"');
        }
        if (value == null) {
            out.append(" nil=\"true\"/>\n");
            return;
        }
        if (value instanceof Number) {
            out.append(" type=\"number\"");
        } else if (value instanceof Boolean) {
            out.append(" type=\"boolean\"");
        } else if (value instanceof List) {
            out.append(" type=\"array\"");
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                out.append("/>\n");
                return;
            }
            out.append(">\n");
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (isName(k)) {
                    element(out, k, null, e.getValue(), depth + 1);
                } else {
                    element(out, "entry", k, e.getValue(), depth + 1);
                }
            }
            out.append(indent).append("</").append(name).append(">\n");
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.append("/>\n");
                return;
            }
            out.append(">\n");
            for (Object item : list) {
                element(out, "item", null, item, depth + 1);
            }
            out.append(indent).append("</").append(name).append(">\n");
        } else {
            out.append('>').append(escape(String.valueOf(value))).append("</").append(name).append(">\n");
        }
    }

    /** Valid XML name that is not reserved ({@code xml...}) and not one of the structural names. */
    static boolean isName(String key) {
        return NAME.matcher(key).matches() && !key.regionMatches(true, 0, "xml", 0, 3)
                && !key.equals("entry") && !key.equals("item");
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                case '\t', '\n' -> sb.append(c);
                case '\r' -> sb.append("&#13;");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                        sb.append(c).append(s.charAt(++i));
                    } else if (c < 0x20 || Character.isSurrogate(c) || c == 0xFFFE || c == 0xFFFF) {
                        sb.append('�');
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

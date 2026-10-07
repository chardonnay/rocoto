package io.rocoto.metrics.format;

import java.util.Map;

/** Name and value rules shared by the line protocols. */
final class Names {

    private Names() {
    }

    /** A Graphite/StatsD path segment: everything except letters, digits, {@code _} and {@code -} becomes {@code _}. */
    static String segment(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' ? c : '_');
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }

    /** A dotted prefix where every part is a valid segment; empty for no prefix. */
    static String prefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : prefix.split("\\.")) {
            if (!part.isEmpty()) {
                sb.append(segment(part)).append('.');
            }
        }
        return sb.toString();
    }

    /** {@code prefix.name.labelValue1.labelValue2} – label values in label order. */
    static String path(String prefix, String name, Map<String, String> labels) {
        StringBuilder sb = new StringBuilder(prefix(prefix)).append(segment(name));
        labels.values().forEach(v -> sb.append('.').append(segment(v)));
        return sb.toString();
    }

    /**
     * A tag value: only the characters that would break the line are replaced ({@code forbidden}
     * plus whitespace), everything else is kept.
     */
    static String tagValue(String s, String forbidden) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isWhitespace(c) || Character.isISOControl(c) || forbidden.indexOf(c) >= 0 ? '_' : c);
        }
        return sb.toString();
    }

    /**
     * Numbers as the line protocols expect them: whole numbers without exponent or fraction, other
     * values rounded to 12 significant digits (no {@code 0.042874999999999996}).
     */
    static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return new java.math.BigDecimal(v).round(new java.math.MathContext(12)).stripTrailingZeros().toString();
    }
}

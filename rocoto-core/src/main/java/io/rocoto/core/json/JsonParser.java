package io.rocoto.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, strict JSON parser. Objects become {@link LinkedHashMap}, arrays {@link ArrayList},
 * integers {@link Long} (or {@link BigInteger} when they do not fit), decimals {@link BigDecimal}.
 *
 * <p>Input size and nesting depth are bounded because the parser handles untrusted request bodies.
 */
public final class JsonParser {

    public static final int DEFAULT_MAX_DEPTH = 64;

    private final String src;
    private final int maxDepth;
    private int pos;

    private JsonParser(String src, int maxDepth) {
        this.src = src;
        this.maxDepth = maxDepth;
    }

    public static Object parse(String json) {
        return parse(json, DEFAULT_MAX_DEPTH);
    }

    public static Object parse(String json, int maxDepth) {
        if (json == null) {
            throw new JsonException("No JSON input");
        }
        JsonParser p = new JsonParser(json, maxDepth);
        p.skipWhitespace();
        Object value = p.readValue(0);
        p.skipWhitespace();
        if (p.pos != p.src.length()) {
            throw p.error("Unexpected trailing content");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object o = parse(json);
        if (!(o instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) o;
    }

    private Object readValue(int depth) {
        if (depth > maxDepth) {
            throw error("JSON nesting deeper than " + maxDepth);
        }
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || isDigit(c)) {
                    yield readNumber();
                }
                throw error("Unexpected character '" + c + "'");
            }
        };
    }

    private Map<String, Object> readObject(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a string key");
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> readArray(int depth) {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String readString() {
        pos++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= src.length()) {
                throw error("Unterminated string");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw error("Unescaped control character in string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (pos + 4 > src.length()) {
                        throw error("Incomplete unicode escape");
                    }
                    try {
                        sb.append((char) HexFormat.fromHexDigits(src, pos, pos + 4));
                    } catch (IllegalArgumentException ex) {
                        throw error("Invalid unicode escape");
                    }
                    pos += 4;
                }
                default -> throw error("Invalid escape '\\" + e + "'");
            }
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw error("Invalid literal");
        }
        pos += literal.length();
        return value;
    }

    private Number readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        int digitsStart = pos;
        skipDigits();
        if (pos == digitsStart) {
            throw error("Invalid number");
        }
        if (src.charAt(digitsStart) == '0' && pos - digitsStart > 1) {
            throw error("Leading zeros are not allowed");
        }
        boolean decimal = false;
        if (pos < src.length() && src.charAt(pos) == '.') {
            decimal = true;
            pos++;
            int f = pos;
            skipDigits();
            if (pos == f) {
                throw error("Invalid number");
            }
        }
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            decimal = true;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                pos++;
            }
            int e = pos;
            skipDigits();
            if (pos == e) {
                throw error("Invalid number");
            }
        }
        if (pos - start > 512) {
            throw error("Number too long");
        }
        String text = src.substring(start, pos);
        if (decimal) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException e) {
                throw error("Decimal exponent out of range");
            }
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return new BigInteger(text);
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private void skipDigits() {
        while (pos < src.length() && isDigit(src.charAt(pos))) {
            pos++;
        }
    }

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw error("Unexpected end of input");
        }
        return src.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            throw error("Expected '" + c + "'");
        }
    }

    private JsonException error(String message) {
        return new JsonException(message + " at position " + pos);
    }
}

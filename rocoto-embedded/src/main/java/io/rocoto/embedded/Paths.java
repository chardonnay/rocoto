package io.rocoto.embedded;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class Paths {

    private Paths() {
    }

    /** Percent-decodes as UTF-8; {@code plusIsSpace} for query strings. Malformed input is kept as is. */
    static String decode(String value, boolean plusIsSpace) {
        if (value.indexOf('%') < 0 && (!plusIsSpace || value.indexOf('+') < 0)) {
            return value;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length() && hex(value.charAt(i + 1)) >= 0 && hex(value.charAt(i + 2)) >= 0) {
                out.write(hex(value.charAt(i + 1)) * 16 + hex(value.charAt(i + 2)));
                i += 2;
            } else if (c == '+' && plusIsSpace) {
                out.write(' ');
            } else {
                int end = Character.isHighSurrogate(c) && i + 1 < value.length() ? i + 2 : i + 1;
                byte[] bytes = value.substring(i, end).getBytes(StandardCharsets.UTF_8);
                i = end - 1;
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }
}

package io.rocoto.compat.jolokia;

import java.util.ArrayList;
import java.util.List;

/** Jolokia's path syntax: segments separated by {@code /}, with {@code !} as escape character. */
final class JolokiaPath {

    private JolokiaPath() {
    }

    static List<String> split(String path) {
        List<String> parts = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return parts;
        }
        StringBuilder current = new StringBuilder();
        boolean any = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '!' && i + 1 < path.length()) {
                current.append(path.charAt(++i));
                any = true;
            } else if (c == '/') {
                if (any || current.length() > 0) {
                    parts.add(current.toString());
                }
                current.setLength(0);
                any = false;
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any || current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }
}

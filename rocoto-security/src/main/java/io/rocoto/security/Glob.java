package io.rocoto.security;

import java.util.regex.Pattern;

/** Case-insensitive glob ({@code *} and {@code ?}) used for attribute, operation and key names. */
public final class Glob {

    private final String source;
    private final Pattern pattern;

    private Glob(String source) {
        this.source = source;
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : source.toCharArray()) {
            if (c == '*' || c == '?') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '*' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        this.pattern = Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    public static Glob of(String glob) {
        return new Glob(glob);
    }

    public boolean matches(String value) {
        return value != null && pattern.matcher(value).matches();
    }

    @Override
    public String toString() {
        return source;
    }
}

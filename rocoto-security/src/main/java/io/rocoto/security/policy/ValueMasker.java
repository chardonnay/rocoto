package io.rocoto.security.policy;

import java.util.ArrayList;
import java.util.List;

import io.rocoto.core.config.Config;
import io.rocoto.security.Glob;

/**
 * Decides which attribute names and nested keys carry secrets. Masked values are replaced on the
 * server and never serialized, logged or exported as metrics.
 */
public final class ValueMasker {

    private static final List<String> DEFAULTS = List.of(
            "*password*", "*passwd*", "*secret*", "*credential*", "*privatekey*", "*private-key*",
            "*apikey*", "*api-key*", "*accesskey*", "*authtoken*", "*clear-text*", "*cleartext*");

    private final List<Glob> patterns;

    private ValueMasker(List<Glob> patterns) {
        this.patterns = List.copyOf(patterns);
    }

    /** @param policy the {@code policy} section; {@code mask} adds to the defaults, it never replaces them */
    public static ValueMasker fromConfig(Config policy) {
        List<Glob> globs = new ArrayList<>();
        DEFAULTS.forEach(d -> globs.add(Glob.of(d)));
        policy.strings("mask").forEach(m -> globs.add(Glob.of(m)));
        return new ValueMasker(globs);
    }

    public boolean isMasked(String name) {
        for (Glob g : patterns) {
            if (g.matches(name)) {
                return true;
            }
        }
        return false;
    }
}

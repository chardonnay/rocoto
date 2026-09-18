package io.rocoto.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void readsNestedValuesWithDefaults() {
        Config c = ConfigLoader.parse("""
                auth:
                  mode: chain
                  builtin:
                    lockout: { maxAttempts: 3 }
                roles: [a, b]
                single: x
                rules:
                  - mbean: "java.lang:*"
                """);
        assertEquals("chain", c.string("auth.mode", "builtin"));
        assertEquals(3, c.integer("auth.builtin.lockout.maxAttempts", 5));
        assertEquals(5, c.integer("auth.builtin.lockout.missing", 5));
        assertEquals(List.of("a", "b"), c.strings("roles"));
        assertEquals(List.of("x"), c.strings("single"));
        assertEquals("java.lang:*", c.subList("rules").get(0).string("mbean", null));
        assertTrue(c.sub("nothing").asMap().isEmpty());
    }

    @Test
    void yamlCannotInstantiateJavaTypes() {
        assertThrows(ConfigException.class,
                () -> ConfigLoader.parse("x: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[]]]"));
    }

    @Test
    void wrongTypesAreReported() {
        Config c = ConfigLoader.parse("flag: maybe\nnum: abc");
        assertThrows(ConfigException.class, () -> c.bool("flag", false));
        assertThrows(ConfigException.class, () -> c.integer("num", 1));
    }
}

package io.rocoto.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void roundTrip() {
        String json = "{\"a\":1,\"b\":[true,false,null,\"x\\ny\"],\"c\":{\"d\":-1.5e3}}";
        Map<String, Object> parsed = JsonParser.parseObject(json);
        assertEquals(1L, parsed.get("a"));
        List<?> b = (List<?>) parsed.get("b");
        assertEquals(Boolean.TRUE, b.get(0));
        assertNull(b.get(2));
        assertEquals("x\ny", b.get(3));
        assertEquals(new BigDecimal("-1.5e3"), ((Map<?, ?>) parsed.get("c")).get("d"));
        assertEquals(JsonParser.parse(JsonWriter.toJson(parsed)), parsed);
    }

    @Test
    void unicodeEscapesAreDecoded() {
        assertEquals("A", JsonParser.parse("\"\\u0041\""));
    }

    @Test
    void bigIntegersSurviveParsing() {
        Object v = JsonParser.parse("123456789012345678901234567890");
        assertInstanceOf(BigInteger.class, v);
    }

    @Test
    void writerEscapesMarkupAndControlCharacters() {
        String input = "<script>" + (char) 1;
        assertEquals("\"\\u003cscript\\u003e\\u0001\"", JsonWriter.toJson(input));
    }

    @Test
    void nonFiniteNumbersBecomeStrings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nan", Double.NaN);
        m.put("inf", Double.POSITIVE_INFINITY);
        assertEquals("{\"nan\":\"NaN\",\"inf\":\"Infinity\"}", JsonWriter.toJson(m));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : List.of("", "{", "[1,]", "{\"a\":}", "01", "\"abc", "tru", "{\"a\":1} x", "'a'", "[1 2]")) {
            assertThrows(JsonException.class, () -> JsonParser.parse(bad), bad);
        }
    }

    @Test
    void rejectsExcessiveNesting() {
        String deep = "[".repeat(100) + "]".repeat(100);
        assertThrows(JsonException.class, () -> JsonParser.parse(deep));
    }
}

package io.rocoto.core.jmx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.rocoto.core.convert.ArgumentConverter;
import io.rocoto.core.convert.ConversionException;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.convert.ValueConverter.NumberMode;
import io.rocoto.core.json.JsonParser;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.core.testbeans.Sample;

class MBeanRoundTripTest {

    private static final MBeanServer SERVER = ManagementFactory.getPlatformMBeanServer();
    private static final ValueConverter TYPED = ValueConverter.builder().build();
    private static final ValueConverter PLAIN = ValueConverter.builder().numberMode(NumberMode.PLAIN)
            .tabularAsRows(false).build();

    private static ObjectName name;
    private static MBeanAccess access;

    @BeforeAll
    static void register() throws Exception {
        name = new ObjectName("io.rocoto.test:type=Sample,case=core");
        SERVER.registerMBean(new Sample(), name);
        access = new MBeanServerAccess(List.of(SERVER));
    }

    @AfterAll
    static void unregister() throws Exception {
        SERVER.unregisterMBean(name);
    }

    private static Object read(String attribute, ValueConverter converter) {
        return converter.convert(access.getAttribute(name, attribute));
    }

    @Test
    void longsBeyondTheSafeRangeKeepTheirPrecision() {
        assertEquals("9223372036854775807", read("MaxLong", TYPED));
        assertEquals(42L, read("SmallLong", TYPED));
        assertEquals("123456789012345678901234567890", read("Huge", TYPED));
        // The compatibility mode emits plain numbers, exactly like Jolokia does.
        assertEquals("9223372036854775807", JsonWriter.toJson(read("MaxLong", PLAIN)));
    }

    @Test
    void byteArraysAreBase64() {
        assertEquals("AQID/w==", read("Bytes", TYPED));
    }

    @Test
    void setsMapsAndCompositeLists() {
        assertEquals(List.of("a", "b"), read("Tags", TYPED));
        assertEquals(List.of(Map.of("x", 1, "y", 2), Map.of("x", 3, "y", 4)), read("Points", TYPED));
        // MXBean maps are TabularData: rows in the native API, index-keyed maps in Jolokia mode.
        assertEquals(List.of(Map.of("key", "one", "value", 1), Map.of("key", "two", "value", 2)),
                read("Counts", TYPED));
        assertEquals(Map.of("one", 1, "two", 2), read("Counts", PLAIN));
    }

    @Test
    void nanIsValidJson() {
        assertEquals("\"NaN\"", JsonWriter.toJson(read("NotANumber", TYPED)));
    }

    @Test
    void maskedKeysNeverLeak() {
        ValueConverter masking = ValueConverter.builder().maskedKey(k -> k.equals("y")).build();
        Map<?, ?> origin = (Map<?, ?>) read("Origin", masking);
        assertEquals(ValueConverter.MASK, origin.get("y"));
        assertTrue(origin.get("x") instanceof Integer);
    }

    @Test
    void depthLimitStopsDescending() {
        ValueConverter shallow = ValueConverter.builder().maxDepth(1).build();
        Object points = read("Points", shallow);
        assertTrue(((List<?>) points).get(0) instanceof String);
    }

    @Test
    void writeSimpleAndCompositeAttributes() {
        MBeanInfo info = access.info(name);

        MBeanAttributeInfo level = MBeanMetadata.attribute(info, "Level");
        access.setAttribute(name, "Level",
                ArgumentConverter.convert(JsonParser.parse("7"), level.getType(), MBeanMetadata.openType(level)));
        assertEquals(7, access.getAttribute(name, "Level"));

        MBeanAttributeInfo origin = MBeanMetadata.attribute(info, "Origin");
        access.setAttribute(name, "Origin", ArgumentConverter.convert(
                JsonParser.parse("{\"x\":5,\"y\":6}"), origin.getType(), MBeanMetadata.openType(origin)));
        assertEquals(Map.of("x", 5, "y", 6), read("Origin", TYPED));
    }

    @Test
    void invokeWithArraysCompositesAndOverloads() {
        MBeanInfo info = access.info(name);

        MBeanOperationInfo sum = MBeanMetadata.operation(info, "sum", null, 1);
        Object arg = ArgumentConverter.convert(JsonParser.parse("[1,2,\"9007199254740993\"]"),
                sum.getSignature()[0].getType(), MBeanMetadata.openType(sum.getSignature()[0]));
        assertEquals(9007199254740996L,
                access.invoke(name, "sum", new Object[] {arg}, new String[] {"[J"}));

        MBeanOperationInfo shift = MBeanMetadata.operation(info, "shift", null, 2);
        Object p = ArgumentConverter.convert(JsonParser.parse("{\"x\":1,\"y\":1}"),
                shift.getSignature()[0].getType(), MBeanMetadata.openType(shift.getSignature()[0]));
        Object shifted = access.invoke(name, "shift", new Object[] {p, 4},
                MBeanMetadata.signatureOf(shift).toArray(String[]::new));
        assertEquals(Map.of("x", 5, "y", 1), TYPED.convert(shifted));

        MBeanAccessException ambiguous = assertThrows(MBeanAccessException.class,
                () -> MBeanMetadata.operation(info, "overloaded", null, 1));
        assertEquals(MBeanAccessException.Kind.AMBIGUOUS_OPERATION, ambiguous.kind());
        assertEquals("overloaded",
                MBeanMetadata.operation(info, "overloaded", List.of("java.lang.String"), 1).getName());
    }

    @Test
    void errorsAreClassified() throws Exception {
        MBeanAccessException noAttribute =
                assertThrows(MBeanAccessException.class, () -> access.getAttribute(name, "Nope"));
        assertEquals(MBeanAccessException.Kind.ATTRIBUTE_NOT_FOUND, noAttribute.kind());
        assertEquals("No such attribute: Nope", noAttribute.getMessage());
        // The JDK's MXBeans already say "No such attribute: X" in their exception: no doubled prefix.
        ObjectName runtime = ObjectName.getInstance("java.lang:type=Runtime");
        assertEquals("No such attribute: DoesNotExist",
                assertThrows(MBeanAccessException.class, () -> access.getAttribute(runtime, "DoesNotExist")).getMessage());
        assertEquals("No such attribute: DoesNotExist",
                assertThrows(MBeanAccessException.class, () -> access.setAttribute(runtime, "DoesNotExist", 1)).getMessage());
        assertEquals(MBeanAccessException.Kind.FAILED,
                assertThrows(MBeanAccessException.class,
                        () -> access.invoke(name, "fail", new Object[0], new String[0])).kind());
        ObjectName missing = ObjectName.getInstance("io.rocoto.test:type=Missing");
        assertEquals(MBeanAccessException.Kind.MBEAN_NOT_FOUND,
                assertThrows(MBeanAccessException.class, () -> access.info(missing)).kind());
    }

    @Test
    void conversionRejectsWrongAndUnsupportedTypes() {
        assertThrows(ConversionException.class, () -> ArgumentConverter.convert("abc", "int", null));
        assertThrows(ConversionException.class, () -> ArgumentConverter.convert(null, "int", null));
        assertThrows(ConversionException.class, () -> ArgumentConverter.convert(300L, "byte", null));
        // Arbitrary classes are never instantiated from client input.
        assertThrows(ConversionException.class,
                () -> ArgumentConverter.convert("x", "javax.naming.InitialContext", null));
        assertThrows(ConversionException.class,
                () -> ArgumentConverter.convert(List.of("x"), "[Ljava.net.URL;", null));
    }

    @Test
    void describeListsAttributesAndOperations() {
        Map<String, Object> d = MBeanDescriber.describe(access.info(name));
        Map<?, ?> attrs = (Map<?, ?>) d.get("attributes");
        assertEquals(Boolean.TRUE, ((Map<?, ?>) attrs.get("Name")).get("writable"));
        assertEquals(Boolean.FALSE, ((Map<?, ?>) attrs.get("MaxLong")).get("writable"));
        assertTrue(((List<?>) d.get("operations")).size() >= 7);
    }
}

package io.rocoto.core.convert;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.ArrayType;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

/**
 * Converts parsed JSON values into the Java types an MBean attribute or operation expects.
 *
 * <p>Only a closed set of target types is supported. Rocoto never instantiates classes named by
 * the client: that would turn {@code write}/{@code exec} into a deserialization gadget.
 */
public final class ArgumentConverter {

    private ArgumentConverter() {
    }

    /**
     * @param json     parsed JSON value (String, Long, BigInteger, BigDecimal, Boolean, List, Map, null)
     * @param typeName JMX type name as reported by {@code MBeanInfo} (e.g. {@code long}, {@code java.lang.String}, {@code [I})
     * @param openType open type from the MBean descriptor, or {@code null} if unknown
     */
    public static Object convert(Object json, String typeName, OpenType<?> openType) {
        if (openType != null && !(openType instanceof SimpleType)) {
            return convertOpen(json, openType);
        }
        return convertByName(json, typeName);
    }

    private static Object convertByName(Object json, String type) {
        if (json == null) {
            if (isPrimitive(type)) {
                throw new ConversionException("null is not allowed for primitive type " + type);
            }
            return null;
        }
        switch (type) {
            case "java.lang.String":
            case "java.lang.CharSequence":
            case "java.lang.Object":
                return "java.lang.Object".equals(type) ? json : asString(json);
            case "boolean":
            case "java.lang.Boolean":
                return asBoolean(json);
            case "int":
            case "java.lang.Integer":
                return Math.toIntExact(asLong(json, type));
            case "long":
            case "java.lang.Long":
                return asLong(json, type);
            case "short":
            case "java.lang.Short":
                return narrow(asLong(json, type), Short.MIN_VALUE, Short.MAX_VALUE, type).shortValue();
            case "byte":
            case "java.lang.Byte":
                return narrow(asLong(json, type), Byte.MIN_VALUE, Byte.MAX_VALUE, type).byteValue();
            case "double":
            case "java.lang.Double":
                return asDecimal(json, type).doubleValue();
            case "float":
            case "java.lang.Float":
                return asDecimal(json, type).floatValue();
            case "char":
            case "java.lang.Character": {
                String s = asString(json);
                if (s.length() != 1) {
                    throw new ConversionException("Expected a single character for " + type);
                }
                return s.charAt(0);
            }
            case "java.math.BigInteger":
                return asDecimal(json, type).toBigIntegerExact();
            case "java.math.BigDecimal":
                return asDecimal(json, type);
            case "java.util.Date":
                return asDate(json);
            case "javax.management.ObjectName":
                try {
                    return new ObjectName(asString(json));
                } catch (MalformedObjectNameException e) {
                    throw new ConversionException("Invalid ObjectName: " + e.getMessage());
                }
            case "[B":
                if (json instanceof String s) {
                    try {
                        return Base64.getDecoder().decode(s);
                    } catch (IllegalArgumentException e) {
                        throw new ConversionException("byte[] must be base64 encoded");
                    }
                }
                break;
            case "java.util.List":
            case "java.util.Collection":
            case "java.util.ArrayList":
                return new ArrayList<>(asList(json, type));
            case "java.util.Set":
            case "java.util.LinkedHashSet":
            case "java.util.HashSet":
                return new LinkedHashSet<>(asList(json, type));
            case "java.util.Map":
            case "java.util.HashMap":
            case "java.util.LinkedHashMap":
                if (json instanceof Map<?, ?> m) {
                    return new LinkedHashMap<>(m);
                }
                throw new ConversionException("Expected a JSON object for " + type);
            default:
                break;
        }
        if (type.startsWith("[")) {
            return convertArray(json, type);
        }
        throw new ConversionException("Unsupported parameter type " + type);
    }

    private static Object convertArray(Object json, String type) {
        Collection<?> items = asList(json, type);
        String componentName = componentTypeName(type);
        Class<?> componentClass = classFor(componentName);
        Object array = Array.newInstance(componentClass, items.size());
        int i = 0;
        for (Object item : items) {
            Array.set(array, i++, convertByName(item, componentName));
        }
        return array;
    }

    private static String componentTypeName(String arrayType) {
        String rest = arrayType.substring(1);
        if (rest.startsWith("[")) {
            return rest;
        }
        return switch (rest.charAt(0)) {
            case 'Z' -> "boolean";
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'S' -> "short";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> rest.substring(1, rest.length() - 1);
            default -> throw new ConversionException("Unsupported array type " + arrayType);
        };
    }

    /** Resolves only the closed set of supported element types – never an arbitrary class name. */
    private static Class<?> classFor(String name) {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "char" -> char.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "java.lang.String" -> String.class;
            case "java.lang.Boolean" -> Boolean.class;
            case "java.lang.Integer" -> Integer.class;
            case "java.lang.Long" -> Long.class;
            case "java.lang.Short" -> Short.class;
            case "java.lang.Byte" -> Byte.class;
            case "java.lang.Double" -> Double.class;
            case "java.lang.Float" -> Float.class;
            case "java.lang.Character" -> Character.class;
            case "java.math.BigInteger" -> BigInteger.class;
            case "java.math.BigDecimal" -> BigDecimal.class;
            case "java.util.Date" -> Date.class;
            case "javax.management.ObjectName" -> ObjectName.class;
            case "java.lang.Object" -> Object.class;
            default -> {
                if (name.startsWith("[")) {
                    yield Array.newInstance(classFor(componentTypeName(name)), 0).getClass();
                }
                throw new ConversionException("Unsupported array element type " + name);
            }
        };
    }

    private static Object convertOpen(Object json, OpenType<?> type) {
        if (json == null) {
            return null;
        }
        if (type instanceof SimpleType) {
            return convertByName(json, type.getClassName());
        }
        if (type instanceof ArrayType<?> at) {
            if (at.isPrimitiveArray() || at.getElementOpenType() instanceof SimpleType) {
                return convertByName(json, at.getClassName());
            }
            Collection<?> items = asList(json, at.getClassName());
            OpenType<?> elementType = at.getDimension() == 1 ? at.getElementOpenType() : lowerDimension(at);
            Class<?> elementClass = elementType instanceof CompositeType
                    ? javax.management.openmbean.CompositeData.class
                    : elementType instanceof TabularType
                        ? javax.management.openmbean.TabularData.class
                        : Object.class;
            Object array = Array.newInstance(elementClass, items.size());
            int i = 0;
            for (Object item : items) {
                Array.set(array, i++, convertOpen(item, elementType));
            }
            return array;
        }
        if (type instanceof CompositeType ct) {
            if (!(json instanceof Map<?, ?> m)) {
                throw new ConversionException("Expected a JSON object for composite type " + ct.getTypeName());
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (String key : ct.keySet()) {
                values.put(key, convertOpen(m.get(key), ct.getType(key)));
            }
            for (Object key : m.keySet()) {
                if (!ct.containsKey(String.valueOf(key))) {
                    throw new ConversionException("Unknown key '" + key + "' for composite type " + ct.getTypeName());
                }
            }
            try {
                return new CompositeDataSupport(ct, values);
            } catch (OpenDataException e) {
                throw new ConversionException("Cannot build composite data: " + e.getMessage());
            }
        }
        if (type instanceof TabularType tt) {
            TabularDataSupport table = new TabularDataSupport(tt);
            for (Object row : asList(json, tt.getTypeName())) {
                table.put((javax.management.openmbean.CompositeData) convertOpen(row, tt.getRowType()));
            }
            return table;
        }
        throw new ConversionException("Unsupported open type " + type.getTypeName());
    }

    private static OpenType<?> lowerDimension(ArrayType<?> at) {
        try {
            return new ArrayType<>(at.getDimension() - 1, at.getElementOpenType());
        } catch (OpenDataException e) {
            throw new ConversionException(e.getMessage());
        }
    }

    private static boolean isPrimitive(String type) {
        return switch (type) {
            case "boolean", "byte", "char", "short", "int", "long", "float", "double" -> true;
            default -> false;
        };
    }

    private static String asString(Object json) {
        if (json instanceof Map || json instanceof Collection) {
            throw new ConversionException("Expected a scalar value");
        }
        return String.valueOf(json);
    }

    private static Boolean asBoolean(Object json) {
        if (json instanceof Boolean b) {
            return b;
        }
        if (json instanceof String s && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
            return Boolean.parseBoolean(s);
        }
        throw new ConversionException("Expected a boolean");
    }

    private static long asLong(Object json, String type) {
        try {
            if (json instanceof Long l) {
                return l;
            }
            if (json instanceof BigInteger bi) {
                return bi.longValueExact();
            }
            if (json instanceof BigDecimal bd) {
                return bd.longValueExact();
            }
            if (json instanceof String s) {
                // Strings are accepted so that values emitted in TYPED mode round-trip.
                return Long.parseLong(s.trim());
            }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new ConversionException("Value out of range or not integral for " + type);
        }
        throw new ConversionException("Expected an integer for " + type);
    }

    private static Long narrow(long value, long min, long max, String type) {
        if (value < min || value > max) {
            throw new ConversionException("Value out of range for " + type);
        }
        return value;
    }

    private static BigDecimal asDecimal(Object json, String type) {
        try {
            if (json instanceof Long l) {
                return BigDecimal.valueOf(l);
            }
            if (json instanceof BigInteger bi) {
                return new BigDecimal(bi);
            }
            if (json instanceof BigDecimal bd) {
                return bd;
            }
            if (json instanceof String s) {
                return new BigDecimal(s.trim());
            }
        } catch (NumberFormatException e) {
            throw new ConversionException("Not a number for " + type);
        }
        throw new ConversionException("Expected a number for " + type);
    }

    private static Date asDate(Object json) {
        if (json instanceof Long l) {
            return new Date(l);
        }
        if (json instanceof String s) {
            try {
                return Date.from(Instant.parse(s));
            } catch (RuntimeException e) {
                throw new ConversionException("Dates must be ISO-8601 instants or epoch milliseconds");
            }
        }
        throw new ConversionException("Expected a date");
    }

    private static List<?> asList(Object json, String type) {
        if (json instanceof List<?> l) {
            return l;
        }
        throw new ConversionException("Expected a JSON array for " + type);
    }
}

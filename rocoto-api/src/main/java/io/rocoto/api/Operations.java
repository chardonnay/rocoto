package io.rocoto.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.management.ObjectName;

import io.rocoto.core.convert.ValueConverter;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;
import jakarta.servlet.http.HttpServletResponse;

/** The API operations, shared by the resource style, the RPC style and bulk requests. */
final class Operations {

    /** Set once at start-up from {@code api.maxPatternMBeans}. */
    static volatile int maxPatternMBeans = 1000;

    private Operations() {
    }

    static Map<String, Object> dispatch(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            String type, Map<String, Object> body) {
        switch (type) {
            case "search":
                return search(pipeline, ctx, body.get("pattern") instanceof String p ? p : "*:*");
            case "describe":
                return describe(pipeline, ctx, Http.objectName(string(body, "mbean")));
            case "read": {
                List<String> attributes = new ArrayList<>();
                if (body.get("attribute") instanceof String a) {
                    attributes.add(a);
                }
                List<String> more = stringList(body.get("attributes"));
                if (more != null) {
                    attributes.addAll(more);
                }
                return read(pipeline, converter, ctx, Http.objectName(string(body, "mbean")), attributes);
            }
            case "write":
                if (!body.containsKey("value")) {
                    throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "'value' is required");
                }
                return write(pipeline, converter, ctx, Http.objectName(string(body, "mbean")),
                        string(body, "attribute"), body.get("value"));
            case "exec":
                return exec(pipeline, converter, ctx, Http.objectName(string(body, "mbean")),
                        string(body, "operation"), stringList(body.get("signature")), list(body.get("arguments")));
            default:
                throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Unknown request type '" + type + "'");
        }
    }

    static Map<String, Object> search(RequestPipeline pipeline, CallContext ctx, String pattern) {
        List<String> names = new ArrayList<>();
        for (ObjectName n : pipeline.search(ctx, Http.objectName(pattern))) {
            names.add(n.getCanonicalName());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pattern", pattern);
        out.put("mbeans", names);
        return out;
    }

    static Map<String, Object> describe(RequestPipeline pipeline, CallContext ctx, ObjectName name) {
        requireConcrete(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mbean", name.getCanonicalName());
        out.putAll(pipeline.describe(ctx, name));
        return out;
    }

    /**
     * Reads one attribute, several attributes or (empty list) all attributes. With an ObjectName
     * pattern the result is grouped per MBean and inaccessible values are left out instead of
     * failing the whole request.
     */
    static Map<String, Object> read(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            ObjectName name, List<String> attributes) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (name.isPattern()) {
            Map<String, Object> perMBean = new LinkedHashMap<>();
            for (ObjectName concrete : Http.limited(pipeline.search(ctx, name), maxPatternMBeans)) {
                Map<String, String> errors = new LinkedHashMap<>();
                Map<String, Object> values = pipeline.readAll(ctx, concrete, errors);
                if (!attributes.isEmpty()) {
                    values.keySet().retainAll(attributes);
                }
                if (!values.isEmpty()) {
                    perMBean.put(concrete.getCanonicalName(), converter.convert(values));
                }
            }
            out.put("pattern", name.getCanonicalName());
            out.put("values", perMBean);
            return out;
        }
        out.put("mbean", name.getCanonicalName());
        if (attributes.size() == 1) {
            out.put("attribute", attributes.get(0));
            out.put("value", converter.convert(pipeline.read(ctx, name, attributes.get(0))));
            return out;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        if (attributes.isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            values.putAll(pipeline.readAll(ctx, name, errors));
            if (!errors.isEmpty()) {
                out.put("errors", errors);
            }
        } else {
            for (String a : attributes) {
                values.put(a, pipeline.read(ctx, name, a));
            }
        }
        out.put("values", converter.convert(values));
        return out;
    }

    static Map<String, Object> write(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            ObjectName name, String attribute, Object value) {
        requireConcrete(name);
        Object previous = pipeline.write(ctx, name, attribute, value);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mbean", name.getCanonicalName());
        out.put("attribute", attribute);
        out.put("previousValue", converter.convert(previous));
        return out;
    }

    static Map<String, Object> exec(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            ObjectName name, String operation, List<String> signature, List<Object> arguments) {
        requireConcrete(name);
        Object result = pipeline.exec(ctx, name, operation, signature, arguments);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mbean", name.getCanonicalName());
        out.put("operation", operation);
        out.put("value", converter.convert(result));
        return out;
    }

    /** A JSON Schema describing the readable attributes of one MBean. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> schema(RequestPipeline pipeline, CallContext ctx, ObjectName name) {
        requireConcrete(name);
        Map<String, Object> description = pipeline.describe(ctx, name);
        Map<String, Object> properties = new LinkedHashMap<>();
        ((Map<String, Object>) description.get("attributes")).forEach((attr, meta) -> {
            Map<String, Object> m = (Map<String, Object>) meta;
            Map<String, Object> property = new LinkedHashMap<>(jsonType(String.valueOf(m.get("type"))));
            property.put("description", m.get("description"));
            property.put("readOnly", !Boolean.TRUE.equals(m.get("writable")));
            property.put("x-jmx-type", m.get("type"));
            properties.put(attr, property);
        });
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", name.getCanonicalName());
        schema.put("description", description.get("description"));
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> jsonType(String jmxType) {
        return switch (jmxType) {
            case "boolean", "java.lang.Boolean" -> Map.of("type", "boolean");
            case "int", "short", "byte", "java.lang.Integer", "java.lang.Short", "java.lang.Byte" ->
                Map.of("type", "integer");
            // 64-bit values outside the IEEE-754 safe range are transported as strings
            case "long", "java.lang.Long" -> Map.of("type", List.of("integer", "string"));
            case "java.math.BigInteger", "java.math.BigDecimal" -> Map.of("type", "string");
            case "double", "float", "java.lang.Double", "java.lang.Float" -> Map.of("type", List.of("number", "string"));
            case "java.lang.String", "javax.management.ObjectName", "char", "java.lang.Character" ->
                Map.of("type", "string");
            case "java.util.Date" -> Map.of("type", "string", "format", "date-time");
            case "[B" -> Map.of("type", "string", "contentEncoding", "base64");
            case "javax.management.openmbean.CompositeData" -> Map.of("type", "object");
            case "javax.management.openmbean.TabularData" -> Map.of("type", "array");
            default -> jmxType.startsWith("[") ? Map.of("type", "array") : Map.of();
        };
    }

    private static void requireConcrete(ObjectName name) {
        if (name.isPattern()) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "A concrete ObjectName is required, not a pattern");
        }
    }

    private static String string(Map<String, Object> body, String key) {
        if (body.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "'" + key + "' is required");
    }

    /** @return the list of strings, or {@code null} if the value is absent */
    static List<String> stringList(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof List<?> l)) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Expected an array of strings");
        }
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "'arguments' must be an array");
    }
}

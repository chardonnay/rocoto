package io.rocoto.compat.jolokia;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanParameterInfo;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.api.Http;
import io.rocoto.api.PipelineResourceReader;
import io.rocoto.api.RocotoRuntime;
import io.rocoto.core.convert.ConversionException;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.core.convert.ValueConverter.NumberMode;
import io.rocoto.core.jmx.MBeanAccessException;
import io.rocoto.core.jmx.MBeanMetadata;
import io.rocoto.core.json.JsonException;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.AccessDeniedException;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Speaks the Jolokia protocol (version 7.x) on {@code /jolokia/*} so existing clients – the
 * Jolokia Java and JavaScript clients, hawtio, Telegraf's {@code jolokia2_agent}, monitoring
 * checks – keep working. Every request runs through the same pipeline as the native API.
 *
 * <p>Deliberate differences to Jolokia: authentication is mandatory, {@code write}/{@code exec}
 * need an allow rule, proxy mode ({@code target}) and JSONP ({@code callback}) do not exist,
 * stack traces are never returned, arbitrary beans are rendered with {@code toString()}.
 */
public class JolokiaServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String PROTOCOL_VERSION = "7.2";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, false);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, true);
    }

    private void handle(HttpServletRequest request, HttpServletResponse response, boolean post) throws IOException {
        try {
            RocotoRuntime runtime = Http.runtime(getServletContext());
            if (!runtime.config().bool("compat.jolokia.enabled", true)) {
                Http.writeJson(response, 404, Http.error(404, "The Jolokia compatibility endpoint is disabled"));
                return;
            }
            if (request.getParameter("callback") != null) {
                Http.writeJson(response, 400, Http.error(400, "JSONP is not supported"));
                return;
            }
            CallContext ctx = Http.context(request, "jolokia");
            Object body;
            if (post) {
                Object parsed = Http.readJson(request);
                if (parsed instanceof List<?> bulk) {
                    int max = runtime.config().integer("api.maxBulkRequests", 100);
                    if (bulk.size() > max) {
                        Http.writeJson(response, 413, Http.error(413, "At most " + max + " requests per bulk call"));
                        return;
                    }
                    List<Object> results = new ArrayList<>(bulk.size());
                    for (Object item : bulk) {
                        results.add(execute(runtime, ctx, item, request));
                    }
                    body = results;
                } else {
                    body = execute(runtime, ctx, parsed, request);
                }
            } else {
                Map<String, Object> fromPath = fromPath(Http.pathSegmentsRaw(request, "/jolokia"));
                String type = String.valueOf(fromPath.get("type"));
                if ((type.equals("write") || type.equals("exec")) && !getMutationAllowed(runtime, request)) {
                    Http.writeJson(response, 405, Http.error(405, "write and exec must be sent with POST"));
                    return;
                }
                body = execute(runtime, ctx, fromPath, request);
            }
            response.setStatus(200);
            response.setContentType("text/plain".equals(request.getParameter("mimeType"))
                    ? "text/plain" : "application/json");
            response.setCharacterEncoding("UTF-8");
            JsonWriter.write(body, response.getWriter());
        } catch (RuntimeException e) {
            Http.writeError(response, e);
        }
    }

    /**
     * A state change triggered by a plain URL is what CSRF attacks are made of, yet the Jolokia
     * Java client sends simple write/exec requests as GET. Compromise: GET mutations are accepted
     * from non-browser clients only. Every current browser labels its requests with fetch metadata
     * ({@code Sec-Fetch-*}); a request carrying such a header, or an {@code Origin}, has to use POST,
     * which is protected by the JSON-only rule. {@code compat.jolokia.allowGetMutations: false}
     * switches GET mutations off altogether.
     */
    private static boolean getMutationAllowed(RocotoRuntime runtime, HttpServletRequest request) {
        if (!runtime.config().bool("compat.jolokia.allowGetMutations", true)) {
            return false;
        }
        return request.getHeader("Sec-Fetch-Site") == null && request.getHeader("Sec-Fetch-Mode") == null
                && request.getHeader("Origin") == null;
    }

    // ---- request parsing -----------------------------------------------------------------

    /** Translates a GET path such as {@code /read/java.lang:type=Memory/HeapMemoryUsage/used}. */
    private static Map<String, Object> fromPath(String rawPath) {
        List<String> parts = JolokiaPath.split(rawPath);
        Map<String, Object> req = new LinkedHashMap<>();
        if (parts.isEmpty()) {
            req.put("type", "version");
            return req;
        }
        String type = parts.get(0);
        req.put("type", type);
        switch (type) {
            case "read" -> {
                put(req, "mbean", parts, 1);
                put(req, "attribute", parts, 2);
                putPath(req, parts, 3);
            }
            case "write" -> {
                put(req, "mbean", parts, 1);
                put(req, "attribute", parts, 2);
                put(req, "value", parts, 3);
                putPath(req, parts, 4);
            }
            case "exec" -> {
                put(req, "mbean", parts, 1);
                put(req, "operation", parts, 2);
                req.put("arguments", new ArrayList<Object>(parts.subList(Math.min(3, parts.size()), parts.size())));
                req.put("stringArguments", Boolean.TRUE);
            }
            case "search" -> put(req, "mbean", parts, 1);
            case "list" -> putPath(req, parts, 1);
            default -> {
            }
        }
        return req;
    }

    private static void put(Map<String, Object> req, String key, List<String> parts, int index) {
        if (parts.size() > index) {
            req.put(key, parts.get(index));
        }
    }

    private static void putPath(Map<String, Object> req, List<String> parts, int from) {
        if (parts.size() > from) {
            req.put("pathParts", new ArrayList<>(parts.subList(from, parts.size())));
        }
    }

    // ---- execution -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(RocotoRuntime runtime, CallContext ctx, Object item,
            HttpServletRequest http) {
        Map<String, Object> request = item instanceof Map ? (Map<String, Object>) item : new LinkedHashMap<>();
        Map<String, Object> echo = new LinkedHashMap<>(request);
        echo.remove("pathParts");
        echo.remove("stringArguments");
        echo.remove("config");
        if (request.get("pathParts") instanceof List<?> p) {
            echo.put("path", String.join("/", (List<String>) p));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("request", echo);
        try {
            if (!(item instanceof Map)) {
                throw new JolokiaException(400, "java.lang.IllegalArgumentException", "Request must be a JSON object");
            }
            if (request.containsKey("target")) {
                throw new JolokiaException(403, "java.lang.SecurityException",
                        "Proxy mode (target) is not supported by Rocoto");
            }
            Params params = new Params(request, http);
            Object value = dispatch(runtime, ctx, request, params);
            response.put("value", value);
            response.put("timestamp", System.currentTimeMillis() / 1000);
            response.put("status", 200);
        } catch (RuntimeException e) {
            JolokiaException je = translate(e);
            response.put("error_type", je.errorType());
            response.put("error", je.errorType() + " : " + je.getMessage());
            response.put("status", je.status());
        }
        return response;
    }

    private Object dispatch(RocotoRuntime runtime, CallContext ctx, Map<String, Object> request, Params params) {
        RequestPipeline pipeline = runtime.pipeline();
        ValueConverter converter = pipeline.converter().numberMode(NumberMode.PLAIN).tabularAsRows(false)
                .objectNameAsMap(true).maxDepth(params.maxDepth > 0 ? params.maxDepth : 16)
                .maxCollectionSize(params.maxCollectionSize > 0 ? params.maxCollectionSize : 10_000).build();
        String type = String.valueOf(request.get("type")).toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case "version" -> version(runtime, ctx);
            case "search" -> search(pipeline, ctx, name(request), params);
            case "read" -> navigate(read(pipeline, converter, ctx, request, params), path(request));
            case "write" -> write(pipeline, converter, ctx, request);
            case "exec" -> exec(pipeline, converter, ctx, request);
            case "list" -> list(pipeline, ctx, path(request), params);
            case "notification" -> throw new JolokiaException(400, "java.lang.UnsupportedOperationException",
                    "Jolokia notifications are not supported; use Rocoto's /api/v1/subscribe");
            default -> throw new JolokiaException(400, "java.lang.IllegalArgumentException",
                    "Unknown request type '" + type + "'");
        };
    }

    private Object version(RocotoRuntime runtime, CallContext ctx) {
        Map<String, Object> info = new LinkedHashMap<>();
        Map<String, Object> server = runtime.adapter().serverInfo(new PipelineResourceReader(runtime.pipeline(), ctx));
        info.put("product", String.valueOf(server.getOrDefault("product", runtime.adapter().id())));
        info.put("vendor", "Rocoto");
        if (server.get("version") != null) {
            info.put("version", String.valueOf(server.get("version")));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("agent", "rocoto-" + runtime.version());
        value.put("protocol", PROTOCOL_VERSION);
        value.put("info", info);
        value.put("config", Map.of("agentType", "servlet", "agentDescription", "Rocoto Jolokia compatibility"));
        return value;
    }

    private Object search(RequestPipeline pipeline, CallContext ctx, ObjectName pattern, Params params) {
        List<String> names = new ArrayList<>();
        for (ObjectName n : pipeline.search(ctx, pattern)) {
            names.add(params.canonicalNaming ? n.getCanonicalName() : n.toString());
        }
        return names;
    }

    private Object read(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            Map<String, Object> request, Params params) {
        ObjectName name = name(request);
        List<String> attributes = new ArrayList<>();
        Object attribute = request.get("attribute");
        if (attribute instanceof String s) {
            attributes.add(s);
        } else if (attribute instanceof List<?> l) {
            l.forEach(a -> attributes.add(String.valueOf(a)));
        }
        if (name.isPattern()) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (ObjectName concrete : Http.limited(pipeline.search(ctx, name), params.maxPatternMBeans)) {
                Map<String, String> errors = new LinkedHashMap<>();
                Map<String, Object> values = pipeline.readAll(ctx, concrete, errors);
                if (!attributes.isEmpty()) {
                    values.keySet().retainAll(attributes);
                }
                if (params.ignoreErrors) {
                    errors.forEach((a, msg) -> {
                        if (attributes.isEmpty() || attributes.contains(a)) {
                            values.put(a, "ERROR: " + msg);
                        }
                    });
                }
                if (!values.isEmpty()) {
                    result.put(params.canonicalNaming ? concrete.getCanonicalName() : concrete.toString(),
                            converter.convert(values));
                }
            }
            if (result.isEmpty()) {
                throw new JolokiaException(404, "javax.management.InstanceNotFoundException",
                        "No MBean with pattern " + name + " found for reading attributes");
            }
            return result;
        }
        if (attributes.size() == 1 && attribute instanceof String) {
            return converter.convert(pipeline.read(ctx, name, attributes.get(0)));
        }
        if (attributes.isEmpty()) {
            return converter.convert(pipeline.readAll(ctx, name, new LinkedHashMap<>()));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String a : attributes) {
            try {
                values.put(a, pipeline.read(ctx, name, a));
            } catch (AccessDeniedException | MBeanAccessException e) {
                if (!params.ignoreErrors) {
                    throw e;
                }
                values.put(a, "ERROR: " + e.getMessage());
            }
        }
        return converter.convert(values);
    }

    private Object write(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            Map<String, Object> request) {
        if (path(request) != null && !path(request).isEmpty()) {
            throw new JolokiaException(400, "java.lang.UnsupportedOperationException",
                    "Writing to an inner path is not supported");
        }
        Object value = request.get("value");
        if ("[null]".equals(value)) {
            value = null;
        }
        return converter.convert(pipeline.write(ctx, name(request), string(request, "attribute"), value));
    }

    private Object exec(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            Map<String, Object> request) {
        ObjectName name = name(request);
        String operation = string(request, "operation");
        List<String> signature = null;
        int paren = operation.indexOf('(');
        if (paren > 0 && operation.endsWith(")")) {
            String inner = operation.substring(paren + 1, operation.length() - 1).trim();
            signature = inner.isEmpty() ? List.of() : Arrays.asList(inner.split("\\s*,\\s*"));
            operation = operation.substring(0, paren);
        }
        List<Object> arguments = new ArrayList<>();
        if (request.get("arguments") instanceof List<?> l) {
            arguments.addAll(l);
        }
        if (Boolean.TRUE.equals(request.get("stringArguments"))) {
            arguments = fromStrings(pipeline, ctx, name, operation, signature, arguments);
        }
        return converter.convert(pipeline.exec(ctx, name, operation, signature, arguments));
    }

    /** GET requests carry every argument as a string: {@code [null]} is null, arrays are comma-separated. */
    private static List<Object> fromStrings(RequestPipeline pipeline, CallContext ctx, ObjectName name,
            String operation, List<String> signature, List<Object> arguments) {
        MBeanOperationInfo op = MBeanMetadata.operation(pipeline.info(ctx, name), operation, signature,
                arguments.size());
        MBeanParameterInfo[] params = op.getSignature();
        List<Object> out = new ArrayList<>(arguments.size());
        for (int i = 0; i < arguments.size(); i++) {
            Object arg = arguments.get(i);
            String type = i < params.length ? params[i].getType() : "java.lang.String";
            if ("[null]".equals(arg)) {
                out.add(null);
            } else if ("\"\"".equals(arg)) {
                out.add("");
            } else if (arg instanceof String s && (type.startsWith("[") && !type.equals("[B")
                    || type.equals("java.util.List") || type.equals("java.util.Set"))) {
                out.add(s.isEmpty() ? List.of() : new ArrayList<Object>(Arrays.asList(s.split(","))));
            } else {
                out.add(arg);
            }
        }
        return out;
    }

    // ---- list ----------------------------------------------------------------------------

    private Object list(RequestPipeline pipeline, CallContext ctx, List<String> path, Params params) {
        Map<String, Object> tree = new TreeMap<>();
        for (ObjectName name : pipeline.search(ctx, null)) {
            if (path != null && !path.isEmpty() && !path.get(0).equals(name.getDomain())) {
                continue;
            }
            String props = params.canonicalNaming ? name.getCanonicalKeyPropertyListString()
                    : name.getKeyPropertyListString();
            if (path != null && path.size() > 1 && !path.get(1).equals(props)) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> domain = (Map<String, Object>) tree.computeIfAbsent(name.getDomain(),
                        d -> new TreeMap<String, Object>());
                domain.put(props, describe(pipeline, ctx, name));
            } catch (AccessDeniedException | MBeanAccessException e) {
                // not listable for this caller
            }
        }
        Object value = navigate(tree, path);
        int depth = params.maxDepth;
        return depth > 0 ? truncate(value, depth) : value;
    }

    private static Map<String, Object> describe(RequestPipeline pipeline, CallContext ctx, ObjectName name) {
        MBeanInfo info = pipeline.info(ctx, name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("desc", info.getDescription());
        out.put("class", info.getClassName());
        Map<String, Object> attrs = new TreeMap<>();
        for (MBeanAttributeInfo a : info.getAttributes()) {
            if (!pipeline.mayRead(ctx, name, a.getName())) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rw", a.isWritable() && a.isReadable());
            m.put("type", a.getType());
            m.put("desc", a.getDescription());
            attrs.put(a.getName(), m);
        }
        if (!attrs.isEmpty()) {
            out.put("attr", attrs);
        }
        Map<String, Object> ops = new TreeMap<>();
        for (MBeanOperationInfo op : info.getOperations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            List<Object> args = new ArrayList<>();
            for (MBeanParameterInfo p : op.getSignature()) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("name", p.getName());
                pm.put("type", p.getType());
                pm.put("desc", p.getDescription());
                args.add(pm);
            }
            m.put("args", args);
            m.put("ret", op.getReturnType());
            m.put("desc", op.getDescription());
            // Overloaded operations are an array of descriptions, exactly as Jolokia does it.
            ops.merge(op.getName(), m, (existing, added) -> {
                List<Object> all = existing instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>(List.of(existing));
                all.add(added);
                return all;
            });
        }
        if (!ops.isEmpty()) {
            out.put("op", ops);
        }
        return out;
    }

    private static Object truncate(Object value, int depth) {
        if (!(value instanceof Map<?, ?> m)) {
            return value;
        }
        if (depth == 0) {
            return 1;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), truncate(v, depth - 1)));
        return out;
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Descends into an already converted value along Jolokia's inner path. */
    private static Object navigate(Object value, List<String> path) {
        if (path == null) {
            return value;
        }
        Object current = value;
        for (String part : path) {
            if (current instanceof Map<?, ?> m && m.containsKey(part)) {
                current = m.get(part);
            } else if (current instanceof List<?> l && part.matches("\\d{1,9}") && Integer.parseInt(part) < l.size()) {
                current = l.get(Integer.parseInt(part));
            } else {
                throw new JolokiaException(404, "javax.management.AttributeNotFoundException",
                        "Invalid path '" + part + "'");
            }
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static List<String> path(Map<String, Object> request) {
        if (request.get("pathParts") instanceof List<?> l) {
            return (List<String>) l;
        }
        if (request.get("path") instanceof String s) {
            return JolokiaPath.split(s);
        }
        return null;
    }

    private static ObjectName name(Map<String, Object> request) {
        String mbean = string(request, "mbean");
        try {
            return new ObjectName(mbean);
        } catch (MalformedObjectNameException e) {
            throw new JolokiaException(400, "javax.management.MalformedObjectNameException",
                    "Invalid object name " + mbean);
        }
    }

    private static String string(Map<String, Object> request, String key) {
        if (request.get(key) instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new JolokiaException(400, "java.lang.IllegalArgumentException", "'" + key + "' is required");
    }

    private static JolokiaException translate(RuntimeException e) {
        if (e instanceof JolokiaException je) {
            return je;
        }
        if (e instanceof AccessDeniedException) {
            return new JolokiaException(403, "java.lang.SecurityException", e.getMessage());
        }
        if (e instanceof MBeanAccessException m) {
            return switch (m.kind()) {
                case MBEAN_NOT_FOUND -> new JolokiaException(404, "javax.management.InstanceNotFoundException",
                        m.getMessage());
                case ATTRIBUTE_NOT_FOUND -> new JolokiaException(404, "javax.management.AttributeNotFoundException",
                        m.getMessage());
                case OPERATION_NOT_FOUND, AMBIGUOUS_OPERATION, INVALID_VALUE -> new JolokiaException(400,
                        "java.lang.IllegalArgumentException", m.getMessage());
                case FORBIDDEN_BY_SERVER -> new JolokiaException(403, "java.lang.SecurityException", m.getMessage());
                case FAILED -> new JolokiaException(500, "javax.management.MBeanException", m.getMessage());
            };
        }
        if (e instanceof ConversionException || e instanceof JsonException || e instanceof ArithmeticException) {
            return new JolokiaException(400, "java.lang.IllegalArgumentException", e.getMessage());
        }
        return new JolokiaException(Http.statusOf(e), "java.lang.Exception", Http.messageOf(e));
    }

    /** Processing parameters: query string first, overridden by the {@code config} object of a POST request. */
    private static final class Params {
        final int maxDepth;
        final int maxCollectionSize;
        final boolean ignoreErrors;
        final boolean canonicalNaming;
        final int maxPatternMBeans;

        Params(Map<String, Object> request, HttpServletRequest http) {
            this.maxPatternMBeans = Http.runtime(http.getServletContext()).config().integer("api.maxPatternMBeans", 1000);
            Map<?, ?> config = request.get("config") instanceof Map<?, ?> m ? m : Map.of();
            this.maxDepth = intParam(config, http, "maxDepth");
            this.maxCollectionSize = intParam(config, http, "maxCollectionSize");
            this.ignoreErrors = "true".equalsIgnoreCase(param(config, http, "ignoreErrors"));
            this.canonicalNaming = !"false".equalsIgnoreCase(param(config, http, "canonicalNaming"));
        }

        private static String param(Map<?, ?> config, HttpServletRequest http, String name) {
            Object v = config.get(name);
            return v != null ? String.valueOf(v) : http.getParameter(name);
        }

        private static int intParam(Map<?, ?> config, HttpServletRequest http, String name) {
            String v = param(config, http, name);
            try {
                return v == null ? 0 : Math.max(0, Integer.parseInt(v.trim()));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}

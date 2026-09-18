package io.rocoto.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.ObjectName;

import io.rocoto.adapters.ResourceKind;
import io.rocoto.core.convert.ValueConverter;
import io.rocoto.security.CallContext;
import io.rocoto.security.RequestPipeline;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * REST API v1, mapped to {@code /api/v1/*}.
 *
 * <p>Two styles are offered for the same functionality:
 * <ul>
 *   <li>resource style: {@code GET /mbeans/{name}/attributes/{attribute}} – ObjectName URL-encoded
 *       into one path segment;</li>
 *   <li>RPC style: {@code POST /read}, {@code /write}, {@code /exec}, {@code /search},
 *       {@code /describe}, {@code /bulk} with a JSON body – no escaping issues, works with every
 *       ObjectName (some servers reject encoded slashes in paths).</li>
 * </ul>
 */
public class ApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String PREFIX = "/api/v1";

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            RocotoRuntime runtime = Http.runtime(getServletContext());
            List<String> path = Http.pathSegments(request, PREFIX);
            String method = request.getMethod();
            if (path.equals(List.of("health", "live")) && method.equals("GET")) {
                Http.writeJson(response, 200, Map.of("status", "UP"));
                return;
            }
            CallContext ctx = Http.context(request, "api");
            Object result = switch (method) {
                case "GET" -> get(runtime, ctx, path, request, response);
                case "PUT" -> put(runtime, ctx, path, request);
                case "POST" -> post(runtime, ctx, path, request);
                default -> throw new ApiException(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Method not allowed");
            };
            if (result != null) {
                Http.writeJson(response, 200, result);
            }
        } catch (RuntimeException e) {
            Http.writeError(response, e);
        }
    }

    // ---- GET -----------------------------------------------------------------------------

    private Object get(RocotoRuntime rt, CallContext ctx, List<String> path, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String head = path.isEmpty() ? "" : path.get(0);
        switch (head) {
            case "server":
                return serverInfo(rt, ctx);
            case "health":
                return health(rt, ctx);
            case "openapi.json":
                return openApi(response);
            case "schema":
                return Operations.schema(rt.pipeline(), ctx, Http.objectName(request.getParameter("mbean")));
            case "resources":
                return resources(rt, ctx, path);
            case "mbeans":
                return mbeans(rt, ctx, path, request);
            default:
                throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Unknown resource");
        }
    }

    private Object mbeans(RocotoRuntime rt, CallContext ctx, List<String> path, HttpServletRequest request) {
        RequestPipeline pipeline = rt.pipeline();
        ValueConverter converter = pipeline.converter().build();
        if (path.size() == 1) {
            String pattern = request.getParameter("pattern");
            return Operations.search(pipeline, ctx, pattern == null ? "*:*" : pattern);
        }
        ObjectName name = Http.objectName(path.get(1));
        if (path.size() == 2) {
            return Operations.describe(pipeline, ctx, name);
        }
        if (path.size() == 3 && path.get(2).equals("attributes")) {
            return Operations.read(pipeline, converter, ctx, name, List.of());
        }
        if (path.size() == 4 && path.get(2).equals("attributes")) {
            return Operations.read(pipeline, converter, ctx, name, List.of(path.get(3)));
        }
        throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Unknown resource");
    }

    private Object resources(RocotoRuntime rt, CallContext ctx, List<String> path) {
        PipelineResourceReader reader = new PipelineResourceReader(rt.pipeline(), ctx);
        ValueConverter converter = rt.pipeline().converter().build();
        if (path.size() == 1) {
            List<String> kinds = new ArrayList<>();
            for (ResourceKind k : ResourceKind.values()) {
                kinds.add(k.path());
            }
            return Map.of("kinds", kinds);
        }
        ResourceKind kind = ResourceKind.fromPath(path.get(1));
        if (kind == null || path.size() > 2) {
            throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Unknown resource kind");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.path());
        out.put("server", rt.adapter().id());
        out.put("items", converter.convert(rt.adapter().resources(kind, reader)));
        return out;
    }

    private Object serverInfo(RocotoRuntime rt, CallContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rocoto", Map.of("version", rt.version(), "authMode", rt.security().mode().name().toLowerCase()));
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("adapter", rt.adapter().id());
        server.putAll(rt.adapter().serverInfo(new PipelineResourceReader(rt.pipeline(), ctx)));
        out.put("server", rt.pipeline().converter().build().convert(server));
        Map<String, Object> caller = new LinkedHashMap<>();
        caller.put("name", ctx.identity().name());
        caller.put("roles", new ArrayList<>(new java.util.TreeSet<>(ctx.identity().roles())));
        caller.put("authenticatedBy", ctx.identity().source());
        out.put("caller", caller);
        return out;
    }

    private Object health(RocotoRuntime rt, CallContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (rt.security().builtin() != null && rt.security().builtin().store().isEmpty()) {
            warnings.add("built-in user store is empty");
        }
        if (rt.config().bool("auth.allowInsecureTransport", false)) {
            warnings.add("auth.allowInsecureTransport is enabled");
        }
        if (rt.tlsProbe() != null) {
            warnings.addAll(rt.tlsProbe().warnings());
        }
        out.put("status", warnings.isEmpty() ? "UP" : "WARN");
        out.put("version", rt.version());
        out.put("adapter", rt.adapter().id());
        out.put("visibleMBeans", rt.pipeline().search(ctx, null).size());
        out.put("activeSubscriptions", rt.subscriptions().activeCount());
        out.put("warnings", warnings);
        return out;
    }

    private Object openApi(HttpServletResponse response) throws IOException {
        try (InputStream in = ApiServlet.class.getResourceAsStream("openapi.json")) {
            if (in == null) {
                throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "OpenAPI document not bundled");
            }
            response.setStatus(200);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            in.transferTo(response.getOutputStream());
            return null;
        }
    }

    // ---- PUT -----------------------------------------------------------------------------

    private Object put(RocotoRuntime rt, CallContext ctx, List<String> path, HttpServletRequest request)
            throws IOException {
        if (path.size() == 4 && path.get(0).equals("mbeans") && path.get(2).equals("attributes")) {
            Map<String, Object> body = Http.readJsonObject(request);
            if (!body.containsKey("value")) {
                throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Body must contain 'value'");
            }
            return Operations.write(rt.pipeline(), rt.pipeline().converter().build(), ctx,
                    Http.objectName(path.get(1)), path.get(3), body.get("value"));
        }
        throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Unknown resource");
    }

    // ---- POST ----------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Object post(RocotoRuntime rt, CallContext ctx, List<String> path, HttpServletRequest request)
            throws IOException {
        RequestPipeline pipeline = rt.pipeline();
        ValueConverter converter = pipeline.converter().build();
        if (path.size() == 4 && path.get(0).equals("mbeans") && path.get(2).equals("operations")) {
            Map<String, Object> body = Http.readJsonObject(request);
            return Operations.exec(pipeline, converter, ctx, Http.objectName(path.get(1)), path.get(3),
                    Operations.stringList(body.get("signature")), Operations.list(body.get("arguments")));
        }
        if (path.size() != 1) {
            throw new ApiException(HttpServletResponse.SC_NOT_FOUND, "Unknown resource");
        }
        if (path.get(0).equals("bulk")) {
            Object body = Http.readJson(request);
            Object requests = body instanceof Map<?, ?> m ? m.get("requests") : body;
            if (!(requests instanceof List<?> list)) {
                throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Expected a JSON array of requests");
            }
            int max = rt.config().integer("api.maxBulkRequests", 100);
            if (list.size() > max) {
                throw new ApiException(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "At most " + max + " requests per bulk call");
            }
            List<Object> results = new ArrayList<>(list.size());
            for (Object item : list) {
                results.add(bulkItem(pipeline, converter, ctx, item));
            }
            return Map.of("results", results);
        }
        Map<String, Object> body = Http.readJsonObject(request);
        return Operations.dispatch(pipeline, converter, ctx, path.get(0), body);
    }

    /** One failing request must not fail the others: each bulk item carries its own status. */
    @SuppressWarnings("unchecked")
    private static Object bulkItem(RequestPipeline pipeline, ValueConverter converter, CallContext ctx,
            Object item) {
        try {
            if (!(item instanceof Map<?, ?> m) || !(m.get("type") instanceof String type)) {
                throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Each request needs a 'type'");
            }
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("status", 200);
            ok.putAll((Map<String, Object>) Operations.dispatch(pipeline, converter, ctx, type,
                    (Map<String, Object>) m));
            return ok;
        } catch (RuntimeException e) {
            return Http.error(Http.statusOf(e), Http.messageOf(e));
        }
    }

    static Set<String> rpcTypes() {
        return Set.of("read", "write", "exec", "search", "describe");
    }
}

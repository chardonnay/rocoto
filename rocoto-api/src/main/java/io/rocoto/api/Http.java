package io.rocoto.api;

import java.io.IOException;
import java.io.Reader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import io.rocoto.core.convert.ConversionException;
import io.rocoto.core.jmx.MBeanAccessException;
import io.rocoto.core.json.JsonException;
import io.rocoto.core.json.JsonParser;
import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.AccessDeniedException;
import io.rocoto.security.CallContext;
import io.rocoto.security.RocotoIdentity;
import io.rocoto.security.RocotoSecurity;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Shared plumbing of the servlets: identity, JSON in/out, error mapping. */
public final class Http {

    private static final Logger LOG = Logger.getLogger(Http.class.getName());
    private static final int MAX_BODY_CHARS = 1024 * 1024;

    private Http() {
    }

    public static RocotoRuntime runtime(ServletContext context) {
        Object runtime = context.getAttribute(RocotoRuntime.CONTEXT_ATTRIBUTE);
        if (runtime instanceof RocotoRuntime r) {
            return r;
        }
        throw new ApiException(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Rocoto is not initialised");
    }

    /**
     * The caller as established by the auth filter. A missing identity means the filter is not
     * mapped in front of the servlet – a deployment error that must never result in open access.
     */
    public static CallContext context(HttpServletRequest request, String channel) {
        Object identity = request.getAttribute(RocotoSecurity.IDENTITY_ATTRIBUTE);
        if (!(identity instanceof RocotoIdentity id)) {
            throw new ApiException(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
        }
        return new CallContext(id, request.getRemoteAddr(), channel);
    }

    public static ObjectName objectName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "An MBean name is required");
        }
        try {
            return new ObjectName(name);
        } catch (MalformedObjectNameException e) {
            throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Invalid ObjectName: " + name);
        }
    }

    /** Path segments after the given prefix, each URL-decoded individually. */
    public static List<String> pathSegments(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        String base = request.getContextPath() + prefix;
        String rest = uri.startsWith(base) ? uri.substring(base.length()) : "";
        List<String> segments = new ArrayList<>();
        for (String raw : rest.split("/")) {
            if (!raw.isEmpty()) {
                segments.add(URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8));
            }
        }
        return segments;
    }

    /** The undecoded-per-segment remainder of the request URI after the prefix, URL-decoded as a whole. */
    public static String pathSegmentsRaw(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        String base = request.getContextPath() + prefix;
        String rest = uri.startsWith(base) ? uri.substring(base.length()) : "";
        return URLDecoder.decode(rest.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    public static Object readJson(HttpServletRequest request) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buffer = new char[4096];
        request.setCharacterEncoding("UTF-8");
        try (Reader reader = request.getReader()) {
            int n;
            while ((n = reader.read(buffer)) > 0) {
                if (sb.length() + n > MAX_BODY_CHARS) {
                    throw new ApiException(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
                }
                sb.append(buffer, 0, n);
            }
        }
        return JsonParser.parse(sb.toString());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readJsonObject(HttpServletRequest request) throws IOException {
        Object body = readJson(request);
        if (body instanceof Map) {
            return (Map<String, Object>) body;
        }
        throw new ApiException(HttpServletResponse.SC_BAD_REQUEST, "Expected a JSON object");
    }

    public static void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonWriter.write(body, response.getWriter());
    }

    public static Map<String, Object> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", message);
        return body;
    }

    /** Maps any failure to a status code. Stack traces never leave the server. */
    public static int statusOf(Throwable t) {
        if (t instanceof ApiException e) {
            return e.status();
        }
        if (t instanceof AccessDeniedException) {
            return HttpServletResponse.SC_FORBIDDEN;
        }
        if (t instanceof MBeanAccessException e) {
            return switch (e.kind()) {
                case MBEAN_NOT_FOUND, ATTRIBUTE_NOT_FOUND, OPERATION_NOT_FOUND -> HttpServletResponse.SC_NOT_FOUND;
                case AMBIGUOUS_OPERATION, INVALID_VALUE -> HttpServletResponse.SC_BAD_REQUEST;
                case FORBIDDEN_BY_SERVER -> HttpServletResponse.SC_FORBIDDEN;
                case FAILED -> HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
            };
        }
        if (t instanceof ConversionException || t instanceof JsonException || t instanceof ArithmeticException) {
            return HttpServletResponse.SC_BAD_REQUEST;
        }
        return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }

    public static String messageOf(Throwable t) {
        int status = statusOf(t);
        if (status == HttpServletResponse.SC_INTERNAL_SERVER_ERROR && !(t instanceof MBeanAccessException)) {
            LOG.log(Level.WARNING, "Unexpected error while handling a request", t);
            return "Internal error";
        }
        return t.getMessage();
    }

    public static void writeError(HttpServletResponse response, Throwable t) throws IOException {
        if (!response.isCommitted()) {
            int status = statusOf(t);
            writeJson(response, status, error(status, messageOf(t)));
        }
    }
}

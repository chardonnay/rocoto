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
import io.rocoto.core.format.DocumentFormat;
import io.rocoto.core.format.DocumentFormats;
import io.rocoto.core.format.FormatOptions;
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

    /** Guards pattern reads: reading every attribute of thousands of MBeans in one request is refused. */
    public static <T> java.util.Collection<T> limited(java.util.Collection<T> matches, int max) {
        if (matches.size() > max) {
            throw new ApiException(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The pattern matches "
                    + matches.size() + " MBeans; at most " + max + " can be read at once (api.maxPatternMBeans)");
        }
        return matches;
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

    // ---- output formats ------------------------------------------------------------------

    /** CSP for HTML pages: no scripts, no inline code, only Rocoto's own stylesheet. */
    public static final String HTML_CSP =
            "default-src 'none'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /**
     * The output format the client asked for: {@code ?format=} first, then {@code Accept}, else JSON.
     * Called before any work is done, so an unsupported format never executes an operation.
     *
     * @throws ApiException 406 if {@code format} names an unknown or disabled format
     */
    public static DocumentFormat format(HttpServletRequest request) {
        DocumentFormats formats = formats(request);
        String requested = request.getParameter("format");
        if (requested != null) {
            DocumentFormat f = formats.byId(requested);
            if (f == null) {
                throw new ApiException(HttpServletResponse.SC_NOT_ACCEPTABLE,
                        "Unsupported format '" + requested + "'; available: " + formats.ids());
            }
            return f;
        }
        return formats.negotiate(request.getHeader("Accept"));
    }

    private static DocumentFormats formats(HttpServletRequest request) {
        Object runtime = request.getServletContext().getAttribute(RocotoRuntime.CONTEXT_ATTRIBUTE);
        return runtime instanceof RocotoRuntime r ? r.formats() : JSON_ONLY;
    }

    private static final DocumentFormats JSON_ONLY = DocumentFormats.of(java.util.List.of());

    /** Writes a response tree in the negotiated format; falls back to JSON if the format is invalid. */
    public static void write(HttpServletRequest request, HttpServletResponse response, int status, Object body)
            throws IOException {
        DocumentFormat format;
        try {
            format = format(request);
        } catch (ApiException e) {
            format = JSON_ONLY.json();
        }
        response.setStatus(status);
        response.setContentType(format.contentType());
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Vary", "Accept");
        if (format.id().equals("html")) {
            response.setHeader("Content-Security-Policy", HTML_CSP);
        }
        format.write(body, options(request).withStatus(status), response.getWriter());
    }

    private static FormatOptions options(HttpServletRequest request) {
        String context = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String title = uri.startsWith(context) ? uri.substring(context.length()) : uri;
        try {
            title = URLDecoder.decode(title.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // keep the raw path
        }
        int width = FormatOptions.DEFAULT.maxColumnWidth();
        String w = request.getParameter("width");
        if (w != null) {
            try {
                width = Math.min(500, Integer.parseInt(w.trim()));
            } catch (NumberFormatException e) {
                // keep the default
            }
        }
        return new FormatOptions(title.isEmpty() ? "/" : title, context + "/api/v1/style.css",
                "true".equalsIgnoreCase(request.getParameter("ascii")), width);
    }

    /** Like {@link #writeError(HttpServletResponse, Throwable)}, in the client's format. */
    public static void writeError(HttpServletRequest request, HttpServletResponse response, Throwable t)
            throws IOException {
        if (!response.isCommitted()) {
            int status = statusOf(t);
            response.resetBuffer();
            write(request, response, status, error(status, messageOf(t)));
        }
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

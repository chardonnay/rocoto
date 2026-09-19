package io.rocoto.embedded;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/** The supported part of {@code HttpServletRequest}. There are no sessions and no container login. */
final class MiniRequest {

    private final Exchange exchange;
    private final MiniContext context;
    private final String servletPath;
    private final String pathInfo;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, List<String>> parameters = new LinkedHashMap<>();
    private String characterEncoding;
    HttpServletRequest proxy;
    MiniResponse response;
    MiniAsync async;

    MiniRequest(Exchange exchange, MiniContext context, String servletPath, String pathInfo) {
        this.exchange = exchange;
        this.context = context;
        this.servletPath = servletPath;
        this.pathInfo = pathInfo;
        parseQuery(exchange.rawQuery());
        if (exchange.clientCertificates() != null) {
            attributes.put("jakarta.servlet.request.X509Certificate", exchange.clientCertificates());
        }
    }

    private void parseQuery(String query) {
        if (query == null || query.isEmpty()) {
            return;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = Paths.decode(eq < 0 ? pair : pair.substring(0, eq), true);
            String value = eq < 0 ? "" : Paths.decode(pair.substring(eq + 1), true);
            parameters.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
    }

    // ---- request line -------------------------------------------------------------------------

    public String getMethod() {
        return exchange.method();
    }

    public String getProtocol() {
        return exchange.protocol();
    }

    public String getScheme() {
        return exchange.scheme();
    }

    public String getServerName() {
        return exchange.serverName();
    }

    public int getServerPort() {
        return exchange.serverPort();
    }

    public boolean isSecure() {
        return exchange.secure();
    }

    public String getRemoteAddr() {
        return exchange.remoteAddress();
    }

    public String getRemoteHost() {
        return exchange.remoteAddress();
    }

    public String getRequestURI() {
        return exchange.rawPath();
    }

    public StringBuffer getRequestURL() {
        return new StringBuffer(exchange.scheme()).append("://").append(exchange.serverName()).append(':')
                .append(exchange.serverPort()).append(exchange.rawPath());
    }

    public String getQueryString() {
        return exchange.rawQuery();
    }

    public String getContextPath() {
        return context.getContextPath();
    }

    public String getServletPath() {
        return servletPath;
    }

    public String getPathInfo() {
        return pathInfo;
    }

    public ServletContext getServletContext() {
        return context.proxy;
    }

    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    // ---- headers --------------------------------------------------------------------------------

    public String getHeader(String name) {
        List<String> values = exchange.headers().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public Enumeration<String> getHeaders(String name) {
        List<String> values = exchange.headers().get(name);
        return Collections.enumeration(values == null ? List.<String>of() : values);
    }

    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(exchange.headers().keySet());
    }

    public int getIntHeader(String name) {
        String value = getHeader(name);
        return value == null ? -1 : Integer.parseInt(value.trim());
    }

    public long getDateHeader(String name) {
        return -1;      // conditional requests are not used by Rocoto
    }

    public String getContentType() {
        return getHeader("Content-Type");
    }

    public int getContentLength() {
        return (int) Math.min(Integer.MAX_VALUE, getContentLengthLong());
    }

    public long getContentLengthLong() {
        String value = getHeader("Content-Length");
        try {
            return value == null ? -1 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public Cookie[] getCookies() {
        return null;
    }

    public Locale getLocale() {
        return Locale.getDefault();
    }

    // ---- parameters (query string only: Rocoto does not accept form posts) ------------------------

    public String getParameter(String name) {
        List<String> values = parameters.get(name);
        return values == null ? null : values.get(0);
    }

    public String[] getParameterValues(String name) {
        List<String> values = parameters.get(name);
        return values == null ? null : values.toArray(String[]::new);
    }

    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet());
    }

    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> map = new LinkedHashMap<>();
        parameters.forEach((k, v) -> map.put(k, v.toArray(String[]::new)));
        return map;
    }

    // ---- body -------------------------------------------------------------------------------------

    public String getCharacterEncoding() {
        if (characterEncoding != null) {
            return characterEncoding;
        }
        String type = getContentType();
        if (type != null) {
            for (String part : type.split(";")) {
                String p = part.trim();
                if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                    return p.substring(8).replace("\"", "").trim();
                }
            }
        }
        return null;
    }

    public void setCharacterEncoding(String encoding) {
        this.characterEncoding = encoding;
    }

    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(exchange.body(), charset));
    }

    public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        exchange.body().transferTo(copy);
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(copy.toByteArray());
        return new jakarta.servlet.ServletInputStream() {
            @Override
            public int read() {
                return in.read();
            }

            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(jakarta.servlet.ReadListener listener) {
                throw new UnsupportedOperationException("non-blocking I/O is not supported by the embedded Rocoto runtime");
            }
        };
    }

    // ---- attributes ---------------------------------------------------------------------------------

    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    public void setAttribute(String name, Object value) {
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    public void removeAttribute(String name) {
        attributes.remove(name);
    }

    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }

    // ---- security: there is no container, only Rocoto's built-in authentication ---------------------

    public Principal getUserPrincipal() {
        return null;
    }

    public String getRemoteUser() {
        return null;
    }

    public String getAuthType() {
        return null;
    }

    public boolean isUserInRole(String role) {
        return false;
    }

    public boolean authenticate(HttpServletResponse servletResponse) {
        return false;
    }

    public HttpSession getSession(boolean create) {
        if (create) {
            throw new UnsupportedOperationException("sessions are not supported by the embedded Rocoto runtime");
        }
        return null;
    }

    public HttpSession getSession() {
        return getSession(true);
    }

    public String getRequestedSessionId() {
        return null;
    }

    // ---- async ----------------------------------------------------------------------------------------

    public boolean isAsyncSupported() {
        return true;
    }

    public boolean isAsyncStarted() {
        return async != null && !async.completed();
    }

    public AsyncContext startAsync() {
        if (async == null) {
            async = new MiniAsync(this, response, exchange);
        }
        return async.proxy;
    }

    public AsyncContext startAsync(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse servletResponse) {
        return startAsync();
    }

    public AsyncContext getAsyncContext() {
        if (async == null) {
            throw new IllegalStateException("startAsync() has not been called");
        }
        return async.proxy;
    }
}

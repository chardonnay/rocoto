package io.rocoto.security.servlet;

import java.io.IOException;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import io.rocoto.core.json.JsonWriter;
import io.rocoto.security.RocotoIdentity;
import io.rocoto.security.RocotoSecurity;
import io.rocoto.security.RocotoSecurity.AuthMode;
import io.rocoto.security.audit.AuditEvent;
import io.rocoto.security.audit.AuditEvent.Outcome;
import io.rocoto.security.auth.AuthRequest;
import io.rocoto.security.auth.AuthResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * First element of every request path. Enforces, in this order:
 * <ol>
 *   <li>response hardening headers</li>
 *   <li>cross-origin rules (deny unless the origin is allow-listed) and JSON-only request bodies,
 *       which together defeat CSRF from browsers</li>
 *   <li>authentication – built-in, container (Elytron / realm) or both, depending on {@code auth.mode}</li>
 * </ol>
 * The authenticated {@link RocotoIdentity} is stored as request attribute; endpoints refuse to
 * work without it.
 */
public class RocotoAuthFilter extends HttpFilter {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        RocotoSecurity security = (RocotoSecurity) getServletContext().getAttribute(RocotoSecurity.CONTEXT_ATTRIBUTE);
        hardenResponse(response);
        if (security == null) {
            // Start-up failed (e.g. invalid configuration). Fail closed.
            sendError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Rocoto is not initialised; see server log");
            return;
        }
        if (!originAllowed(request, response, security)) {
            return;
        }
        if (!bodyTypeAllowed(request)) {
            sendError(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "Request bodies must be sent as application/json");
            return;
        }
        if (security.anonymousHealth() && "GET".equals(request.getMethod())
                && "/api/v1/health/live".equals(pathOf(request))) {
            chain.doFilter(request, response);
            return;
        }
        RocotoIdentity identity = authenticate(request, response, security);
        if (identity == null) {
            return;
        }
        request.setAttribute(RocotoSecurity.IDENTITY_ATTRIBUTE, identity);
        chain.doFilter(new IdentityRequest(request, identity), response);
    }

    /**
     * Presents the Rocoto identity through the standard servlet API. This is what carries the
     * identity across a WebSocket upgrade, where request attributes are not available.
     */
    private static final class IdentityRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final RocotoPrincipal principal;

        IdentityRequest(HttpServletRequest request, RocotoIdentity identity) {
            super(request);
            this.principal = new RocotoPrincipal(identity);
        }

        @Override
        public Principal getUserPrincipal() {
            return principal;
        }

        @Override
        public boolean isUserInRole(String role) {
            return principal.identity().roles().contains(role);
        }

        @Override
        public String getRemoteUser() {
            return principal.getName();
        }
    }

    private RocotoIdentity authenticate(HttpServletRequest request, HttpServletResponse response,
            RocotoSecurity security) throws IOException, ServletException {
        AuthMode mode = security.mode();
        if (mode != AuthMode.CONTAINER) {
            AuthResult result = security.builtin().authenticate(toAuthRequest(request));
            switch (result.status()) {
                case AUTHENTICATED:
                    return result.identity();
                case FAILED:
                    auditAuth(security, request, result, Outcome.AUTH_FAILED);
                    challenge(response, security);
                    return null;
                case LOCKED:
                    auditAuth(security, request, result, Outcome.AUTH_LOCKED);
                    response.setHeader("Retry-After", "60");
                    sendError(response, 429, "Too many failed login attempts");
                    return null;
                case INSECURE_TRANSPORT:
                    auditAuth(security, request, result, Outcome.AUTH_FAILED);
                    sendError(response, HttpServletResponse.SC_FORBIDDEN,
                            "Credentials are only accepted over TLS");
                    return null;
                case NO_CREDENTIALS:
                default:
                    if (mode == AuthMode.BUILTIN) {
                        challenge(response, security);
                        return null;
                    }
            }
        }
        return containerIdentity(request, response, security);
    }

    /** Container mode: the application server (Elytron, Tomcat realm, ...) authenticates. */
    private RocotoIdentity containerIdentity(HttpServletRequest request, HttpServletResponse response,
            RocotoSecurity security) throws IOException, ServletException {
        if (request.getUserPrincipal() == null) {
            boolean authenticated;
            try {
                authenticated = request.authenticate(response);
            } catch (IllegalStateException | ServletException e) {
                // IllegalStateException: no login-config / security domain for the deployment.
                // ServletException: Undertow (WildFly) reports "authentication failed" this way when the
                // mechanism did not send a challenge itself, e.g. for requests without credentials.
                authenticated = false;
            }
            if (!authenticated) {
                if (request.getHeader("Authorization") != null) {
                    // Credentials were presented and the application server rejected them.
                    security.audit().log(new AuditEvent(Instant.now(), null, "container", request.getRemoteAddr(),
                            "auth", "LOGIN", null, null, Outcome.AUTH_FAILED,
                            "rejected by the application server's security domain", null));
                }
                if (!response.isCommitted() && response.getStatus() != HttpServletResponse.SC_UNAUTHORIZED) {
                    // The container has not written a challenge of its own.
                    response.resetBuffer();
                    challenge(response, security);
                }
                return null;
            }
        }
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            if (!response.isCommitted()) {
                sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
            }
            return null;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : security.policy().knownRoles()) {
            if (request.isUserInRole(role)) {
                roles.add(role);
            }
        }
        return new RocotoIdentity(principal.getName(), roles, "container");
    }

    private static AuthRequest toAuthRequest(HttpServletRequest request) {
        Object certs = request.getAttribute("jakarta.servlet.request.X509Certificate");
        return new AuthRequest(request.getHeader("Authorization"),
                certs instanceof X509Certificate[] chain ? chain : null, request.isSecure(), request.getRemoteAddr());
    }

    private static void challenge(HttpServletResponse response, RocotoSecurity security) throws IOException {
        if (security.mode() != AuthMode.BUILTIN && !response.containsHeader("WWW-Authenticate")) {
            response.addHeader("WWW-Authenticate", security.containerChallenge());
        }
        if (security.builtin() != null) {
            for (String c : security.builtin().challenges()) {
                if (!response.getHeaders("WWW-Authenticate").contains(c)) {
                    response.addHeader("WWW-Authenticate", c);
                }
            }
        }
        sendError(response, HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
    }

    private static void auditAuth(RocotoSecurity security, HttpServletRequest request, AuthResult result,
            Outcome outcome) {
        security.audit().log(new AuditEvent(Instant.now(), result.user(), "builtin", request.getRemoteAddr(),
                "auth", "LOGIN", null, null, outcome, result.detail(), null));
    }

    private static void hardenResponse(HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    /**
     * Requests carrying an {@code Origin} header come from a browser. They are rejected unless the
     * origin is the server itself or explicitly allow-listed – there is no wildcard.
     */
    private static boolean originAllowed(HttpServletRequest request, HttpServletResponse response,
            RocotoSecurity security) throws IOException {
        String origin = request.getHeader("Origin");
        if (origin == null) {
            // Browsers do not send Origin for plain cross-site GETs (<img>, <script>, navigation) but
            // they do attach cached Basic credentials. Fetch metadata closes that gap.
            String site = request.getHeader("Sec-Fetch-Site");
            if (site != null && !site.equals("same-origin") && !site.equals("none")) {
                sendError(response, HttpServletResponse.SC_FORBIDDEN, "Cross-site request denied");
                return false;
            }
            return true;
        }
        String self = request.getScheme() + "://" + request.getServerName()
                + (isDefaultPort(request) ? "" : ":" + request.getServerPort());
        boolean listed = security.allowedOrigins().contains(origin);
        if (!listed && !origin.equalsIgnoreCase(self)) {
            sendError(response, HttpServletResponse.SC_FORBIDDEN, "Cross-origin request denied");
            return false;
        }
        if (listed) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Vary", "Origin");
            response.setHeader("Access-Control-Allow-Credentials", "true");
            if ("OPTIONS".equals(request.getMethod())) {
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT");
                response.setHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
                response.setHeader("Access-Control-Max-Age", "600");
                response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultPort(HttpServletRequest request) {
        int port = request.getServerPort();
        return ("http".equals(request.getScheme()) && port == 80) || ("https".equals(request.getScheme()) && port == 443);
    }

    /** HTML forms cannot send application/json, so this blocks classic cross-site form posts. */
    private static boolean bodyTypeAllowed(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            return true;
        }
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith("application/json");
    }

    private static String pathOf(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return servletPath + pathInfo;
    }

    static void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", status);
        body.put("error", message);
        response.getWriter().write(JsonWriter.toJson(body));
    }
}

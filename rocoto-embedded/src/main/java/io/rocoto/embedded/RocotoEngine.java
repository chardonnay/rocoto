package io.rocoto.embedded;

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.api.ApiServlet;
import io.rocoto.api.MetricsServlet;
import io.rocoto.api.RocotoBootstrap;
import io.rocoto.api.RocotoRuntime;
import io.rocoto.api.stream.SseServlet;
import io.rocoto.compat.jolokia.JolokiaServlet;
import io.rocoto.core.config.ConfigException;
import io.rocoto.security.RocotoSecurity.AuthMode;
import io.rocoto.security.servlet.RocotoAuthFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rocoto without a servlet container: the same bootstrap listener, authentication filter and
 * servlets as in {@code rocoto.war}, wired like its {@code web.xml}, driven by {@link Exchange}s
 * of an HTTP server that has no servlet API (Undertow core, Netty).
 *
 * <p>Differences to the WAR: only {@code auth.mode: builtin} (there is no container to delegate
 * to) and no WebSocket endpoint - streaming is offered over Server-Sent Events.
 */
public final class RocotoEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RocotoEngine.class.getName());

    private record Route(String path, boolean prefix, Servlet servlet) {
    }

    private final String contextPath;
    private final EmbeddedConfig settings;
    private final MiniContext context;
    private final RocotoBootstrap bootstrap = new RocotoBootstrap();
    private final Filter filter = new RocotoAuthFilter();
    private final List<Route> routes;
    private final RocotoRuntime runtime;

    private RocotoEngine(String requestedContextPath, ClassLoader classLoader) {
        this.context = new MiniContext("", classLoader);
        context.proxy = Proxies.create(ServletContext.class, context);
        bootstrap.contextInitialized(new ServletContextEvent(context.proxy));
        if (!(context.getAttribute(RocotoRuntime.CONTEXT_ATTRIBUTE) instanceof RocotoRuntime started)) {
            throw new ConfigException("Rocoto failed to start - see the SEVERE log message above");
        }
        this.runtime = started;
        this.settings = new EmbeddedConfig(runtime.config().sub("embedded"));
        this.contextPath = normalize(requestedContextPath != null ? requestedContextPath : settings.contextPath());
        context.contextPath(contextPath);
        if (runtime.security().mode() != AuthMode.BUILTIN) {
            close();
            throw new ConfigException("auth.mode: the embedded runtime (Undertow, Netty) has no container to "
                    + "delegate to - set auth.mode: builtin");
        }
        try {
            filter.init(Proxies.create(FilterConfig.class, new MiniContext.ComponentConfig("rocoto-auth", context)));
            // same order and patterns as WEB-INF/web.xml: the exact match wins over /api/v1/*
            routes = List.of(
                    new Route("/api/v1/subscribe", false, init("rocoto-sse", new SseServlet())),
                    new Route("/api/v1", true, init("rocoto-api", new ApiServlet())),
                    new Route("/metrics", false, init("rocoto-metrics", new MetricsServlet())),
                    new Route("/jolokia", true, init("rocoto-jolokia", new JolokiaServlet())));
        } catch (ServletException e) {
            close();
            throw new ConfigException("Cannot initialise Rocoto's servlets", e);
        }
    }

    private Servlet init(String name, Servlet servlet) throws ServletException {
        servlet.init(Proxies.create(ServletConfig.class, new MiniContext.ComponentConfig(name, context)));
        return servlet;
    }

    /**
     * Starts Rocoto. The configuration is looked up as usual (system property {@code rocoto.config},
     * environment variable {@code ROCOTO_CONFIG}, {@code rocoto.yaml} on the class path).
     *
     * @param contextPath the path prefix Rocoto answers under, e.g. {@code /rocoto}; {@code ""} for the
     *                    root; {@code null} to use {@code embedded.contextPath} of rocoto.yaml (default /rocoto)
     * @throws ConfigException if Rocoto cannot start; nothing is served then (fail closed)
     */
    public static RocotoEngine start(String contextPath) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return new RocotoEngine(contextPath, loader != null ? loader : RocotoEngine.class.getClassLoader());
    }

    private static String normalize(String contextPath) {
        String path = contextPath.equals("/") ? "" : contextPath;
        if (!path.isEmpty() && (!path.startsWith("/") || path.endsWith("/"))) {
            throw new ConfigException("contextPath must start with '/' and must not end with '/': " + contextPath);
        }
        return path;
    }

    /** The {@code embedded} section of rocoto.yaml: listener settings for the standalone servers. */
    public EmbeddedConfig settings() {
        return settings;
    }

    public RocotoRuntime runtime() {
        return runtime;
    }

    public String contextPath() {
        return contextPath;
    }

    /** {@code true} if the raw request path belongs to Rocoto. Cheap; may be called on an I/O thread. */
    public boolean matches(String rawPath) {
        return contextPath.isEmpty() || rawPath.equals(contextPath) || rawPath.startsWith(contextPath + "/");
    }

    /**
     * Handles one request. Blocks; call it from a worker thread. When it returns the response is
     * either complete or - for Server-Sent Events - continued by Rocoto's own threads until
     * {@link Exchange#end()}.
     */
    public void handle(Exchange exchange) {
        String path = Paths.decode(exchange.rawPath().substring(contextPath.length()), false);
        String servletPath = "";
        String pathInfo = path.isEmpty() ? null : path;
        Servlet target = null;
        for (Route route : routes) {
            if (path.equals(route.path()) || (route.prefix() && path.startsWith(route.path() + "/"))) {
                target = route.servlet();
                servletPath = route.path();
                pathInfo = path.length() == route.path().length() ? null : path.substring(route.path().length());
                break;
            }
        }
        MiniRequest request = new MiniRequest(exchange, context, servletPath, pathInfo);
        MiniResponse response = new MiniResponse(exchange);
        request.proxy = Proxies.create(HttpServletRequest.class, request);
        response.proxy = Proxies.create(HttpServletResponse.class, response);
        request.response = response;
        Servlet servlet = target;
        try {
            // the filter authenticates first; an unknown path is a 404 only for authenticated callers
            filter.doFilter(request.proxy, response.proxy, (req, res) -> {
                if (servlet == null) {
                    ((HttpServletResponse) res).sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown resource");
                } else {
                    servlet.service(req, res);
                }
            });
        } catch (IOException | ServletException | RuntimeException e) {
            LOG.log(Level.WARNING, "Request failed: " + exchange.method() + " " + exchange.rawPath(), e);
            if (!response.isCommitted()) {
                try {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal error");
                } catch (IOException | RuntimeException ignored) {
                    // nothing left to tell the client
                }
            }
        }
        if (!request.isAsyncStarted()) {
            response.finish();
        }
    }

    @Override
    public void close() {
        for (Route route : routes == null ? List.<Route>of() : routes) {
            route.servlet().destroy();
        }
        filter.destroy();
        bootstrap.contextDestroyed(new ServletContextEvent(context.proxy));
    }
}

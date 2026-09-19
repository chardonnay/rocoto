package io.rocoto.api;

import java.util.logging.Level;
import java.util.logging.Logger;

import io.rocoto.api.stream.WsEndpoint;
import io.rocoto.security.RocotoSecurity;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Starts and stops Rocoto with the web application. If start-up fails nothing is published in
 * the servlet context and the auth filter answers every request with 503 – never with data.
 */
public class RocotoBootstrap implements ServletContextListener {

    private static final Logger LOG = Logger.getLogger(RocotoBootstrap.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        try {
            RocotoRuntime runtime = RocotoRuntime.start(context.getClassLoader());
            context.setAttribute(RocotoRuntime.CONTEXT_ATTRIBUTE, runtime);
            context.setAttribute(RocotoSecurity.CONTEXT_ATTRIBUTE, runtime.security());
            if (runtime.config().bool("stream.websocket", true)) {
                registerWebSocket(context);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Rocoto failed to start and will reject all requests: " + e.getMessage(), e);
        }
    }

    /**
     * WsEndpoint extends jakarta.websocket.Endpoint: on a server without the WebSocket API (e.g.
     * Jetty without its ee10-websocket-jakarta module) merely loading it fails with a LinkageError.
     * That must not take Rocoto down - streaming stays available over SSE.
     */
    private static void registerWebSocket(ServletContext context) {
        if (context.getAttribute("jakarta.websocket.server.ServerContainer") == null) {
            LOG.info("No WebSocket container available - streaming is offered over SSE only");
            return;
        }
        try {
            WsEndpoint.register(context);
        } catch (LinkageError e) {
            LOG.log(Level.WARNING, "WebSocket API not usable - streaming is offered over SSE only", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        Object runtime = event.getServletContext().getAttribute(RocotoRuntime.CONTEXT_ATTRIBUTE);
        if (runtime instanceof RocotoRuntime r) {
            r.close();
        }
    }
}

package com.example.embedded;

import io.rocoto.embedded.RocotoEngine;
import io.rocoto.undertow.RocotoUndertowHandler;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.util.Headers;

/**
 * An Undertow application (undertow-core, no servlets) that serves Rocoto under /rocoto next to
 * its own handler. LOCAL DEMO ONLY: plain HTTP, conf/rocoto.yaml sets auth.allowInsecureTransport.
 * In production add an HTTPS listener - Rocoto refuses credentials on plain HTTP.
 */
public final class UndertowApp {

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        HttpHandler application = exchange -> {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
            exchange.getResponseSender().send("Hello from the Undertow application\n");
        };

        // Reads rocoto.yaml as usual: -Drocoto.config=..., ROCOTO_CONFIG or the class path.
        RocotoEngine rocoto = RocotoEngine.start("/rocoto");
        Runtime.getRuntime().addShutdownHook(new Thread(rocoto::close));

        Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(new RocotoUndertowHandler(rocoto, application))   // everything else: application
                .build()
                .start();
        System.out.println("Undertow application on http://localhost:" + port + "/ - Rocoto under /rocoto");
    }
}

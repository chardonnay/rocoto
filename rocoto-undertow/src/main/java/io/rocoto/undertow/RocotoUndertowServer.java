package io.rocoto.undertow;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;

import org.xnio.Options;
import org.xnio.SslClientAuthMode;

import io.rocoto.embedded.EmbeddedConfig;
import io.rocoto.embedded.RocotoEngine;
import io.undertow.Undertow;

/**
 * Standalone Rocoto on Undertow, configured by the {@code embedded} section of rocoto.yaml.
 * {@code java -jar rocoto-undertow-<version>-all.jar} exposes the MBeans of its own JVM - useful
 * for trying Rocoto out; to monitor an application use {@link RocotoUndertowAgent} or mount
 * {@link RocotoUndertowHandler} into the application's own Undertow.
 */
public final class RocotoUndertowServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RocotoUndertowServer.class.getName());

    private final RocotoEngine engine;
    private final Undertow undertow;

    private RocotoUndertowServer(RocotoEngine engine, Undertow undertow) {
        this.engine = engine;
        this.undertow = undertow;
    }

    /** @param daemon {@code true} for agents: the server threads must not keep the JVM alive */
    public static RocotoUndertowServer start(boolean daemon) {
        RocotoEngine engine = RocotoEngine.start(null);
        try {
            EmbeddedConfig settings = engine.settings();
            SSLContext ssl = settings.sslContext();
            Undertow.Builder builder = Undertow.builder()
                    .setWorkerThreads(settings.workerThreads())
                    .setWorkerOption(Options.THREAD_DAEMON, daemon)
                    .setHandler(new RocotoUndertowHandler(engine, null));
            if (ssl == null) {
                builder.addHttpListener(settings.port(), settings.host());
            } else {
                builder.addHttpsListener(settings.port(), settings.host(), ssl);
                builder.setSocketOption(Options.SSL_CLIENT_AUTH_MODE, switch (settings.clientAuth()) {
                    case NONE -> SslClientAuthMode.NOT_REQUESTED;
                    case WANT -> SslClientAuthMode.REQUESTED;
                    case NEED -> SslClientAuthMode.REQUIRED;
                });
            }
            Undertow undertow = builder.build();
            undertow.start();
            LOG.info("Rocoto listens on " + (ssl == null ? "http" : "https") + "://" + settings.host() + ":"
                    + settings.port() + engine.contextPath() + " (Undertow)"
                    + (ssl == null ? " - plain HTTP: credentials are refused unless auth.allowInsecureTransport is set" : ""));
            return new RocotoUndertowServer(engine, undertow);
        } catch (RuntimeException e) {
            engine.close();
            throw e;
        }
    }

    @Override
    public void close() {
        undertow.stop();
        engine.close();
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            RocotoUndertowServer server = start(false);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "rocoto-shutdown"));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Rocoto did not start: " + e.getMessage(), e);
            System.exit(1);
        }
        Thread.currentThread().join();
    }
}

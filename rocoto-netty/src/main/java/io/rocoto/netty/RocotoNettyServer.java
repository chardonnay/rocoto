package io.rocoto.netty;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.rocoto.embedded.EmbeddedConfig;
import io.rocoto.embedded.RocotoEngine;

/**
 * Standalone Rocoto on Netty, configured by the {@code embedded} section of rocoto.yaml.
 * {@code java -jar rocoto-netty-<version>-all.jar} exposes the MBeans of its own JVM - useful for
 * trying Rocoto out; to monitor an application use {@link RocotoNettyAgent} or add
 * {@link RocotoNettyHandler} to the application's own pipeline.
 */
public final class RocotoNettyServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RocotoNettyServer.class.getName());
    private static final int MAX_REQUEST_BYTES = 1 << 20;

    private final RocotoEngine engine;
    private final EventLoopGroup boss;
    private final EventLoopGroup io;
    private final ExecutorService workers;
    private final Channel channel;

    private RocotoNettyServer(RocotoEngine engine, EventLoopGroup boss, EventLoopGroup io, ExecutorService workers,
            Channel channel) {
        this.engine = engine;
        this.boss = boss;
        this.io = io;
        this.workers = workers;
        this.channel = channel;
    }

    /** @param daemon {@code true} for agents: the server threads must not keep the JVM alive */
    public static RocotoNettyServer start(boolean daemon) {
        RocotoEngine engine = RocotoEngine.start(null);
        EventLoopGroup boss = null;
        EventLoopGroup io = null;
        ExecutorService workers = null;
        try {
            EmbeddedConfig settings = engine.settings();
            SSLContext ssl = settings.sslContext();
            boss = new NioEventLoopGroup(1, new DefaultThreadFactory("rocoto-netty-accept", daemon));
            io = new NioEventLoopGroup(2, new DefaultThreadFactory("rocoto-netty-io", daemon));
            AtomicInteger counter = new AtomicInteger();
            workers = Executors.newFixedThreadPool(settings.workerThreads(), runnable -> {
                Thread thread = new Thread(runnable, "rocoto-netty-worker-" + counter.incrementAndGet());
                thread.setDaemon(daemon);
                return thread;
            });
            ExecutorService pool = workers;
            Channel channel = new ServerBootstrap().group(boss, io).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (ssl != null) {
                                SSLEngine sslEngine = ssl.createSSLEngine();
                                sslEngine.setUseClientMode(false);
                                switch (settings.clientAuth()) {
                                    case WANT -> sslEngine.setWantClientAuth(true);
                                    case NEED -> sslEngine.setNeedClientAuth(true);
                                    default -> {
                                    }
                                }
                                ch.pipeline().addLast(new SslHandler(sslEngine));
                            }
                            ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(MAX_REQUEST_BYTES),
                                    new RocotoNettyHandler(engine, pool), new NotFoundHandler());
                        }
                    }).bind(settings.host(), settings.port()).sync().channel();
            LOG.info("Rocoto listens on " + (ssl == null ? "http" : "https") + "://" + settings.host() + ":"
                    + settings.port() + engine.contextPath() + " (Netty)"
                    + (ssl == null ? " - plain HTTP: credentials are refused unless auth.allowInsecureTransport is set" : ""));
            return new RocotoNettyServer(engine, boss, io, workers, channel);
        } catch (InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            shutdown(boss, io, workers);
            engine.close();
            throw e instanceof RuntimeException r ? r : new IllegalStateException("interrupted while starting", e);
        }
    }

    private static void shutdown(EventLoopGroup boss, EventLoopGroup io, ExecutorService workers) {
        if (boss != null) {
            boss.shutdownGracefully();
        }
        if (io != null) {
            io.shutdownGracefully();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    @Override
    public void close() {
        channel.close();
        shutdown(boss, io, workers);
        engine.close();
    }

    public static void main(String[] args) throws InterruptedException {
        try {
            RocotoNettyServer server = start(false);
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "rocoto-shutdown"));
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Rocoto did not start: " + e.getMessage(), e);
            System.exit(1);
        }
        Thread.currentThread().join();
    }
}

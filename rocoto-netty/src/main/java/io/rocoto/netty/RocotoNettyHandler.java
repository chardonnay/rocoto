package io.rocoto.netty;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import io.rocoto.embedded.Exchange;
import io.rocoto.embedded.RocotoEngine;

/**
 * Rocoto as a Netty channel handler. Put it behind {@code HttpServerCodec} and
 * {@code HttpObjectAggregator}; requests below the engine's context path are answered by Rocoto,
 * everything else is passed on to the next handler of the pipeline:
 * <pre>
 * RocotoEngine rocoto = RocotoEngine.start("/rocoto");
 * Executor workers = Executors.newFixedThreadPool(8);      // Rocoto blocks - never the event loop
 * ...
 * pipeline.addLast(new HttpServerCodec(), new HttpObjectAggregator(1 << 20),
 *                  new RocotoNettyHandler(rocoto, workers), myApplicationHandler);
 * </pre>
 * One instance per channel. TLS is detected through an {@link SslHandler} in the pipeline.
 */
public final class RocotoNettyHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final RocotoEngine engine;
    private final Executor workers;
    private final List<Runnable> closeCallbacks = new CopyOnWriteArrayList<>();

    public RocotoNettyHandler(RocotoEngine engine, Executor workers) {
        super(false);       // released by this handler, or by the one the request is passed to
        this.engine = engine;
        this.workers = workers;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        int scheme = uri.indexOf("://");
        if (scheme > 0 && !uri.startsWith("/")) {            // absolute-form request target
            int slash = uri.indexOf('/', scheme + 3);
            uri = slash < 0 ? "/" : uri.substring(slash);
        }
        int question = uri.indexOf('?');
        String rawPath = question < 0 ? uri : uri.substring(0, question);
        String rawQuery = question < 0 || question == uri.length() - 1 ? null : uri.substring(question + 1);
        if (!request.decoderResult().isSuccess()) {
            request.release();
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        if (!engine.matches(rawPath)) {
            ctx.fireChannelRead(request);
            return;
        }
        byte[] body = ByteBufUtil.getBytes(request.content());
        NettyExchange exchange = new NettyExchange(ctx, request, rawPath, rawQuery, body);
        request.release();
        // one request at a time per connection: no pipelining while a worker thread is busy with it
        ctx.channel().config().setAutoRead(false);
        try {
            workers.execute(() -> engine.handle(exchange));
        } catch (RejectedExecutionException e) {
            ctx.channel().config().setAutoRead(true);
            ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        closeCallbacks.forEach(Runnable::run);
        closeCallbacks.clear();
        super.channelInactive(ctx);
    }

    private final class NettyExchange implements Exchange {

        private final ChannelHandlerContext ctx;
        private final String method;
        private final String protocol;
        private final String rawPath;
        private final String rawQuery;
        private final byte[] body;
        private final boolean keepAlive;
        private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile boolean streamed;
        private volatile boolean begun;

        NettyExchange(ChannelHandlerContext ctx, FullHttpRequest request, String rawPath, String rawQuery, byte[] body) {
            this.ctx = ctx;
            this.method = request.method().name();
            this.protocol = request.protocolVersion().text();
            this.rawPath = rawPath;
            this.rawQuery = rawQuery;
            this.body = body;
            this.keepAlive = HttpUtil.isKeepAlive(request);
            request.headers().forEach(h -> headers.computeIfAbsent(h.getKey(), k -> new ArrayList<>()).add(h.getValue()));
        }

        @Override
        public String method() {
            return method;
        }

        @Override
        public String rawPath() {
            return rawPath;
        }

        @Override
        public String rawQuery() {
            return rawQuery;
        }

        @Override
        public Map<String, List<String>> headers() {
            return headers;
        }

        @Override
        public InputStream body() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public String protocol() {
            return protocol;
        }

        @Override
        public String scheme() {
            return secure() ? "https" : "http";
        }

        private String hostHeader() {
            List<String> host = headers.get("Host");
            return host == null || host.isEmpty() ? null : host.get(0);
        }

        @Override
        public String serverName() {
            String host = hostHeader();
            if (host == null) {
                return ((InetSocketAddress) ctx.channel().localAddress()).getHostString();
            }
            int colon = host.startsWith("[") ? host.indexOf(':', host.indexOf(']')) : host.lastIndexOf(':');
            return colon < 0 ? host : host.substring(0, colon);
        }

        @Override
        public int serverPort() {
            String host = hostHeader();
            if (host != null) {
                int colon = host.startsWith("[") ? host.indexOf(':', host.indexOf(']')) : host.lastIndexOf(':');
                if (colon >= 0) {
                    try {
                        return Integer.parseInt(host.substring(colon + 1));
                    } catch (NumberFormatException e) {
                        // fall through to the local port
                    }
                } else {
                    return secure() ? 443 : 80;
                }
            }
            return ((InetSocketAddress) ctx.channel().localAddress()).getPort();
        }

        @Override
        public String remoteAddress() {
            InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
            return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
        }

        @Override
        public boolean secure() {
            return ctx.pipeline().get(SslHandler.class) != null;
        }

        @Override
        public X509Certificate[] clientCertificates() {
            SslHandler ssl = ctx.pipeline().get(SslHandler.class);
            if (ssl == null) {
                return null;
            }
            try {
                List<X509Certificate> chain = new ArrayList<>();
                for (Certificate certificate : ssl.engine().getSession().getPeerCertificates()) {
                    if (certificate instanceof X509Certificate x509) {
                        chain.add(x509);
                    }
                }
                return chain.isEmpty() ? null : chain.toArray(X509Certificate[]::new);
            } catch (Exception e) {
                return null;        // no client certificate was presented
            }
        }

        @Override
        public void begin(int status, Map<String, List<String>> responseHeaders, long contentLength) {
            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
            responseHeaders.forEach((name, values) -> response.headers().add(name, values));
            if (contentLength >= 0) {
                HttpUtil.setContentLength(response, contentLength);
            } else {
                streamed = true;
                HttpUtil.setTransferEncodingChunked(response, true);
            }
            if (!keepAlive || streamed) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            } else if (protocol.equals("HTTP/1.0")) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            begun = true;
            ctx.write(response);        // completes with the next flush - do not wait for it here
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            await(ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(buffer, offset, length))));
        }

        /** Called from worker threads only, so waiting for the event loop is safe. */
        private void await(ChannelFuture future) throws IOException {
            try {
                future.sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (Exception e) {
                throw new IOException("the client is gone", e);
            }
        }

        @Override
        public void end() {
            if (!ended.compareAndSet(false, true)) {
                return;
            }
            if (!ctx.channel().isActive() || !begun) {
                ctx.close();
                return;
            }
            ChannelFuture last = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            if (!keepAlive || streamed) {
                last.addListener(ChannelFutureListener.CLOSE);
            } else {
                last.addListener(f -> {
                    ctx.channel().config().setAutoRead(true);
                    ctx.read();
                });
            }
        }

        @Override
        public void onClosed(Runnable callback) {
            closeCallbacks.add(callback);
            if (!ctx.channel().isActive()) {
                callback.run();
            }
        }
    }
}

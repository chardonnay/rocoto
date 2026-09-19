package com.example.embedded;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.rocoto.embedded.RocotoEngine;
import io.rocoto.netty.RocotoNettyHandler;

/**
 * A Netty application that serves Rocoto under /rocoto next to its own handler.
 * LOCAL DEMO ONLY: plain HTTP, conf/rocoto.yaml sets auth.allowInsecureTransport. In production
 * put an SslHandler first - Rocoto detects it and refuses credentials without it.
 */
public final class NettyApp {

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        RocotoEngine rocoto = RocotoEngine.start("/rocoto");
        // Rocoto blocks (JMX calls): it must run on its own threads, never on the event loop.
        ExecutorService rocotoWorkers = Executors.newFixedThreadPool(8);
        Runtime.getRuntime().addShutdownHook(new Thread(rocoto::close));

        new ServerBootstrap()
                .group(new NioEventLoopGroup(1), new NioEventLoopGroup())
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new HttpServerCodec(),
                                new HttpObjectAggregator(1 << 20),
                                new RocotoNettyHandler(rocoto, rocotoWorkers),    // one instance per channel
                                new ApplicationHandler());                       // everything else
                    }
                })
                .bind(port).sync();
        System.out.println("Netty application on http://localhost:" + port + "/ - Rocoto under /rocoto");
    }

    private static final class ApplicationHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            byte[] body = "Hello from the Netty application\n".getBytes(StandardCharsets.UTF_8);
            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            HttpUtil.setContentLength(response, body.length);
            HttpUtil.setKeepAlive(response, HttpUtil.isKeepAlive(request));
            ctx.writeAndFlush(response);
        }
    }
}

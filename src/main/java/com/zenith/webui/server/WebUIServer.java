package com.zenith.webui.server;

import com.zenith.webui.WebUIPlugin;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the Netty HTTP server lifecycle.
 * Handles bootstrap, binding, and graceful shutdown.
 */
public class WebUIServer {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI");

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private ExecutorService blockingExecutor;

    private final int port;
    private final WebUIChannelHandler channelHandler;

    public WebUIServer(int port, WebUIChannelHandler channelHandler) {
        this.port = port;
        this.channelHandler = channelHandler;
    }

    public boolean start() {
        if (serverChannel != null && serverChannel.isActive()) {
            LOG.info("WebUI server already running, not starting again");
            return true;
        }
        cleanupStaleResources();
        try {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 protected void initChannel(SocketChannel ch) {
                     ChannelPipeline p = ch.pipeline();
                     p.addLast(new HttpServerCodec());
                     p.addLast(new HttpObjectAggregator(65536));
                     p.addLast(new ChunkedWriteHandler());
                     p.addLast(new WebSocketServerProtocolHandler("/ws"));
                     p.addLast(channelHandler);
                 }
             })
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childOption(ChannelOption.TCP_NODELAY, true);

            serverChannel = b.bind(port).sync().channel();
            LOG.info("Netty NIO server started on port {}", port);

            blockingExecutor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "webui-blocking");
                t.setDaemon(true);
                return t;
            });

            return true;
        } catch (Exception e) {
            LOG.error("Failed to start WebUI", e);
            cleanup();
            return false;
        }
    }

    public void stop() {
        // Close all SSE clients
        for (Channel ch : WebUIPlugin.sseClients) {
            ch.close();
        }
        WebUIPlugin.sseClients.clear();
        for (Channel ch : WebUIPlugin.dashboardSseClients) {
            ch.close();
        }
        WebUIPlugin.dashboardSseClients.clear();
        WebUIPlugin.sseClientTokens.clear();

        if (blockingExecutor != null) {
            blockingExecutor.shutdownNow();
            blockingExecutor = null;
        }
        cleanup();
    }

    public boolean isRunning() {
        return serverChannel != null && serverChannel.isActive();
    }

    private void cleanupStaleResources() {
        if (bossGroup != null) { bossGroup.shutdownGracefully(); bossGroup = null; }
        if (workerGroup != null) { workerGroup.shutdownGracefully(); workerGroup = null; }
    }

    private void cleanup() {
        if (serverChannel != null) { serverChannel.close(); serverChannel = null; }
        if (workerGroup != null) { workerGroup.shutdownGracefully(); workerGroup = null; }
        if (bossGroup != null) { bossGroup.shutdownGracefully(); bossGroup = null; }
    }
}

package com.zenith.webui;

import com.zenith.webui.config.ConfigManager;
import com.zenith.webui.auth.AuthManager;
import com.zenith.webui.auth.AuthHandler;
import com.zenith.webui.auth.AuthController;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.command.api.CommandUsage;
import com.zenith.module.api.Module;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.UnsynchronizedAppenderBase;

import com.zenith.event.chat.PublicChatEvent;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.event.chat.DeathMessageChatEvent;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.queue.QueueStartEvent;
import com.zenith.event.queue.QueuePositionUpdateEvent;
import com.zenith.event.queue.QueueCompleteEvent;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketCodecRegistries;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;

import static com.zenith.Globals.*;

public class WebUIPlugin implements ZenithProxyPlugin {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI");
    private static final String PLUGIN_ID = "webui";

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private PluginAPI api;
    private WebUILogHandler logHandler;
    private ConfigManager configManager;
    private AuthManager authManager;
    private AuthHandler authHandler;
    private AuthController authController;

    static WebUIPlugin instance;

    private static final int LOG_BUFFER_MAX = 500;
    private static final ConcurrentLinkedDeque<Map<String, Object>> logBuffer = new ConcurrentLinkedDeque<>();
    private static final AtomicLong logPollCounter = new AtomicLong(0);

    // SSE clients using Netty channels
    private static final List<Channel> sseClients = new CopyOnWriteArrayList<>();

    // File cache for static files (path -> content)
    private static final ConcurrentHashMap<Path, byte[]> fileCache = new ConcurrentHashMap<>();

    // Log message translations
    private static final Map<String, String> LOG_TRANSLATIONS = new LinkedHashMap<>();
    static {
        LOG_TRANSLATIONS.put("Connecting to server", "正在连接服务器");
        LOG_TRANSLATIONS.put("Connected to server", "已连接服务器");
        LOG_TRANSLATIONS.put("Disconnected from server", "已从服务器断开");
        LOG_TRANSLATIONS.put("Connection lost", "连接已丢失");
        LOG_TRANSLATIONS.put("Reconnecting", "正在重连");
        LOG_TRANSLATIONS.put("Reconnected", "已重连");
        LOG_TRANSLATIONS.put("Login failed", "登录失败");
        LOG_TRANSLATIONS.put("Login successful", "登录成功");
        LOG_TRANSLATIONS.put("Authenticating", "正在认证");
        LOG_TRANSLATIONS.put("Authentication failed", "认证失败");
        LOG_TRANSLATIONS.put("Queue started", "已进入队列");
        LOG_TRANSLATIONS.put("Queue complete", "队列完成");
        LOG_TRANSLATIONS.put("Queue position", "队列位置");
        LOG_TRANSLATIONS.put("Left queue", "已离开队列");
        LOG_TRANSLATIONS.put("Player joined", "玩家已加入");
        LOG_TRANSLATIONS.put("Player left", "玩家已离开");
        LOG_TRANSLATIONS.put("Now online", "已上线");
        LOG_TRANSLATIONS.put("Now offline", "已离线");
        LOG_TRANSLATIONS.put("Bot started", "机器人已启动");
        LOG_TRANSLATIONS.put("Bot stopped", "机器人已停止");
        LOG_TRANSLATIONS.put("Bot crashed", "机器人已崩溃");
        LOG_TRANSLATIONS.put("Anti-cheat detected", "检测到反作弊");
        LOG_TRANSLATIONS.put("Kicked by server", "被服务器踢出");
        LOG_TRANSLATIONS.put("Banned by server", "被服务器封禁");
        LOG_TRANSLATIONS.put("AutoTotem", "自动图腾");
        LOG_TRANSLATIONS.put("AutoEat", "自动进食");
        LOG_TRANSLATIONS.put("AutoFish", "自动钓鱼");
        LOG_TRANSLATIONS.put("AutoMend", "自动修补");
        LOG_TRANSLATIONS.put("AutoDisconnect", "自动断开");
        LOG_TRANSLATIONS.put("AutoRespawn", "自动重生");
        LOG_TRANSLATIONS.put("AutoReply", "自动回复");
        LOG_TRANSLATIONS.put("Module enabled", "模块已启用");
        LOG_TRANSLATIONS.put("Module disabled", "模块已禁用");
        LOG_TRANSLATIONS.put("Config saved", "配置已保存");
        LOG_TRANSLATIONS.put("Config loaded", "配置已加载");
        LOG_TRANSLATIONS.put("Error", "错误");
        LOG_TRANSLATIONS.put("Warning", "警告");
        LOG_TRANSLATIONS.put("Failed to", "未能");
        LOG_TRANSLATIONS.put("Switching to server", "正在切换服务器");
        LOG_TRANSLATIONS.put("Server switch", "服务器切换");
    }

    private static String translateLogMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        for (var entry : LOG_TRANSLATIONS.entrySet()) {
            if (message.contains(entry.getKey())) {
                message = message.replace(entry.getKey(), entry.getValue());
            }
        }
        return message;
    }

    private static net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY_SERIALIZER =
        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();

    private static String componentToPlainText(net.kyori.adventure.text.Component component) {
        if (component == null) return "";
        return LEGACY_SERIALIZER.serialize(component);
    }

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        instance = this;
        this.api = pluginAPI;

        // Create plugin config directory
        Path pluginDir = Path.of("plugins", PLUGIN_ID);
        configManager = new ConfigManager(pluginDir);
        configManager.loadOrCreate();

        // Initialize auth manager
        authManager = new AuthManager(configManager.getConfig().auth);
        authHandler = new AuthHandler(authManager);
        authController = new AuthController(authManager);

        pluginAPI.registerCommand(new WebUICommand());
        start();
        LOG.info("WebUI plugin loaded! Access at http://localhost:{}", configManager.getConfig().port);
    }

    public void start() {
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
                     p.addLast(new WebUIChannelHandler());
                 }
             })
             .option(ChannelOption.SO_BACKLOG, 128)
             .childOption(ChannelOption.SO_KEEPALIVE, true)
             .childOption(ChannelOption.TCP_NODELAY, true);

            int port = configManager.getConfig().port;
            serverChannel = b.bind(port).sync().channel();
            LOG.info("Netty NIO server started on port {}", port);

            // Register Title packet handler
            try {
                var titleCodec = PacketHandlerCodec.clientBuilder()
                    .setId("webui-title-handler")
                    .setPriority(100)
                    .allStates(PacketHandlerStateCodec.<ClientSession>clientBuilder()
                        .inbound(ClientboundSetTitleTextPacket.class, new TitlePacketHandler())
                        .build())
                    .build();
                PacketCodecRegistries.CLIENT_REGISTRY.register(titleCodec);
                LOG.info("Title packet handler registered");
            } catch (Exception e) {
                LOG.warn("Failed to register Title packet handler", e);
            }

            // Register Logback appender
            logHandler = new WebUILogHandler();
            LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logHandler);
            logHandler.start();

            // Subscribe to events
            EVENT_BUS.subscribe(this, PublicChatEvent.class, e -> {
                try {
                    String name = e.sender() != null ? e.sender().getName() : "Unknown";
                    broadcastLog("CHAT", "Chat", "<" + name + "> " + componentToPlainText(e.component()));
                } catch (Exception ignored) {}
            });
            EVENT_BUS.subscribe(this, SystemChatEvent.class, e -> {
                try {
                    broadcastLog("INFO", "System", componentToPlainText(e.component()));
                } catch (Exception ignored) {}
            });
            EVENT_BUS.subscribe(this, WhisperChatEvent.class, e -> {
                try {
                    String dir = e.outgoing() ? "→" : "←";
                    String from = e.sender() != null ? e.sender().getName() : "Unknown";
                    String to = e.receiver() != null ? e.receiver().getName() : "Unknown";
                    broadcastLog("CHAT", "Whisper", dir + " " + from + " → " + to + ": " + componentToPlainText(e.component()));
                } catch (Exception ignored) {}
            });
            EVENT_BUS.subscribe(this, DeathMessageChatEvent.class, e -> {
                try {
                    broadcastLog("INFO", "Death", componentToPlainText(e.component()));
                } catch (Exception ignored) {}
            });
            EVENT_BUS.subscribe(this, ClientConnectEvent.class, e ->
                broadcastLog("INFO", "Client", "正在连接服务器... / Connecting to server..."));
            EVENT_BUS.subscribe(this, ClientOnlineEvent.class, e ->
                broadcastLog("INFO", "Client", "已上线 / Now online"));
            EVENT_BUS.subscribe(this, ClientDisconnectEvent.class, e ->
                broadcastLog("WARN", "Client", "已断开: " + e.reason() + " / Disconnected: " + e.reason()));
            EVENT_BUS.subscribe(this, QueueStartEvent.class, e ->
                broadcastLog("INFO", "Queue", "已进入队列 / Entered queue"));
            EVENT_BUS.subscribe(this, QueuePositionUpdateEvent.class, e ->
                broadcastLog("INFO", "Queue", "队列位置: " + e.position() + " / Queue position: " + e.position()));
            EVENT_BUS.subscribe(this, QueueCompleteEvent.class, e ->
                broadcastLog("INFO", "Queue", "队列完成，已进入服务器 / Queue complete, entering server"));
        } catch (Exception e) {
            LOG.error("Failed to start WebUI", e);
        }
    }

    public void stop() {
        EVENT_BUS.unsubscribe(this);
        if (logHandler != null) {
            LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logHandler);
            logHandler.stop();
            logHandler = null;
        }
        // Close all SSE clients
        for (Channel ch : sseClients) {
            ch.close();
        }
        sseClients.clear();
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
        instance = null;
    }

    public void broadcastLog(String level, String logger, String message) {
        message = translateLogMessage(message);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("level", level);
        entry.put("logger", logger);
        entry.put("message", message);
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("id", logPollCounter.getAndIncrement());
        logBuffer.addLast(entry);
        // Trim buffer size
        while (logBuffer.size() > LOG_BUFFER_MAX) {
            logBuffer.pollFirst();
        }
        notifySSEClients(level, logger, message);
    }

    private void notifySSEClients(String level, String logger, String message) {
        com.google.gson.JsonObject log = new com.google.gson.JsonObject();
        log.addProperty("level", level);
        log.addProperty("logger", logger);
        log.addProperty("message", message);
        log.addProperty("timestamp", System.currentTimeMillis());
        String json = GSON.toJson(log);
        String sseData = "data: " + json + "\n\n";

        sseClients.removeIf(ch -> {
            if (!ch.isActive()) return true;
            try {
                ByteBuf buf = Unpooled.copiedBuffer(sseData, CharsetUtil.UTF_8);
                DefaultHttpContent content = new DefaultHttpContent(buf);
                ch.writeAndFlush(content).addListener(future -> {
                    if (!future.isSuccess()) {
                        ch.close();
                    }
                });
                return false;
            } catch (Exception e) {
                ch.close();
                return true;
            }
        });
    }

    public static void pushTitle(String text) {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("type", "title");
        data.addProperty("text", text);
        String json = GSON.toJson(data);
        String sseData = "data: " + json + "\n\n";

        sseClients.removeIf(ch -> {
            if (!ch.isActive()) return true;
            try {
                ByteBuf buf = Unpooled.copiedBuffer(sseData, CharsetUtil.UTF_8);
                DefaultHttpContent content = new DefaultHttpContent(buf);
                ch.writeAndFlush(content).addListener(future -> {
                    if (!future.isSuccess()) {
                        ch.close();
                    }
                });
                return false;
            } catch (Exception e) {
                ch.close();
                return true;
            }
        });
    }

    // Netty Channel Handler
    private class WebUIChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
            String uri = req.uri();
            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            String query = uri.contains("?") ? uri.substring(uri.indexOf('?') + 1) : null;
            HttpMethod method = req.method();

            // CORS
            if (HttpMethod.OPTIONS.equals(method)) {
                sendCorsResponse(ctx);
                return;
            }

            // Handle auth endpoints (public)
            if (path.equals("/api/auth/login") && HttpMethod.POST.equals(method)) {
                authController.handleLogin(ctx, req);
                return;
            }
            if (path.equals("/api/auth/logout") && HttpMethod.POST.equals(method)) {
                authController.handleLogout(ctx, req);
                return;
            }
            if (path.equals("/api/auth/status")) {
                authController.handleStatus(ctx, req);
                return;
            }

            // Check authentication for protected resources
            if (!authHandler.checkAuth(req)) {
                // API endpoints return 401
                if (path.startsWith("/api/")) {
                    AuthHandler.sendUnauthorized(ctx, "Authentication required");
                    return;
                }
                // Other paths redirect to login
                AuthHandler.sendRedirect(ctx, "/login.html");
                return;
            }

            // Route to handlers
            try {
                switch (path) {
                    case "/api/status" -> handleStatus(ctx);
                    case "/api/config" -> handleConfig(ctx, req);
                    case "/api/commands" -> handleCommands(ctx, req);
                    case "/api/players" -> handlePlayers(ctx);
                    case "/api/queue" -> handleQueue(ctx);
                    case "/api/logs" -> handleLogsFile(ctx, query);
                    case "/api/logs/live" -> handleLogPoll(ctx, query);
                    case "/api/logs/stream" -> handleLogStream(ctx);
                    case "/api/system" -> handleSystem(ctx);
                    case "/api/latency" -> handleLatency(ctx);
                    case "/api/player" -> handlePlayer(ctx);
                    case "/api/whitelist" -> handleWhitelist(ctx, req);
                    default -> handleStaticFile(ctx, path);
                }
            } catch (Exception e) {
                sendJson(ctx, Map.of("error", e.getMessage()));
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            sseClients.remove(ctx.channel());
        }
    }

    // API Handlers
    private void handleStatus(ChannelHandlerContext ctx) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", Proxy.getInstance().isConnected());
        status.put("inQueue", Proxy.getInstance().isInQueue());
        status.put("queuePosition", Proxy.getInstance().getQueuePosition());
        status.put("version", MC_VERSION);
        status.put("username", CONFIG.authentication.username);
        status.put("proxyAddress", CONFIG.server.getProxyAddress());
        status.put("uptime", System.currentTimeMillis() - Proxy.getInstance().getStartTime());
        sendJson(ctx, status);
    }

    private void handleConfig(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.GET.equals(req.method())) {
            // Return config without sensitive fields
            com.google.gson.JsonObject configJson = GSON.toJsonTree(CONFIG).getAsJsonObject();
            if (configJson.has("authentication")) {
                com.google.gson.JsonObject auth = configJson.getAsJsonObject("authentication");
                auth.remove("password");
                auth.addProperty("password", "");
            }
            sendJson(ctx, configJson);
        } else if (HttpMethod.POST.equals(req.method())) {
            try {
                String body = req.content().toString(CharsetUtil.UTF_8);
                com.google.gson.JsonObject incoming = GSON.fromJson(body, com.google.gson.JsonObject.class);
                if (incoming == null) {
                    sendJson(ctx, Map.of("success", false, "error", "Invalid JSON"));
                    return;
                }
                com.google.gson.JsonObject root;
                Path configPath = CONFIG_FILE.toPath();
                if (Files.exists(configPath)) {
                    root = GSON.fromJson(Files.readString(configPath), com.google.gson.JsonObject.class);
                } else {
                    root = new com.google.gson.JsonObject();
                }
                for (var entry : incoming.entrySet()) {
                    root.add(entry.getKey(), entry.getValue());
                }
                Files.writeString(configPath, GSON.toJson(root));

                // Invalidate all tokens if password was changed
                if (incoming.has("authentication")) {
                    com.google.gson.JsonObject auth = incoming.getAsJsonObject("authentication");
                    if (auth.has("password")) {
                        authManager.invalidateAllTokensForUser(CONFIG.authentication.username);
                    }
                }

                sendJson(ctx, Map.of("success", true, "restartRequired", true));
            } catch (Exception e) {
                sendJson(ctx, Map.of("success", false, "error", e.getMessage()));
            }
        }
    }

    private void handleCommands(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.GET.equals(req.method())) {
            List<String> commands = new ArrayList<>();
            for (var cmd : COMMAND.getCommands()) {
                commands.add(cmd.commandUsage().getName());
            }
            sendJson(ctx, commands);
        } else if (HttpMethod.POST.equals(req.method())) {
            try {
                String body = req.content().toString(CharsetUtil.UTF_8);
                com.google.gson.JsonObject json = GSON.fromJson(body, com.google.gson.JsonObject.class);
                String command = json != null && json.has("command") ? json.get("command").getAsString() : "";
                CommandContext context = CommandContext.create(command, CommandSources.TERMINAL);
                COMMAND.execute(context);

                List<String> output = new ArrayList<>();
                var embed = context.getEmbed();
                if (embed != null) {
                    if (embed.title() != null) output.add(embed.title());
                    if (embed.description() != null) output.add(embed.description());
                    var fields = embed.fields();
                    if (fields != null) {
                        for (var field : fields) {
                            String name = field.name();
                            String value = field.value();
                            if (name != null && !name.isEmpty()) output.add(name + ": " + value);
                            else if (value != null) output.add(value);
                        }
                    }
                }
                var ml = context.getMultiLineOutput();
                if (ml != null && !ml.isEmpty()) output.addAll(ml);

                sendJson(ctx, Map.of("success", true, "output", output));
            } catch (Exception e) {
                sendJson(ctx, Map.of("success", false, "error", e.getMessage()));
            }
        }
    }

    private void handlePlayers(ChannelHandlerContext ctx) {
        List<Map<String, Object>> players = new ArrayList<>();
        var currentPlayer = Proxy.getInstance().getCurrentPlayer().get();
        if (currentPlayer != null) {
            players.add(Map.of("name", currentPlayer.getName(), "uuid", currentPlayer.getUUID().toString(), "type", "controller"));
        }
        Proxy.getInstance().getActiveConnections().forEach(session ->
            players.add(Map.of("name", session.getName(), "uuid", session.getUUID().toString(), "type", "spectator"))
        );
        sendJson(ctx, players);
    }

    private void handleQueue(ChannelHandlerContext ctx) {
        Map<String, Object> queue = new LinkedHashMap<>();
        queue.put("inQueue", Proxy.getInstance().isInQueue());
        queue.put("position", Proxy.getInstance().getQueuePosition());
        sendJson(ctx, queue);
    }

    private void handleLogsFile(ChannelHandlerContext ctx, String query) {
        if (query != null && query.contains("file=")) {
            String fileName = query.replace("file=", "").trim();
            if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                sendJson(ctx, Map.of("success", false, "error", "Invalid filename"));
                return;
            }
            Path file = LOGS_DIR.resolve(fileName);
            if (!Files.exists(file) || Files.isDirectory(file)) {
                sendJson(ctx, Map.of("success", false, "error", "File not found"));
                return;
            }
            sendFile(ctx, file, fileName);
        } else {
            List<Map<String, Object>> files = new ArrayList<>();
            if (Files.exists(LOGS_DIR) && Files.isDirectory(LOGS_DIR)) {
                try (var stream = Files.list(LOGS_DIR)) {
                    stream.filter(p -> !Files.isDirectory(p)).sorted((a, b) -> {
                        try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                        catch (IOException e) { return 0; }
                    }).forEach(p -> {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("name", p.getFileName().toString());
                        try { info.put("size", Files.size(p)); } catch (IOException e) { info.put("size", 0); }
                        try { info.put("lastModified", Files.getLastModifiedTime(p).toMillis()); } catch (IOException e) { info.put("lastModified", 0); }
                        files.add(info);
                    });
                } catch (IOException e) {
                    // ignore
                }
            }
            sendJson(ctx, files);
        }
    }

    private void handleLogPoll(ChannelHandlerContext ctx, String query) {
        long afterId = -1;
        if (query != null && query.startsWith("after=")) {
            try { afterId = Long.parseLong(query.substring(6)); } catch (NumberFormatException ignored) {}
        }
        List<Map<String, Object>> result = new ArrayList<>();
        // ConcurrentLinkedDeque iterator is weakly consistent, no sync needed
        for (Map<String, Object> entry : logBuffer) {
            long id = (long) entry.get("id");
            if (id > afterId) result.add(entry);
        }
        sendJson(ctx, Map.of("logs", result, "counter", logPollCounter.get()));
    }

    private void handleLogStream(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        ctx.writeAndFlush(response);

        sseClients.add(ctx.channel());
    }

    private void handleSystem(ChannelHandlerContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();

        // CPU
        double cpuLoad = -1;
        try {
            java.lang.management.OperatingSystemMXBean osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            cpuLoad = osBean.getSystemLoadAverage();
            if (cpuLoad >= 0) cpuLoad = Math.round(cpuLoad * 100.0) / 100.0;
        } catch (Exception ignored) {}
        data.put("cpuLoad", cpuLoad);

        // Memory
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.maxMemory();
        long freeMem = rt.freeMemory();
        long usedMem = rt.totalMemory() - freeMem;
        double memUsedMB = Math.round(usedMem / 1048576.0 * 10.0) / 10.0;
        double memTotalMB = Math.round(totalMem / 1048576.0 * 10.0) / 10.0;
        double memPercent = Math.round(usedMem * 10000.0 / totalMem) / 100.0;
        data.put("memoryUsedMB", memUsedMB);
        data.put("memoryTotalMB", memTotalMB);
        data.put("memoryPercent", memPercent);

        // Network
        long now = System.currentTimeMillis();
        long totalBytesIn = 0;
        long totalBytesOut = 0;
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/net/dev"));
            for (String line : lines) {
                line = line.trim();
                if (line.contains(":") && !line.startsWith("Inter") && !line.startsWith("face")) {
                    String[] parts = line.split(":");
                    if (parts.length >= 2) {
                        String[] stats = parts[1].trim().split("\\s+");
                        if (stats.length >= 10) {
                            totalBytesIn += Long.parseLong(stats[0]);
                            totalBytesOut += Long.parseLong(stats[8]);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        long deltaIn = 0, deltaOut = 0;
        double bytesPerSecIn = 0, bytesPerSecOut = 0;
        if (lastNetworkTime > 0) {
            double elapsed = (now - lastNetworkTime) / 1000.0;
            if (elapsed > 0) {
                deltaIn = totalBytesIn - lastBytesIn;
                deltaOut = totalBytesOut - lastBytesOut;
                bytesPerSecIn = Math.round(deltaIn / elapsed * 10.0) / 10.0;
                bytesPerSecOut = Math.round(deltaOut / elapsed * 10.0) / 10.0;
            }
        }
        lastNetworkTime = now;
        lastBytesIn = totalBytesIn;
        lastBytesOut = totalBytesOut;

        data.put("netBytesPerSecIn", bytesPerSecIn);
        data.put("netBytesPerSecOut", bytesPerSecOut);
        data.put("netTotalBytesIn", totalBytesIn);
        data.put("netTotalBytesOut", totalBytesOut);
        data.put("threadCount", Thread.activeCount());

        sendJson(ctx, data);
    }

    private static volatile long lastNetworkTime = 0;
    private static volatile long lastBytesIn = 0;
    private static volatile long lastBytesOut = 0;

    private void handleLatency(ChannelHandlerContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            var client = Proxy.getInstance().getClient();
            boolean connected = client != null && client.isConnected();
            long botLatency = connected ? client.getPing() : -1;
            data.put("connected", connected);
            data.put("botLatency", botLatency);

            List<Map<String, Object>> players = new ArrayList<>();
            for (var session : Proxy.getInstance().getActiveConnections().getArray()) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("name", session.getName());
                p.put("uuid", session.getUUID().toString());
                p.put("ping", session.getPing());
                players.add(p);
            }
            data.put("players", players);
        } catch (Exception e) {
            data.put("connected", false);
            data.put("botLatency", -1);
            data.put("players", new ArrayList<>());
        }
        sendJson(ctx, data);
    }

    private void handlePlayer(ChannelHandlerContext ctx) {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            var client = Proxy.getInstance().getClient();
            boolean connected = client != null && client.isConnected();
            data.put("connected", connected);

            if (connected) {
                var playerCache = CACHE.getPlayerCache();
                var thePlayer = playerCache.getThePlayer();
                if (thePlayer != null) {
                    data.put("health", thePlayer.getHealth());
                    data.put("food", thePlayer.getFood());
                    data.put("saturation", thePlayer.getSaturation());
                } else {
                    data.put("health", null);
                    data.put("food", null);
                    data.put("saturation", null);
                }
            } else {
                data.put("health", null);
                data.put("food", null);
                data.put("saturation", null);
            }
        } catch (Exception e) {
            data.put("connected", false);
            data.put("health", null);
            data.put("food", null);
            data.put("saturation", null);
        }
        sendJson(ctx, data);
    }

    private void handleWhitelist(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.GET.equals(req.method())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", CONFIG.server.extra.whitelist.enable);
            data.put("kickmsg", CONFIG.server.extra.whitelist.kickmsg);
            data.put("autoAddClient", CONFIG.server.extra.whitelist.autoAddClient);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (var entry : CONFIG.server.extra.whitelist.whitelist) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("username", entry.getUsername());
                e.put("uuid", entry.getUuid().toString());
                entries.add(e);
            }
            data.put("whitelist", entries);
            sendJson(ctx, data);
        } else if (HttpMethod.POST.equals(req.method())) {
            try {
                String body = req.content().toString(CharsetUtil.UTF_8);
                com.google.gson.JsonObject json = GSON.fromJson(body, com.google.gson.JsonObject.class);
                String action = json.get("action").getAsString();
                switch (action) {
                    case "toggle" -> {
                        CONFIG.server.extra.whitelist.enable = json.get("enabled").getAsBoolean();
                        sendJson(ctx, Map.of("success", true, "enabled", CONFIG.server.extra.whitelist.enable));
                    }
                    case "add" -> {
                        String username = json.get("username").getAsString();
                        var result = PLAYER_LISTS.getWhitelist().add(username);
                        if (result.isPresent()) {
                            sendJson(ctx, Map.of("success", true, "username", result.get().getUsername(), "uuid", result.get().getUuid().toString()));
                        } else {
                            sendJson(ctx, Map.of("success", false, "error", "无法解析用户名 / Could not resolve username"));
                        }
                    }
                    case "remove" -> {
                        String username = json.get("username").getAsString();
                        PLAYER_LISTS.getWhitelist().remove(username);
                        sendJson(ctx, Map.of("success", true));
                    }
                    case "updateConfig" -> {
                        if (json.has("kickmsg")) CONFIG.server.extra.whitelist.kickmsg = json.get("kickmsg").getAsString();
                        if (json.has("autoAddClient")) CONFIG.server.extra.whitelist.autoAddClient = json.get("autoAddClient").getAsBoolean();
                        sendJson(ctx, Map.of("success", true));
                    }
                    default -> sendJson(ctx, Map.of("success", false, "error", "Unknown action: " + action));
                }
            } catch (Exception e) {
                sendJson(ctx, Map.of("success", false, "error", e.getMessage()));
            }
        }
    }

    private static final Path LOGS_DIR = Path.of("logs");

    private void handleStaticFile(ChannelHandlerContext ctx, String path) {
        if ("/".equals(path)) path = "/index.html";

        // Path traversal prevention
        if (!AuthHandler.isSafePath(path)) {
            sendNotFound(ctx);
            return;
        }

        // Try classpath first
        byte[] content = loadResource(path);
        if (content == null) {
            // Try plugin directory
            Path webDir = configManager.getConfigDir().resolve("web");
            content = loadFile(webDir.resolve(path.substring(1)));
        }

        if (content != null) {
            sendBytes(ctx, content, getContentType(path));
        } else {
            // SPA fallback
            content = loadResource("/index.html");
            if (content == null) {
                Path webDir = configManager.getConfigDir().resolve("web");
                content = loadFile(webDir.resolve("index.html"));
            }
            if (content != null) {
                sendBytes(ctx, content, "text/html");
            } else {
                sendNotFound(ctx);
            }
        }
    }

    private byte[] loadResource(String path) {
        if (path.startsWith("/")) path = path.substring(1);
        try (var is = getClass().getResourceAsStream("/web/" + path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] loadFile(Path path) {
        return fileCache.computeIfAbsent(path, p -> {
            try {
                if (Files.exists(p) && !Files.isDirectory(p)) {
                    return Files.readAllBytes(p);
                }
            } catch (Exception ignored) {}
            return null;
        });
    }

    // NIO-based file sending with zero-copy
    private void sendFile(ChannelHandlerContext ctx, Path file, String fileName) {
        try {
            long fileSize = Files.size(file);
            String ct = "text/plain";
            String nameLower = fileName.toLowerCase();
            if (nameLower.endsWith(".gz")) ct = "application/gzip";
            else if (nameLower.endsWith(".zip")) ct = "application/zip";

            HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, ct);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileSize);
            response.headers().set(HttpHeaderNames.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
            ctx.write(response);

            // Use FileRegion for zero-copy transfer
            FileChannel fileChannel = FileChannel.open(file, StandardOpenOption.READ);
            DefaultFileRegion region = new DefaultFileRegion(fileChannel, 0, fileSize);
            ctx.write(region).addListener(future -> {
                fileChannel.close();
                if (!future.isSuccess()) {
                    ctx.close();
                }
            });
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        } catch (Exception e) {
            sendNotFound(ctx);
        }
    }

    private void sendBytes(ChannelHandlerContext ctx, byte[] content, String contentType) {
        ByteBuf buf = Unpooled.wrappedBuffer(content);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        ctx.write(response);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void sendNotFound(ChannelHandlerContext ctx) {
        String notFound = "404 Not Found";
        ByteBuf buf = Unpooled.copiedBuffer(notFound, CharsetUtil.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ctx.write(response);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void sendJson(ChannelHandlerContext ctx, Object obj) {
        byte[] bytes = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.write(httpResponse);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void sendCorsResponse(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    // Command
    private class WebUICommand extends Command {
        @Override
        public CommandUsage commandUsage() {
            return CommandUsage.builder().name("webui").category(CommandCategory.MANAGE)
                .description("控制WebUI服务器").usageLines("enable", "disable", "status").build();
        }
        @Override
        public com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandContext> register() {
            return literal("webui")
                .then(literal("enable").executes(ctx -> { start(); ctx.getSource().getEmbed().title("WebUI Enabled").description("http://localhost:" + configManager.getConfig().port); return 1; }))
                .then(literal("disable").executes(ctx -> { stop(); ctx.getSource().getEmbed().title("WebUI Disabled"); return 1; }))
                .then(literal("status").executes(ctx -> {
                    String status = (serverChannel != null && serverChannel.isActive()) ? "Running" : "Stopped";
                    ctx.getSource().getEmbed().title("WebUI Status").description("状态: " + status + "\nHTTP: http://localhost:" + configManager.getConfig().port);
                    return 1;
                }))
                .executes(ctx -> { ctx.getSource().getEmbed().title("WebUI").description("用法: webui enable/disable/status"); return 1; });
        }
    }

    private static com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    // Logback Appender
    private static class WebUILogHandler extends UnsynchronizedAppenderBase<ILoggingEvent> {
        private static final String[] IGNORED_PREFIXES = {
            "com.sun.net.httpserver",
            "jdk.httpserver"
        };

        @Override
        protected void append(ILoggingEvent event) {
            if (instance == null) return;
            if (event.getLevel() == ch.qos.logback.classic.Level.DEBUG) return;
            String logger = event.getLoggerName();
            if (logger == null || logger.isEmpty()) logger = "root";
            for (String prefix : IGNORED_PREFIXES) {
                if (logger.startsWith(prefix)) return;
            }
            String level = event.getLevel().toString();
            String message = event.getFormattedMessage().replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "");
            IThrowableProxy tp = event.getThrowableProxy();
            if (tp != null) {
                message += " " + tp.getClassName() + (tp.getMessage() != null ? ": " + tp.getMessage() : "");
            }
            instance.broadcastLog(level, logger, message);
        }
    }
}

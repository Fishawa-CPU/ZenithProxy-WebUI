package com.zenith.webui;

import com.zenith.event.chat.*;
import com.zenith.event.client.ClientConnectEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.event.client.ClientOnlineEvent;
import com.zenith.event.queue.*;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketCodecRegistries;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import com.zenith.webui.api.*;
import com.zenith.webui.auth.AuthController;
import com.zenith.webui.auth.AuthHandler;
import com.zenith.webui.auth.AuthManager;
import com.zenith.webui.command.WebUICommand;
import com.zenith.webui.config.ConfigManager;
import com.zenith.webui.http.HttpHelper;
import com.zenith.webui.terminal.LogTranslator;
import com.zenith.webui.terminal.WebUILogHandler;
import com.zenith.webui.queue.QueueParser;
import com.zenith.webui.server.WebUIChannelHandler;
import com.zenith.webui.server.WebUIServer;
import com.zenith.webui.staticfile.StaticFileHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.util.CharsetUtil;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundClearTitlesPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;

import static com.zenith.Globals.*;

/**
 * WebUI plugin — thin orchestrator that wires together all sub-components.
 *
 * <h3>Package map</h3>
 * <pre>
 * server/      — Netty HTTP server bootstrap + channel request routing
 * http/        — Static HTTP response helpers + shared Gson instance
 * api/         — Endpoint handlers: commands, dashboard, log-stream, whitelist
 * log/         — Log message translation + Logback SSE appender
 * staticfile/  — Static file serving with bounded file cache
 * command/     — /webui enable|disable|status command
 * auth/        — Token-based auth (login/logout/status, cookie + Bearer)
 * config/      — Plugin config JSON I/O (auto-generates password on first run)
 * queue/       — 3c3u.org / 2b2t.org queue-position packet parsing
 * </pre>
 */
public class WebUIPlugin implements ZenithProxyPlugin {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI");
    private static final String PLUGIN_ID = "webui";

    // ── Singleton instance ────────────────────────────────────
    public static volatile WebUIPlugin instance;

    // ── Shared mutable state (accessed cross-package) ─────────
    static final int LOG_BUFFER_MAX = 500;
    public static final ConcurrentLinkedDeque<Map<String, Object>> logBuffer = new ConcurrentLinkedDeque<>();
    static final AtomicLong logPollCounter = new AtomicLong(0);
    /** Approximate log buffer size counter — avoids O(n) {@code ConcurrentLinkedDeque.size()}. */
    public static final AtomicInteger logBufferApproxSize = new AtomicInteger(0);

    public static final List<Channel> sseClients = new CopyOnWriteArrayList<>();
    public static final List<Channel> dashboardSseClients = new CopyOnWriteArrayList<>();

    // ── Component graph ───────────────────────────────────────
    private ConfigManager configManager;
    private AuthManager authManager;
    private AuthHandler authHandler;
    private AuthController authController;
    private DashboardHandler dashboardHandler;
    private WebUIServer server;
    private WebUILogHandler logHandler;

    // ── Adventure serialiser ──────────────────────────────────
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
        LegacyComponentSerializer.legacySection();

    private static String componentToPlainText(net.kyori.adventure.text.Component c) {
        return c == null ? "" : LEGACY_SERIALIZER.serialize(c);
    }

    // ═══════════════════════════════════════════════════════════
    //  Plugin lifecycle
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onLoad(PluginAPI api) {
        instance = this;

        // Config
        Path pluginDir = Path.of("plugins", PLUGIN_ID);
        configManager = new ConfigManager(pluginDir);
        configManager.loadOrCreate();

        // Auth
        authManager = new AuthManager(configManager.getConfig().auth);
        authHandler = new AuthHandler(authManager, configManager.getConfig());
        authController = new AuthController(authManager);

        // Handlers
        dashboardHandler = new DashboardHandler();
        WebUIChannelHandler channelHandler = new WebUIChannelHandler(
            authHandler, authController,
            new CommandHandler(),
            dashboardHandler,
            new LogStreamHandler(),
            new WhitelistHandler(),
            new StaticFileHandler(pluginDir));

        // Server
        int port = configManager.getConfig().port;
        server = new WebUIServer(port, channelHandler);

        // Command
        api.registerCommand(new WebUICommand(
            this::start, this::stop, () -> server != null && server.isRunning(), port));

        // Go
        boolean ok = start();
        LOG.info("WebUI plugin loaded!{}",
            ok ? " Access at http://localhost:" + port : " (server failed to start, check logs)");
    }

    // ═══════════════════════════════════════════════════════════
    //  Start / Stop  (public — also driven by /webui command)
    // ═══════════════════════════════════════════════════════════

    /** Full start: server → packet handlers → log appender → events → dashboard stream. */
    public boolean start() {
        instance = this;
        if (!server.start()) return false;

        registerPacketHandlers();
        registerLogAppender();
        subscribeEvents();
        dashboardHandler.startStream();
        return true;
    }

    /** Full stop: events → dashboard stream → log appender → server. */
    public void stop() {
        EVENT_BUS.unsubscribe(this);
        dashboardHandler.stopStream();
        detachLogAppender();
        server.stop();
        if (authManager != null) authManager.shutdown();
        instance = null;
    }

    // ═══════════════════════════════════════════════════════════
    //  Startup helpers
    // ═══════════════════════════════════════════════════════════

    private void registerPacketHandlers() {
        try {
            var c = PacketHandlerCodec.clientBuilder()
                .setId("webui-title-handler").setPriority(100)
                .allStates(PacketHandlerStateCodec.<ClientSession>clientBuilder()
                    .inbound(ClientboundSetTitleTextPacket.class, new TitlePacketHandler()).build())
                .build();
            PacketCodecRegistries.CLIENT_REGISTRY.register(c);
            LOG.info("Title packet handler registered");
        } catch (Exception e) { LOG.warn("Failed to register Title packet handler", e); }

        try {
            var c = PacketHandlerCodec.clientBuilder()
                .setId("webui-queue-parser").setPriority(100)
                .allStates(PacketHandlerStateCodec.<ClientSession>clientBuilder()
                    .inbound(ClientboundSetSubtitleTextPacket.class, new QueueParser())
                    .inbound(ClientboundClearTitlesPacket.class, new QueueParser.ClearHandler()).build())
                .build();
            PacketCodecRegistries.CLIENT_REGISTRY.register(c);
            LOG.info("Queue parser registered");
        } catch (Exception e) { LOG.warn("Failed to register Queue parser", e); }
    }

    private void registerLogAppender() {
        logHandler = new WebUILogHandler();
        LoggerContext ctx = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logHandler);
        logHandler.start();
    }

    private void detachLogAppender() {
        if (logHandler == null) return;
        LoggerContext ctx = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logHandler);
        logHandler.stop();
        logHandler = null;
    }

    private void subscribeEvents() {
        EVENT_BUS.subscribe(this, PublicChatEvent.class, e -> {
            try {
                broadcastLog("CHAT", "Chat",
                    "<" + (e.sender() != null ? e.sender().getName() : "Unknown") + "> "
                    + componentToPlainText(e.component()));
            } catch (Exception ignored) {}
        });
        EVENT_BUS.subscribe(this, SystemChatEvent.class, e -> {
            try { broadcastLog("INFO", "System", componentToPlainText(e.component())); }
            catch (Exception ignored) {}
        });
        EVENT_BUS.subscribe(this, WhisperChatEvent.class, e -> {
            try {
                broadcastLog("CHAT", "Whisper",
                    (e.outgoing() ? "→" : "←") + " "
                    + (e.sender() != null ? e.sender().getName() : "Unknown") + " → "
                    + (e.receiver() != null ? e.receiver().getName() : "Unknown") + ": "
                    + componentToPlainText(e.component()));
            } catch (Exception ignored) {}
        });
        EVENT_BUS.subscribe(this, DeathMessageChatEvent.class, e -> {
            try { broadcastLog("INFO", "Death", componentToPlainText(e.component())); }
            catch (Exception ignored) {}
        });
        EVENT_BUS.subscribe(this, ClientConnectEvent.class, e ->
            broadcastLog("INFO", "Client", "正在连接服务器... / Connecting to server..."));
        EVENT_BUS.subscribe(this, ClientOnlineEvent.class, e -> {
            QueueParser.reset();
            broadcastLog("INFO", "Client", "已上线 / Now online");
        });
        EVENT_BUS.subscribe(this, ClientDisconnectEvent.class, e -> {
            QueueParser.reset();
            broadcastLog("WARN", "Client",
                "已断开: " + e.reason() + " / Disconnected: " + e.reason());
        });
        EVENT_BUS.subscribe(this, QueueStartEvent.class, e ->
            broadcastLog("INFO", "Queue", "已进入队列 / Entered queue"));
        EVENT_BUS.subscribe(this, QueuePositionUpdateEvent.class, e ->
            broadcastLog("INFO", "Queue",
                "队列位置: " + e.position() + " / Queue position: " + e.position()));
        EVENT_BUS.subscribe(this, QueueCompleteEvent.class, e -> {
            QueueParser.reset();
            broadcastLog("INFO", "Queue", "队列完成，已进入服务器 / Queue complete, entering server");
        });
    }

    // ═══════════════════════════════════════════════════════════
    //  Log broadcasting
    // ═══════════════════════════════════════════════════════════

    /** Called by {@link WebUILogHandler} and event subscribers. */
    public void broadcastLog(String level, String logger, String message) {
        message = LogTranslator.translate(message);
        long ts = System.currentTimeMillis();

        // Manual JSON serialization + SSE frame in one pass — avoids Gson/JsonObject allocation
        String sseFrame = buildSseFrame(level, logger, message, ts);

        Map<String, Object> entry = Map.of(
            "level", level, "logger", logger, "message", message,
            "timestamp", ts, "id", logPollCounter.getAndIncrement(),
            "sseFrame", sseFrame);
        logBuffer.addLast(entry);
        // Use counter instead of O(n) ConcurrentLinkedDeque.size()
        if (logBufferApproxSize.incrementAndGet() > LOG_BUFFER_MAX) {
            logBuffer.pollFirst();
            logBufferApproxSize.decrementAndGet();
        }
        broadcastSSE(sseClients, sseFrame);
    }

    /** Push a title packet to log-stream SSE clients. Called by {@link TitlePacketHandler}. */
    public static void pushTitle(String text) {
        StringBuilder sb = new StringBuilder(64 + text.length());
        sb.append("data: {\"type\":\"title\",\"text\":\"");
        escapeJson(sb, text);
        sb.append("\"}\n\n");
        broadcastSSE(sseClients, sb.toString());
    }

    /**
     * Write a pre-built SSE data frame to every active channel in {@code clients}.
     * Uses retainedDuplicate to avoid per-channel ByteBuf allocation.
     * @param sseFrame pre-built SSE frame string (format: "data: {...}\n\n")
     */
    /**
     * Write a pre-built SSE data frame to every active channel in {@code clients}.
     * Collects dead channels for batch removal — avoids CopyOnWriteArrayList.removeIf
     * which copies the entire array even when nothing is removed.
     * @param sseFrame pre-built SSE frame string (format: "data: {...}\n\n")
     */
    public static void broadcastSSE(List<Channel> clients, String sseFrame) {
        ByteBuf template = Unpooled.copiedBuffer(sseFrame, CharsetUtil.UTF_8);
        List<Channel> dead = new ArrayList<>(2);
        for (Channel ch : clients) {
            if (!ch.isActive()) {
                dead.add(ch);
                continue;
            }
            try {
                ch.writeAndFlush(new DefaultHttpContent(template.retainedDuplicate()))
                    .addListener(f -> { if (!f.isSuccess()) ch.close(); });
            } catch (Exception e) {
                ch.close();
                dead.add(ch);
            }
        }
        if (!dead.isEmpty()) {
            clients.removeAll(dead);
        }
        template.release();
    }

    // ═══════════════════════════════════════════════════════════
    //  JSON/SSE helpers (zero-allocation serialization)
    // ═══════════════════════════════════════════════════════════

    /** Build an SSE data frame with manual JSON serialization to avoid Gson/JsonObject allocation. */
    private static String buildSseFrame(String level, String logger, String message, long ts) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("data: {\"level\":\"");
        escapeJson(sb, level);
        sb.append("\",\"logger\":\"");
        escapeJson(sb, logger);
        sb.append("\",\"message\":\"");
        escapeJson(sb, message);
        sb.append("\",\"timestamp\":").append(ts).append("}\n\n");
        return sb.toString();
    }

    /** Minimal JSON string escaping — handles characters that break JSON strings. */
    private static void escapeJson(StringBuilder sb, String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default:   sb.append(c);
            }
        }
    }
}

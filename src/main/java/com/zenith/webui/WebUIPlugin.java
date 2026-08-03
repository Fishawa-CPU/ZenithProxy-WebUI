package com.zenith.webui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandSources;
import com.zenith.command.api.CommandUsage;
import com.zenith.module.api.Module;
import com.zenith.plugin.api.PluginAPI;
import com.zenith.plugin.api.ZenithProxyPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

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

import static com.zenith.Globals.*;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;

public class WebUIPlugin implements ZenithProxyPlugin {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI");
    private static final Path CONFIG_FILE = Path.of("config.json");
    private static int httpPort = 8080;
    private HttpServer httpServer;
    private PluginAPI api;
    private WebUILogHandler logHandler;

    // Static reference for log handler
    static WebUIPlugin instance;

    // Log buffer for HTTP polling
    private static final int LOG_BUFFER_MAX = 500;
    private static final LinkedList<Map<String, Object>> logBuffer = new LinkedList<>();
    private static long logPollCounter = 0;

    // SSE clients
    private static final List<HttpExchange> sseClients = new CopyOnWriteArrayList<>();

    // Log message translations (English -> Chinese)
    private static final Map<String, String> LOG_TRANSLATIONS = new LinkedHashMap<>();
    static {
        // Connection
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
        // Queue
        LOG_TRANSLATIONS.put("Queue started", "已进入队列");
        LOG_TRANSLATIONS.put("Queue complete", "队列完成");
        LOG_TRANSLATIONS.put("Queue position", "队列位置");
        LOG_TRANSLATIONS.put("Left queue", "已离开队列");
        // Player
        LOG_TRANSLATIONS.put("Player joined", "玩家已加入");
        LOG_TRANSLATIONS.put("Player left", "玩家已离开");
        LOG_TRANSLATIONS.put("Now online", "已上线");
        LOG_TRANSLATIONS.put("Now offline", "已离线");
        // Bot
        LOG_TRANSLATIONS.put("Bot started", "机器人已启动");
        LOG_TRANSLATIONS.put("Bot stopped", "机器人已停止");
        LOG_TRANSLATIONS.put("Bot crashed", "机器人已崩溃");
        // Anti-cheat
        LOG_TRANSLATIONS.put("Anti-cheat detected", "检测到反作弊");
        LOG_TRANSLATIONS.put("Kicked by server", "被服务器踢出");
        LOG_TRANSLATIONS.put("Banned by server", "被服务器封禁");
        // Auto actions
        LOG_TRANSLATIONS.put("AutoTotem", "自动图腾");
        LOG_TRANSLATIONS.put("AutoEat", "自动进食");
        LOG_TRANSLATIONS.put("AutoFish", "自动钓鱼");
        LOG_TRANSLATIONS.put("AutoMend", "自动修补");
        LOG_TRANSLATIONS.put("AutoDisconnect", "自动断开");
        LOG_TRANSLATIONS.put("AutoRespawn", "自动重生");
        LOG_TRANSLATIONS.put("AutoReply", "自动回复");
        // Modules
        LOG_TRANSLATIONS.put("Module enabled", "模块已启用");
        LOG_TRANSLATIONS.put("Module disabled", "模块已禁用");
        // Config
        LOG_TRANSLATIONS.put("Config saved", "配置已保存");
        LOG_TRANSLATIONS.put("Config loaded", "配置已加载");
        // Errors
        LOG_TRANSLATIONS.put("Error", "错误");
        LOG_TRANSLATIONS.put("Warning", "警告");
        LOG_TRANSLATIONS.put("Failed to", "未能");
        // Server switch
        LOG_TRANSLATIONS.put("Switching to server", "正在切换服务器");
        LOG_TRANSLATIONS.put("Server switch", "服务器切换");
    }

    private static String translateLogMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        for (var entry : LOG_TRANSLATIONS.entrySet()) {
            if (message.contains(entry.getKey())) {
                // Replace English with Chinese, keep the rest
                message = message.replace(entry.getKey(), entry.getValue());
            }
        }
        return message;
    }

    // Convert Adventure Component to plain text (no ANSI codes)
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
        loadOrCreateConfig();
        pluginAPI.registerCommand(new WebUICommand());
        start();
        LOG.info("WebUI plugin loaded! Access at http://localhost:{}", httpPort);
    }

    private void loadOrCreateConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String content = Files.readString(CONFIG_FILE);
                com.google.gson.JsonObject root = GSON.fromJson(content, com.google.gson.JsonObject.class);
                if (root != null && root.has("webUI")) {
                    com.google.gson.JsonObject webUI = root.getAsJsonObject("webUI");
                    if (webUI.has("port")) httpPort = webUI.get("port").getAsInt();
                    LOG.info("Loaded WebUI config: http={}", httpPort);
                    return;
                }
                // config.json exists but no webUI section — add it
                root.add("webUI", defaultWebUIConfig());
                Files.writeString(CONFIG_FILE, GSON.toJson(root));
                LOG.info("Added default webUI config to existing config.json");
            } else {
                // no config.json at all — create with defaults
                com.google.gson.JsonObject root = new com.google.gson.JsonObject();
                root.add("webUI", defaultWebUIConfig());
                Files.writeString(CONFIG_FILE, GSON.toJson(root));
                LOG.info("Created config.json with default webUI config");
            }
        } catch (Exception e) {
            LOG.error("Failed to load/create config, using defaults", e);
        }
    }

    private com.google.gson.JsonObject defaultWebUIConfig() {
        com.google.gson.JsonObject webUI = new com.google.gson.JsonObject();
        webUI.addProperty("enabled", true);
        webUI.addProperty("port", 8080);
        return webUI;
    }

    private static void saveModuleState(String moduleName, boolean enabled) {
        try {
            com.google.gson.JsonObject root;
            if (Files.exists(CONFIG_FILE)) {
                root = GSON.fromJson(Files.readString(CONFIG_FILE), com.google.gson.JsonObject.class);
            } else {
                root = new com.google.gson.JsonObject();
            }
            if (!root.has("modules")) root.add("modules", new com.google.gson.JsonObject());
            root.getAsJsonObject("modules").addProperty(moduleName, enabled);
            Files.writeString(CONFIG_FILE, GSON.toJson(root));
        } catch (Exception e) {
            LOG.error("Failed to save module state: {}", moduleName, e);
        }
    }

    private static void loadModuleStates() {
        try {
            if (!Files.exists(CONFIG_FILE)) return;
            com.google.gson.JsonObject root = GSON.fromJson(Files.readString(CONFIG_FILE), com.google.gson.JsonObject.class);
            if (root == null || !root.has("modules")) return;
            com.google.gson.JsonObject modules = root.getAsJsonObject("modules");
            for (Module module : MODULE.getModules().toArray(new Module[0])) {
                String name = module.getClass().getSimpleName();
                if (modules.has(name)) {
                    boolean enabled = modules.get(name).getAsBoolean();
                    module.setEnabled(enabled);
                    LOG.info("Module {} set to {}", name, enabled ? "enabled" : "disabled");
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load module states", e);
        }
    }

    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);
            httpServer.setExecutor(Executors.newFixedThreadPool(10));

            httpServer.createContext("/api/status", new StatusHandler());
            httpServer.createContext("/api/config", new ConfigHandler());
            httpServer.createContext("/api/modules", new ModulesHandler());
            httpServer.createContext("/api/commands", new CommandsHandler());
            httpServer.createContext("/api/players", new PlayersHandler());
            httpServer.createContext("/api/queue", new QueueHandler());
            httpServer.createContext("/api/logs", new LogsFileHandler());
            httpServer.createContext("/api/logs/live", new LogPollHandler());
            httpServer.createContext("/api/logs/stream", new LogStreamHandler());
            httpServer.createContext("/api/system", new SystemHandler());
            httpServer.createContext("/api/latency", new LatencyHandler());
            httpServer.createContext("/api/whitelist", new WhitelistHandler());
            httpServer.createContext("/", new StaticFileHandler());

            httpServer.start();

            // Register Logback appender to capture all SLF4J logs
            logHandler = new WebUILogHandler();
            LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(logHandler);
            logHandler.start();

            // Subscribe to ZenithProxy events
            EVENT_BUS.subscribe(this, PublicChatEvent.class, e ->
                broadcastLog("CHAT", "Chat", "<" + e.sender().getName() + "> " + componentToPlainText(e.component())));
            EVENT_BUS.subscribe(this, SystemChatEvent.class, e ->
                broadcastLog("INFO", "System", componentToPlainText(e.component())));
            EVENT_BUS.subscribe(this, WhisperChatEvent.class, e -> {
                String dir = e.outgoing() ? "→" : "←";
                broadcastLog("CHAT", "Whisper", dir + " " + e.sender().getName() + " → " + e.receiver().getName() + ": " + componentToPlainText(e.component()));
            });
            EVENT_BUS.subscribe(this, DeathMessageChatEvent.class, e ->
                broadcastLog("INFO", "Death", componentToPlainText(e.component())));
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

            loadModuleStates();
        } catch (Exception e) {
            LOG.error("Failed to start WebUI", e);
        }
    }

    public void stop() {
        // Unsubscribe from events
        EVENT_BUS.unsubscribe(this);
        if (logHandler != null) {
            LoggerContext context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(logHandler);
            logHandler.stop();
            logHandler = null;
        }
        if (httpServer != null) httpServer.stop(0);
        instance = null;
    }

    public void broadcastLog(String level, String logger, String message) {
        message = translateLogMessage(message);
        synchronized (logBuffer) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("level", level);
            entry.put("logger", logger);
            entry.put("message", message);
            entry.put("timestamp", System.currentTimeMillis());
            entry.put("id", logPollCounter++);
            logBuffer.addLast(entry);
            while (logBuffer.size() > LOG_BUFFER_MAX) logBuffer.removeFirst();
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
        sseClients.removeIf(exchange -> {
            try {
                OutputStream os = exchange.getResponseBody();
                os.write(("data: " + json + "\n\n").getBytes());
                os.flush();
                return false;
            } catch (IOException e) {
                try { exchange.close(); } catch (Exception ignored) {}
                return true;
            }
        });
    }

    // API Handlers
    private static class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("connected", Proxy.getInstance().isConnected());
            status.put("inQueue", Proxy.getInstance().isInQueue());
            status.put("queuePosition", Proxy.getInstance().getQueuePosition());
            status.put("version", VERSION);
            status.put("username", CONFIG.authentication.username);
            status.put("proxyAddress", CONFIG.server.getProxyAddress());
            status.put("uptime", System.currentTimeMillis() - Proxy.getInstance().getStartTime());
            sendJson(exchange, status);
        }
    }

    private static class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                sendJson(exchange, CONFIG);
            } else if ("POST".equals(method)) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    com.google.gson.JsonObject incoming = GSON.fromJson(body, com.google.gson.JsonObject.class);
                    if (incoming == null) {
                        sendJson(exchange, Map.of("success", false, "error", "Invalid JSON"));
                        return;
                    }
                    // merge incoming fields into existing config.json
                    com.google.gson.JsonObject root;
                    if (Files.exists(CONFIG_FILE)) {
                        root = GSON.fromJson(Files.readString(CONFIG_FILE), com.google.gson.JsonObject.class);
                    } else {
                        root = new com.google.gson.JsonObject();
                    }
                    for (var entry : incoming.entrySet()) {
                        root.add(entry.getKey(), entry.getValue());
                    }
                    Files.writeString(CONFIG_FILE, GSON.toJson(root));
                    sendJson(exchange, Map.of("success", true, "restartRequired", true));
                } catch (Exception e) {
                    sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                }
            } else {
                sendJson(exchange, Map.of("error", "Method not allowed"));
            }
        }
    }

    private static class ModulesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                List<Map<String, Object>> modules = new ArrayList<>();
                for (Module module : MODULE.getModules().toArray(new Module[0])) {
                    modules.add(Map.of("name", module.getClass().getSimpleName(), "enabled", module.isEnabled()));
                }
                sendJson(exchange, modules);
            } else if ("POST".equals(method)) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    com.google.gson.JsonObject json = GSON.fromJson(body, com.google.gson.JsonObject.class);
                    String name = json.get("name").getAsString();
                    boolean enabled = json.get("enabled").getAsBoolean();
                    boolean found = false;
                    for (Module module : MODULE.getModules().toArray(new Module[0])) {
                        if (module.getClass().getSimpleName().equals(name)) {
                            module.setEnabled(enabled);
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        saveModuleState(name, enabled);
                        sendJson(exchange, Map.of("success", true));
                    } else {
                        sendJson(exchange, Map.of("success", false, "error", "Module not found: " + name));
                    }
                } catch (Exception e) {
                    sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                }
            } else {
                sendJson(exchange, Map.of("error", "Method not allowed"));
            }
        }
    }

    private static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                // List available commands
                List<String> commands = new ArrayList<>();
                for (var cmd : COMMAND.getCommands()) {
                    commands.add(cmd.commandUsage().getName());
                }
                sendJson(exchange, commands);
            } else if ("POST".equals(method)) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    String command = body.replaceAll(".*\"command\"\\s*:\\s*\"", "").replaceAll("\".*", "");
                    CommandContext context = CommandContext.create(command, CommandSources.TERMINAL);
                    COMMAND.execute(context);

                    // Extract output from embed (commands write to embed, not multiLineOutput)
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
                    // Also check multiLineOutput
                    var ml = context.getMultiLineOutput();
                    if (ml != null && !ml.isEmpty()) output.addAll(ml);

                    sendJson(exchange, Map.of("success", true, "output", output));
                } catch (Exception e) {
                    sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                }
            } else {
                sendJson(exchange, Map.of("error", "Method not allowed"));
            }
        }
    }

    private static class PlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            List<Map<String, Object>> players = new ArrayList<>();
            var currentPlayer = Proxy.getInstance().getCurrentPlayer().get();
            if (currentPlayer != null) {
                players.add(Map.of("name", currentPlayer.getName(), "uuid", currentPlayer.getUUID().toString(), "type", "controller"));
            }
            Proxy.getInstance().getActiveConnections().forEach(session ->
                players.add(Map.of("name", session.getName(), "uuid", session.getUUID().toString(), "type", "spectator"))
            );
            sendJson(exchange, players);
        }
    }

    private static class WhitelistHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
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
                sendJson(exchange, data);
            } else if ("POST".equals(method)) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    com.google.gson.JsonObject json = GSON.fromJson(body, com.google.gson.JsonObject.class);
                    String action = json.get("action").getAsString();
                    switch (action) {
                        case "toggle" -> {
                            CONFIG.server.extra.whitelist.enable = json.get("enabled").getAsBoolean();
                            sendJson(exchange, Map.of("success", true, "enabled", CONFIG.server.extra.whitelist.enable));
                        }
                        case "add" -> {
                            String username = json.get("username").getAsString();
                            var result = PLAYER_LISTS.getWhitelist().add(username);
                            if (result.isPresent()) {
                                sendJson(exchange, Map.of("success", true, "username", result.get().getUsername(), "uuid", result.get().getUuid().toString()));
                            } else {
                                sendJson(exchange, Map.of("success", false, "error", "无法解析用户名 / Could not resolve username"));
                            }
                        }
                        case "remove" -> {
                            String username = json.get("username").getAsString();
                            PLAYER_LISTS.getWhitelist().remove(username);
                            sendJson(exchange, Map.of("success", true));
                        }
                        case "updateConfig" -> {
                            if (json.has("kickmsg")) CONFIG.server.extra.whitelist.kickmsg = json.get("kickmsg").getAsString();
                            if (json.has("autoAddClient")) CONFIG.server.extra.whitelist.autoAddClient = json.get("autoAddClient").getAsBoolean();
                            sendJson(exchange, Map.of("success", true));
                        }
                        default -> sendJson(exchange, Map.of("success", false, "error", "Unknown action: " + action));
                    }
                } catch (Exception e) {
                    sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
                }
            } else {
                sendJson(exchange, Map.of("error", "Method not allowed"));
            }
        }
    }

    private static class QueueHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            Map<String, Object> queue = new LinkedHashMap<>();
            queue.put("inQueue", Proxy.getInstance().isInQueue());
            queue.put("position", Proxy.getInstance().getQueuePosition());
            sendJson(exchange, queue);
        }
    }

    private static class LogsFileHandler implements HttpHandler {
        private static final Path LOGS_DIR = Path.of("logs");
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("file=")) {
                // Serve a specific log file
                String fileName = query.replace("file=", "").trim();
                if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                    sendJson(exchange, Map.of("success", false, "error", "Invalid filename"));
                    return;
                }
                Path file = LOGS_DIR.resolve(fileName);
                if (!Files.exists(file) || Files.isDirectory(file)) {
                    sendJson(exchange, Map.of("success", false, "error", "File not found"));
                    return;
                }
                byte[] content = Files.readAllBytes(file);
                String nameLower = fileName.toLowerCase();
                String ct = "text/plain";
                if (nameLower.endsWith(".gz")) ct = "application/gzip";
                else if (nameLower.endsWith(".zip")) ct = "application/zip";
                exchange.getResponseHeaders().set("Content-Type", ct);
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
                exchange.getResponseBody().close();
            } else {
                // List log files
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
                    }
                }
                sendJson(exchange, files);
            }
        }
    }

    private static class LatencyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            Map<String, Object> data = new LinkedHashMap<>();
            try {
                var client = Proxy.getInstance().getClient();
                boolean connected = client != null && client.isConnected();
                long botLatency = connected ? client.getPing() : -1;
                data.put("connected", connected);
                data.put("botLatency", botLatency);

                // Player-to-bot latency
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
            sendJson(exchange, data);
        }
    }

    private static class LogPollHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String query = exchange.getRequestURI().getQuery();
            long afterId = -1;
            if (query != null && query.startsWith("after=")) {
                try { afterId = Long.parseLong(query.substring(6)); } catch (NumberFormatException ignored) {}
            }
            List<Map<String, Object>> result = new ArrayList<>();
            synchronized (logBuffer) {
                for (Map<String, Object> entry : logBuffer) {
                    long id = (long) entry.get("id");
                    if (id > afterId) result.add(entry);
                }
            }
            sendJson(exchange, Map.of("logs", result, "counter", logPollCounter));
        }
    }

    private static class LogStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            exchange.getResponseHeaders().set("Connection", "keep-alive");
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, 0);
            sseClients.add(exchange);
        }
    }

    private static class SystemHandler implements HttpHandler {
        private static volatile long lastNetworkTime = 0;
        private static volatile long lastBytesIn = 0;
        private static volatile long lastBytesOut = 0;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
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

            // Network (read from /proc/net/dev on Linux)
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

            // Thread count
            data.put("threadCount", Thread.activeCount());

            sendJson(exchange, data);
        }
    }

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";

            // try classpath first (bundled in JAR), then fallback to filesystem
            byte[] content = loadResource(path);
            if (content == null) content = loadFile(Path.of("plugins/webui/web" + path));

            if (content != null) {
                exchange.getResponseHeaders().set("Content-Type", getContentType(path));
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            } else {
                // try index.html for SPA fallback
                content = loadResource("/index.html");
                if (content == null) content = loadFile(Path.of("plugins/webui/web/index.html"));
                if (content != null) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                } else {
                    String notFound = "404 Not Found";
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(404, notFound.length());
                    exchange.getResponseBody().write(notFound.getBytes());
                }
            }
            exchange.getResponseBody().close();
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
            try {
                if (Files.exists(path) && !Files.isDirectory(path)) {
                    return Files.readAllBytes(path);
                }
            } catch (Exception ignored) {}
            return null;
        }
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
                .then(literal("enable").executes(ctx -> { start(); ctx.getSource().getEmbed().title("WebUI Enabled").description("http://localhost:" + httpPort); return 1; }))
                .then(literal("disable").executes(ctx -> { stop(); ctx.getSource().getEmbed().title("WebUI Disabled"); return 1; }))
                .then(literal("status").executes(ctx -> {
                    String status = (httpServer != null && httpServer.getAddress() != null) ? "Running" : "Stopped";
                    ctx.getSource().getEmbed().title("WebUI Status").description("状态: " + status + "\nHTTP: http://localhost:" + httpPort);
                    return 1;
                }))
                .executes(ctx -> { ctx.getSource().getEmbed().title("WebUI").description("用法: webui enable/disable/status"); return 1; });
        }
    }

    // Utility
    private static com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static void sendJson(HttpExchange exchange, Object obj) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1); return;
        }
        String response = GSON.toJson(obj);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, response.getBytes().length);
        exchange.getResponseBody().write(response.getBytes());
        exchange.getResponseBody().close();
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

    // Logback Appender - intercepts all SLF4J/Logback logs
    private static class WebUILogHandler extends UnsynchronizedAppenderBase<ILoggingEvent> {
        private static final String[] IGNORED_PREFIXES = {
            "com.sun.net.httpserver",
            "jdk.httpserver"
        };

        @Override
        protected void append(ILoggingEvent event) {
            if (instance == null) return;
            // Filter out DEBUG level
            if (event.getLevel() == ch.qos.logback.classic.Level.DEBUG) return;
            String logger = event.getLoggerName();
            if (logger == null || logger.isEmpty()) logger = "root";
            // Filter out HTTP server logs
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

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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;

import static com.zenith.Globals.*;
import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.builder.LiteralArgumentBuilder.literal;

public class WebUIPlugin implements ZenithProxyPlugin {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI");
    private static final Path CONFIG_FILE = Path.of("config.json");
    private static int httpPort = 8080;
    private static int wsPort = 8081;
    private HttpServer httpServer;
    private WebSocketServer webSocketServer;
    private PluginAPI api;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
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
                    if (webUI.has("websocketPort")) wsPort = webUI.get("websocketPort").getAsInt();
                    LOG.info("Loaded WebUI config: http={}, ws={}", httpPort, wsPort);
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
        webUI.addProperty("websocketPort", 8081);
        return webUI;
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
            httpServer.createContext("/api/system", new SystemHandler());
            httpServer.createContext("/api/latency", new LatencyHandler());
            httpServer.createContext("/", new StaticFileHandler());

            webSocketServer = new WebSocketServer(wsPort);
            webSocketServer.start();

            httpServer.start();
        } catch (Exception e) {
            LOG.error("Failed to start WebUI", e);
        }
    }

    public void stop() {
        if (httpServer != null) httpServer.stop(0);
        if (webSocketServer != null) webSocketServer.stop();
    }

    public void broadcastLog(String level, String logger, String message) {
        if (webSocketServer != null) webSocketServer.broadcastLog(level, logger, message);
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
            status.put("websocketPort", wsPort);
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
                    sendJson(exchange, Map.of("success", true));
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
                for (Module module : MODULE.getModules()) {
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
                    for (Module module : MODULE.getModules()) {
                        if (module.getClass().getSimpleName().equals(name)) {
                            module.setEnabled(enabled);
                            found = true;
                            break;
                        }
                    }
                    if (found) {
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
            if (!"POST".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of("error", "POST only")); return; }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes());
                String command = body.replaceAll(".*\"command\"\\s*:\\s*\"", "").replaceAll("\".*", "");
                CommandContext context = CommandContext.create(command, CommandSources.TERMINAL);
                COMMAND.execute(context);
                sendJson(exchange, Map.of("success", true, "output", context.getMultiLineOutput()));
            } catch (Exception e) {
                sendJson(exchange, Map.of("success", false, "error", e.getMessage()));
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
                var currentPlayer = Proxy.getInstance().getCurrentPlayer().get();
                data.put("connected", currentPlayer != null);
                data.put("latency", currentPlayer != null ? 0 : -1);
            } catch (Exception e) {
                data.put("latency", -1);
                data.put("connected", false);
            }
            sendJson(exchange, data);
        }
    }

    private static class SystemHandler implements HttpHandler {
        private static long lastNetworkTime = 0;
        private static long lastBytesIn = 0;
        private static long lastBytesOut = 0;

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
                    ctx.getSource().getEmbed().title("WebUI Status").description("状态: " + status + "\nHTTP: http://localhost:" + httpPort + "\nWebSocket: ws://localhost:" + wsPort);
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
}

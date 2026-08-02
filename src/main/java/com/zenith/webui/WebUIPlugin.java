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
    private HttpServer httpServer;
    private WebSocketServer webSocketServer;
    private PluginAPI api;
    private int httpPort = 8080;
    private int wsPort = 8081;

    @Override
    public void onLoad(PluginAPI pluginAPI) {
        this.api = pluginAPI;
        pluginAPI.registerCommand(new WebUICommand());
        start();
        LOG.info("WebUI plugin loaded! Access at http://localhost:{}", httpPort);
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
            sendJson(exchange, status);
        }
    }

    private static class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            sendJson(exchange, CONFIG);
        }
    }

    private static class ModulesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            List<Map<String, Object>> modules = new ArrayList<>();
            for (Module module : MODULE.getModules()) {
                modules.add(Map.of("name", module.getClass().getSimpleName(), "enabled", module.isEnabled()));
            }
            sendJson(exchange, modules);
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

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) { sendJson(exchange, Map.of()); return; }
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path)) path = "/index.html";
            Path filePath = Path.of("plugins/webui/web" + path);
            if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                byte[] content = Files.readAllBytes(filePath);
                exchange.getResponseHeaders().set("Content-Type", getContentType(path));
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, content.length);
                exchange.getResponseBody().write(content);
            } else {
                Path indexPath = Path.of("plugins/webui/web/index.html");
                if (Files.exists(indexPath)) {
                    byte[] content = Files.readAllBytes(indexPath);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, content.length);
                    exchange.getResponseBody().write(content);
                } else {
                    String notFound = "404 Not Found - WebUI files not found at plugins/webui/web/";
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(404, notFound.length());
                    exchange.getResponseBody().write(notFound.getBytes());
                }
            }
            exchange.getResponseBody().close();
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

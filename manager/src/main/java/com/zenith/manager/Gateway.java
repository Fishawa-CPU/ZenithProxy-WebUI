package com.zenith.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 单端口统一入口。
 * <p>
 * 对外只有一个地址：{@code http://127.0.0.1:<port>/}
 * <ul>
 *   <li>{@code /}                 总览页</li>
 *   <li>{@code /api/bots}         bot 列表 / 启停 / 新建 / 删除</li>
 *   <li>{@code /bot/<name>/**}    透明转发到该 bot 自己的 WebUI（含 SSE 流）</li>
 * </ul>
 * 用户从头到尾只看到一个端口、一个页面，各 bot 的内部端口不出现在界面上。
 */
public final class Gateway {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    /** 写回 config.json 时用带缩进的格式，和 ZenithProxy 自己生成的一致 */
    private static final Gson GSON_PRETTY = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final BotStore store;
    private final BotProcess process;
    private final int port;
    private final HttpClient http;
    private HttpServer server;

    public Gateway(BotStore store, BotProcess process, int port) {
        this.store = store;
        this.process = process;
        this.port = port;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
        Log.info("管理界面: http://127.0.0.1:" + port + "/");
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        try {
            if (path.equals("/") || path.equals("/index.html")) {
                serveIndex(ex);
            } else if (path.startsWith("/__mgr/")) {
                String res = path.substring("/__mgr/".length());
                if (res.equals("manager.js") || res.equals("manager.css") || res.equals("labels.json")) {
                    serveResource(ex, res);
                } else {
                    sendText(ex, 404, "Not Found");
                }
            } else if (path.startsWith("/api/bots")) {
                handleBotsApi(ex);
            } else if (path.startsWith("/bot/")) {
                proxy(ex, path);
            } else {
                sendText(ex, 404, "Not Found");
            }
        } catch (Exception e) {
            Log.error("请求处理失败 " + path, e);
            try {
                sendText(ex, 500, "Internal Error: " + e.getMessage());
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    // ---------------------------------------------------------------- 静态

    private void serveIndex(HttpExchange ex) throws IOException {
        String html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="UTF-8">
            <title>ZenithProxy Bot Manager</title>
            <link rel="stylesheet" href="/__mgr/manager.css">
            </head>
            <body>
            <div id="app"><div class="loading">正在加载…</div></div>
            <script src="/__mgr/manager.js"></script>
            </body>
            </html>
            """;
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void serveResource(HttpExchange ex, String name) throws IOException {
        try (InputStream in = Gateway.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                sendText(ex, 404, "Not Found");
                return;
            }
            byte[] body = in.readAllBytes();
            String ct = name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".json") ? "application/json; charset=utf-8"
                : "application/javascript; charset=utf-8";
            ex.getResponseHeaders().set("Content-Type", ct);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ---------------------------------------------------------------- API

    private void handleBotsApi(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (method.equals("GET")) {
            String cfgFor = queryParam(ex, "config");
            if (cfgFor != null) {
                sendBotConfig(ex, cfgFor);
                return;
            }
            if (queryParam(ex, "template") != null) {
                JsonObject o = new JsonObject();
                o.addProperty("name", "");
                o.addProperty("path", "template/config.json（新建时用）");
                o.add("config", process.templateConfig());
                sendJson(ex, 200, GSON.toJson(o));
                return;
            }
            var arr = new com.google.gson.JsonArray();
            for (BotStore.Bot b : store.list()) {
                arr.add(snapshot(b));
            }
            JsonObject out = new JsonObject();
            out.add("bots", arr);
            out.addProperty("managerPort", port);
            sendJson(ex, 200, GSON.toJson(out));
            return;
        }

        if (!method.equals("POST")) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }

        JsonObject req;
        try {
            req = GSON.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"请求体不是合法 JSON\"}");
            return;
        }
        if (req == null || !req.has("action")) {
            sendJson(ex, 400, "{\"ok\":false,\"error\":\"缺少 action\"}");
            return;
        }
        String action = req.get("action").getAsString();
        String name = req.has("name") ? req.get("name").getAsString() : null;

        try {
            switch (action) {
                case "start" -> {
                    BotStore.Bot b = requireBot(name);
                    process.start(b, store.root());
                    sendJson(ex, 200, ok(snapshot(b)));
                }
                case "stop" -> {
                    requireBot(name);
                    process.stop(name);
                    sendJson(ex, 200, ok(snapshot(store.get(name))));
                }
                case "restart" -> {
                    BotStore.Bot b = requireBot(name);
                    process.stop(name);
                    Thread.sleep(300);
                    process.start(b, store.root());
                    sendJson(ex, 200, ok(snapshot(b)));
                }
                case "create" -> {
                    JsonObject userCfg = (req.has("config") && req.get("config").isJsonObject())
                        ? req.getAsJsonObject("config") : null;

                    String host = BotStore.DEFAULT_SERVER;
                    int sport = BotStore.DEFAULT_SERVER_PORT;
                    boolean auto = false;
                    if (userCfg != null) {
                        // 新建时提交了完整配置：服务器/自动连接从配置里读，不再单独传
                        JsonObject client = obj(userCfg, "client");
                        JsonObject server = obj(client, "server");
                        host = str(server, "address", BotStore.DEFAULT_SERVER);
                        sport = num(server, "port", BotStore.DEFAULT_SERVER_PORT);
                        auto = bool(client, "autoConnect", false);
                    } else {
                        host = str(req, "server", BotStore.DEFAULT_SERVER);
                        sport = num(req, "serverPort", BotStore.DEFAULT_SERVER_PORT);
                        auto = bool(req, "autoConnect", false);
                    }

                    BotStore.Bot b = store.create(name, host, sport, auto);
                    if (userCfg != null) applyAccountFromConfig(b, userCfg);
                    else applyAccount(b, req);
                    store.save(b);
                    // 立刻把完整 config.json + 插件落进 bots/<name>/，不用等启动
                    process.provision(b, store.root(), userCfg);
                    sendJson(ex, 200, ok(snapshot(b)));
                }
                case "update" -> {
                    BotStore.Bot b = requireBot(name);
                    String host = str(req, "server", b.server);
                    if (!BotStore.validHost(host)) throw new IOException("服务器地址不合法: " + host);
                    int sport = num(req, "serverPort", b.serverPort);
                    if (!BotStore.validPort(sport)) throw new IOException("服务器端口不合法: " + sport);
                    b.server = host;
                    b.serverPort = sport;
                    b.autoConnect = bool(req, "autoConnect", b.autoConnect);
                    applyAccount(b, req);
                    store.save(b);
                    // bot 没在跑就直接把改动写进它自己的 config.json；在跑的话留着下次启动时同步
                    if (!process.running(b.name)) process.provision(b, store.root());
                    // 配置在进程启动时写入，运行中改动要重启才生效
                    sendJson(ex, 200, ok(snapshot(b)));
                }
                case "delete" -> {
                    requireBot(name);
                    process.stop(name);
                    store.delete(name);
                    sendJson(ex, 200, "{\"ok\":true}");
                }
                case "saveConfig" -> {
                    BotStore.Bot b = requireBot(name);
                    if (req == null || !req.has("config") || !req.get("config").isJsonObject()) {
                        throw new IOException("config 必须是 JSON 对象");
                    }
                    JsonObject cfg = req.getAsJsonObject("config");
                    Path dir = b.dir(store.root());
                    Files.createDirectories(dir);
                    Path file = dir.resolve("config.json");
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, dir.resolve("config.json.bak"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    long logOffset = fileSize(dir.resolve("console.log"));
                    try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                        GSON_PRETTY.toJson(cfg, w);
                    }
                    Log.info("已保存 " + name + "/config.json");

                    // 打了热重载补丁的 ZenithProxy 会自己发现文件变化并原地重载
                    boolean running = process.running(name);
                    boolean hot = running && waitForReloadLog(dir.resolve("console.log"), logOffset, 6000);

                    JsonObject out = new JsonObject();
                    out.addProperty("ok", true);
                    out.addProperty("hotReload", hot);
                    out.addProperty("running", running);
                    sendJson(ex, 200, GSON.toJson(out));
                }
                default -> sendJson(ex, 400, "{\"ok\":false,\"error\":\"未知 action: " + action + "\"}");
            }
        } catch (Exception e) {
            sendJson(ex, 500, "{\"ok\":false,\"error\":" + GSON.toJson(e.getMessage()) + "}");
        }
    }

    /** GET /api/bots?config=<name> —— 返回该 bot 自己的 config.json 全文 */
    private void sendBotConfig(HttpExchange ex, String name) throws IOException {
        BotStore.Bot b = store.get(name);
        if (b == null) {
            sendJson(ex, 404, "{\"ok\":false,\"error\":\"bot 不存在\"}");
            return;
        }
        Path file = b.dir(store.root()).resolve("config.json");
        if (!Files.isRegularFile(file)) {
            sendJson(ex, 404, "{\"ok\":false,\"error\":\"该 bot 还没有 config.json\"}");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", b.name);
        o.addProperty("path", file.toAbsolutePath().toString());
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            o.add("config", GSON.fromJson(text, JsonObject.class));
        } catch (Exception e) {
            sendJson(ex, 500, "{\"ok\":false,\"error\":\"config.json 不是合法 JSON: " + e.getMessage() + "\"}");
            return;
        }
        sendJson(ex, 200, GSON.toJson(o));
    }

    /**
     * 等 bot 的日志里出现热重载结果。
     * <p>
     * ZenithProxy 的 ConfigWatcher 收到文件变化后会打印
     * {@code ConfigReload: applied|no-op|failed}，用这行确认真的重载了，
     * 而不是"文件写了但没生效"。
     */
    private boolean waitForReloadLog(Path log, long fromOffset, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Files.isRegularFile(log)) {
                    byte[] all = Files.readAllBytes(log);
                    if (all.length > fromOffset) {
                        String tail = new String(all, (int) fromOffset,
                            all.length - (int) fromOffset, StandardCharsets.UTF_8);
                        if (tail.contains("ConfigReload: failed")) return false;
                        if (tail.contains("ConfigReload: applied")
                            || tail.contains("ConfigReload: no-op")) return true;
                    }
                }
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private static long fileSize(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.size(p) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String queryParam(HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) return null;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                return java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private BotStore.Bot requireBot(String name) throws IOException {        BotStore.Bot b = store.get(name);
        if (b == null) throw new IOException("bot 不存在: " + name);
        return b;
    }

    private String ok(JsonObject snapshot) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.add("bot", snapshot);
        return GSON.toJson(o);
    }

    /**
     * 把请求里的账号字段写进 bot.json。
     * <p>
     * {@code accountType} 一旦出现就代表"账号交给管理器接管"；离线模式必须填游戏名，
     * 因为 ZenithProxy 直接把 {@code authentication.username} 当离线角色名用。
     */
    private void applyAccount(BotStore.Bot b, JsonObject req) throws IOException {
        if (req == null || !req.has("accountType") || req.get("accountType").isJsonNull()) return;
        String type = str(req, "accountType", "device_code");
        if (!BotStore.validAccountType(type)) throw new IOException("账号类型不合法: " + type);
        String username = str(req, "accountUsername", null);
        String email = str(req, "accountEmail", null);
        String password = str(req, "accountPassword", null);

        b.accountType = type;
        if ("offline".equals(type)) {
            if (username == null) throw new IOException("离线模式必须填游戏名");
            b.accountUsername = username;
            b.accountEmail = null;
            b.accountPassword = null;
        } else {
            b.accountUsername = null;
            b.accountEmail = email;
            b.accountPassword = password;
        }
        // 始终按当前游戏名推导，改名后 UUID 跟着变
        b.offlineUuid = null;
    }

    /**
     * 新建时用户直接编辑了完整配置：账号字段从配置里同步到 bot.json，
     * 这样管理器后续的托管逻辑（改端口/服务器时重写配置）不会跟用户填的账号打架。
     */
    private void applyAccountFromConfig(BotStore.Bot b, JsonObject cfg) throws IOException {
        JsonObject auth = obj(cfg, "authentication");
        if (auth == null) return;
        String type = str(auth, "accountType", "device_code");
        if (!BotStore.validAccountType(type)) type = "device_code";
        b.accountType = type;
        if ("offline".equals(type)) {
            String name = str(auth, "username", null);
            if (name == null || name.equalsIgnoreCase("unknown")) {
                throw new IOException("离线模式必须填游戏名（authentication.username）");
            }
            b.accountUsername = name;
            b.accountEmail = null;
            b.accountPassword = null;
        } else {
            b.accountUsername = null;
            b.accountEmail = str(auth, "email", null);
            b.accountPassword = str(auth, "password", null);
        }
        b.offlineUuid = null;
    }

    /** 取子对象，不存在或类型不对返回 null */
    private static JsonObject obj(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) return null;
        return parent.getAsJsonObject(key);
    }

    private static String str(JsonObject o, String key, String def) {        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            String v = o.get(key).getAsString().trim();
            return v.isEmpty() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private static int num(JsonObject o, String key, int def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception e) {
            return def;
        }
    }

    private JsonObject snapshot(BotStore.Bot b) {
        JsonObject o = new JsonObject();
        o.addProperty("name", b.name);
        o.addProperty("proxyPort", b.proxyPort);
        o.addProperty("webuiPort", b.webuiPort);
        o.addProperty("running", process.running(b.name));
        o.addProperty("pid", process.pid(b.name));
        o.addProperty("uptimeMs", process.uptimeMs(b.name));
        o.addProperty("reachable", BotProcess.portOpen(b.webuiPort));
        o.addProperty("server", b.server);
        o.addProperty("serverPort", b.serverPort);
        o.addProperty("autoConnect", b.autoConnect);
        o.addProperty("accountType", b.accountType == null ? "device_code" : b.accountType);
        o.addProperty("accountManaged", b.accountType != null);
        o.addProperty("accountUsername", b.accountUsername);
        o.addProperty("accountEmail", b.accountEmail);
        o.addProperty("accountPassword", b.accountPassword);
        // 账号名只有微软登录成功后才会出现在 config.json 里；null = 还没登录
        String account = BotProcess.readAccount(b.dir(store.root()));
        o.addProperty("account", account);
        o.addProperty("loggedIn", account != null);
        return o;
    }

    // ---------------------------------------------------------------- 反代

    private void proxy(HttpExchange ex, String path) throws IOException {
        // /bot/<name>/rest...
        String rest = path.substring("/bot/".length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        String tail = slash < 0 ? "" : rest.substring(slash);

        BotStore.Bot b = store.get(name);
        if (b == null) {
            sendText(ex, 404, "bot 不存在: " + name);
            return;
        }
        if (!tail.startsWith("/")) {
            // 补尾斜杠，保证页面内相对路径 ../ 解析正确
            redirect(ex, path + "/");
            return;
        }
        if (!BotProcess.portOpen(b.webuiPort)) {
            byte[] msg = ("<html><body style='font-family:sans-serif;padding:40px;color:#cdd6f4;background:#1e1e2e'>"
                + "<h3>" + escapeHtml(name) + " 未运行</h3>"
                + "<p>回到 <a style='color:#89b4fa' href='/'>总览</a> 启动该 bot。</p>"
                + "</body></html>").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(503, msg.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(msg);
            }
            return;
        }

        URI upstream = URI.create("http://127.0.0.1:" + b.webuiPort + tail
            + (ex.getRequestURI().getRawQuery() != null ? "?" + ex.getRequestURI().getRawQuery() : ""));

        byte[] reqBody = ex.getRequestBody().readAllBytes();
        HttpRequest.Builder rb = HttpRequest.newBuilder(upstream)
            .timeout(Duration.ofSeconds(30))
            .method(ex.getRequestMethod(), reqBody.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(reqBody));

        for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
            String k = h.getKey();
            if (k.equalsIgnoreCase("Host") || k.equalsIgnoreCase("Connection")
                || k.equalsIgnoreCase("Content-Length") || k.equalsIgnoreCase("Accept-Encoding")) {
                continue;
            }
            for (String v : h.getValue()) rb.header(k, v);
        }

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("转发被中断", e);
        }

        var rh = ex.getResponseHeaders();
        String contentType = resp.headers().firstValue("content-type").orElse("application/octet-stream");
        for (Map.Entry<String, List<String>> h : resp.headers().map().entrySet()) {
            String k = h.getKey();
            if (k.equalsIgnoreCase("content-length") || k.equalsIgnoreCase("transfer-encoding")
                || k.equalsIgnoreCase("connection") || k.equalsIgnoreCase("content-encoding")
                || k.equalsIgnoreCase("keep-alive")) {
                continue;
            }
            for (String v : h.getValue()) rh.add(k, v);
        }

        boolean sse = contentType.toLowerCase().startsWith("text/event-stream");
        if (sse) {
            // 流式透传：不缓冲，边读边 flush，SSE 才能实时到浏览器
            ex.sendResponseHeaders(resp.statusCode(), 0);
            try (OutputStream os = ex.getResponseBody(); InputStream in = resp.body()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    os.flush();
                }
            }
            return;
        }

        byte[] body = resp.body().readAllBytes();

        // HTML 注入：让每个 bot 页面顶部出现 bot 切换条
        if (contentType.toLowerCase().contains("text/html")) {
            String html = new String(body, StandardCharsets.UTF_8);
            html = inject(html, b.name);
            body = html.getBytes(StandardCharsets.UTF_8);
            rh.set("Content-Type", "text/html; charset=utf-8");
        }

        ex.sendResponseHeaders(resp.statusCode(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private String inject(String html, String botName) {
        String head = "<script>window.__BOT_NAME__=" + GSON.toJson(botName) + ";</script>"
            + "<link rel=\"stylesheet\" href=\"/__mgr/manager.css\">";
        String boot = "<script src=\"/__mgr/manager.js\"></script>";
        String out = html;
        int i = out.indexOf("</head>");
        if (i >= 0) {
            out = out.substring(0, i) + head + out.substring(i);
        } else {
            out = head + out;
        }
        int j = out.lastIndexOf("</body>");
        if (j >= 0) {
            out = out.substring(0, j) + boot + out.substring(j);
        } else {
            out = out + boot;
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(302, -1);
    }

    private void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

package com.zenith.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 一个 bot = 一个 ZenithProxy 子进程 + 一个独立工作目录。
 * <p>
 * 进程的环境与状态完全隔离：cwd 决定 config.json / plugins / log 的落点，
 * 因此端口、账号、插件配置互不影响。
 */
public final class BotProcess {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path zenithJar;
    private final Path javaExe;
    private final String extraJvmArgs;
    private final Path home;
    private final Map<String, Process> procs = new ConcurrentHashMap<>();
    private final Map<String, Long> startedAt = new ConcurrentHashMap<>();

    public BotProcess(Path zenithJar, Path javaExe, String extraJvmArgs, Path home) {
        this.zenithJar = zenithJar;
        this.javaExe = javaExe;
        this.extraJvmArgs = extraJvmArgs == null ? "" : extraJvmArgs;
        this.home = home;
    }

    /**
     * 新 bot 目录只有 bot.json，缺插件和配置。这里补齐：
     * <ul>
     *   <li>{@code <home>/plugins/*.jar} → bot 的 plugins/（插件模板）</li>
     *   <li>{@code <home>/template/config.json} → bot 的 config.json，并把端口改成分配值</li>
     * </ul>
     * 不补的话，新 bot 既没有 WebUI，也会用默认 25565 端口和已有 bot 撞车。
     */
    /**
     * 建 bot 时就把插件和**完整** config.json 落盘到 {@code bots/<name>/}，
     * 不必等第一次启动。这样每个 bot 从创建那一刻起就是一个自包含的目录，
     * 配置、插件、日志都只属于它自己。
     */
    public void provision(BotStore.Bot bot, Path botsRoot) throws IOException {
        provision(bot, botsRoot, null);
    }

    /**
     * @param userConfig 用户在「全部配置」里编辑好的完整配置；null 表示用模板生成
     */
    public void provision(BotStore.Bot bot, Path botsRoot, JsonObject userConfig) throws IOException {
        seedTemplate(bot, botsRoot, userConfig);
        ensureWebUiConfig(bot, botsRoot);
    }

    /** 新建 bot 时给编辑器用的配置模板（完整字段）。 */
    public JsonObject templateConfig() {
        return loadConfigTemplate();
    }

    private void seedTemplate(BotStore.Bot bot, Path botsRoot, JsonObject userConfig) throws IOException {
        Path dir = bot.dir(botsRoot);
        Path pluginsDir = dir.resolve("plugins");
        Files.createDirectories(pluginsDir);

        Path pluginTemplate = resolveAssetDir("plugins");
        if (pluginTemplate != null) {
            try (var jars = Files.list(pluginTemplate)) {
                for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
                    Path target = pluginsDir.resolve(jar.getFileName());
                    if (!Files.exists(target)) {
                        Files.copy(jar, target);
                        Log.info("已安装插件 " + jar.getFileName() + " → " + bot.name);
                    }
                }
            }
        } else {
            Log.warn("找不到插件模板目录 plugins/，新 bot 将没有 WebUI");
        }

        Path cfg = dir.resolve("config.json");
        if (userConfig != null) {
            // 用户在新建时就把全部字段配好了：以他的为准，只覆盖管理器托管的端口/服务器/账号
            JsonObject root = userConfig.deepCopy();
            syncConfig(root, bot);
            try (Writer w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
                GSON.toJson(root, w);
            }
            Log.info("已写入 " + bot.name + "/config.json（用户自定义完整配置）");
        } else if (!Files.exists(cfg)) {
            JsonObject root = loadConfigTemplate();
            if (root != null) {
                syncConfig(root, bot);
                try (Writer w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
                    GSON.toJson(root, w);
                }
                Log.info("已生成 " + bot.name + "/config.json（端口 " + bot.proxyPort
                    + "，目标 " + bot.server + ":" + bot.serverPort + "）");
            } else {
                Log.warn("缺少配置模板，将由 ZenithProxy 生成默认配置（端口可能冲突）");
            }
        } else {
            // 已有配置：端口与目标服务器都以 Manager 的设定为准，避免手改后对不上
            try {
                JsonObject root;
                try (var r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                    root = GSON.fromJson(r, JsonObject.class);
                }
                if (root != null && syncConfig(root, bot)) {
                    try (Writer w = Files.newBufferedWriter(cfg, StandardCharsets.UTF_8)) {
                        GSON.toJson(root, w);
                    }
                }
            } catch (Exception e) {
                Log.warn("校验 " + bot.name + " 配置失败: " + e.getMessage());
            }
        }
    }

    /** 端口 + 目标服务器 + 账号一起对齐，返回是否发生了修改。 */
    private boolean syncConfig(JsonObject root, BotStore.Bot bot) {
        boolean changed = patchPort(root, bot.proxyPort);
        if (patchServer(root, bot)) changed = true;
        if (patchAccount(root, bot)) changed = true;
        return changed;
    }

    /**
     * 写入账号配置。
     * <p>
     * 只在 {@code bot.accountType} 非 null 时接管——老版本建的 bot 不写这个字段，
     * 管理器就不会去动它已有的 authentication 段。
     * <p>
     * 正版模式不碰 {@code username}：它由登录成功后 ZenithProxy 自己回写；但如果是从
     * 离线切回正版，会把残留的离线名字清成 "Unknown"，否则仪表盘会显示一个并没登录的 ID。
     */
    private boolean patchAccount(JsonObject root, BotStore.Bot bot) {
        if (bot.accountType == null) return false;

        boolean changed = false;
        JsonObject auth;
        if (root.has("authentication") && root.get("authentication").isJsonObject()) {
            auth = root.getAsJsonObject("authentication");
        } else {
            auth = new JsonObject();
            root.add("authentication", auth);
            changed = true;
        }

        String prevType = auth.has("accountType") && !auth.get("accountType").isJsonNull()
            ? auth.get("accountType").getAsString() : "";
        String type = bot.accountType;
        if (!type.equals(prevType)) {
            auth.addProperty("accountType", type);
            changed = true;
        }

        if ("offline".equals(type)) {
            String name = bot.accountUsername == null ? "" : bot.accountUsername.trim();
            if (name.isEmpty()) return changed;
            String curName = auth.has("username") && !auth.get("username").isJsonNull()
                ? auth.get("username").getAsString() : "";
            if (!name.equals(curName)) {
                auth.addProperty("username", name);
                changed = true;
            }
            String uuid = (bot.offlineUuid == null || bot.offlineUuid.isBlank())
                ? BotStore.offlineUuidFor(name) : bot.offlineUuid.trim();
            String curUuid = auth.has("offlineUUID") && !auth.get("offlineUUID").isJsonNull()
                ? auth.get("offlineUUID").getAsString() : "";
            if (!uuid.equals(curUuid)) {
                auth.addProperty("offlineUUID", uuid);
                changed = true;
            }
        } else {
            if ("offline".equals(prevType)) {
                auth.addProperty("username", "Unknown");
                changed = true;
            }
            if (bot.accountEmail != null && !bot.accountEmail.isBlank()) {
                String cur = auth.has("email") && !auth.get("email").isJsonNull()
                    ? auth.get("email").getAsString() : "";
                if (!bot.accountEmail.equals(cur)) {
                    auth.addProperty("email", bot.accountEmail);
                    changed = true;
                }
            }
            if (bot.accountPassword != null && !bot.accountPassword.isEmpty()) {
                String cur = auth.has("password") && !auth.get("password").isJsonNull()
                    ? auth.get("password").getAsString() : "";
                if (!bot.accountPassword.equals(cur)) {
                    auth.addProperty("password", bot.accountPassword);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * 写入目标服务器与自动连接开关。
     * <p>
     * {@code username} 不在这里动：它只在微软登录成功后由 ZenithProxy 自己回写，
     * 强行填值没有意义（见 {@code Config.Authentication#username} 的注释）。
     */
    private boolean patchServer(JsonObject root, BotStore.Bot bot) {
        boolean changed = false;

        JsonObject client;
        if (root.has("client") && root.get("client").isJsonObject()) {
            client = root.getAsJsonObject("client");
        } else {
            client = new JsonObject();
            root.add("client", client);
            changed = true;
        }

        JsonObject server;
        if (client.has("server") && client.get("server").isJsonObject()) {
            server = client.getAsJsonObject("server");
        } else {
            server = new JsonObject();
            client.add("server", server);
            changed = true;
        }

        String wantHost = (bot.server == null || bot.server.isBlank())
            ? BotStore.DEFAULT_SERVER : bot.server.trim();
        int wantPort = bot.serverPort <= 0 ? BotStore.DEFAULT_SERVER_PORT : bot.serverPort;

        String curHost = server.has("address") && !server.get("address").isJsonNull()
            ? server.get("address").getAsString() : null;
        if (!wantHost.equals(curHost)) {
            server.addProperty("address", wantHost);
            changed = true;
        }

        int curPort = server.has("port") ? server.get("port").getAsInt() : -1;
        if (curPort != wantPort) {
            server.addProperty("port", wantPort);
            changed = true;
        }

        boolean curAuto = client.has("autoConnect") && client.get("autoConnect").getAsBoolean();
        if (curAuto != bot.autoConnect) {
            client.addProperty("autoConnect", bot.autoConnect);
            changed = true;
        }
        return changed;
    }

    /**
     * 读 bot 已登录的 Minecraft 账号名。
     * <p>
     * {@code config.json} 的 {@code authentication.username} 默认是 "Unknown"，
     * 只有微软登录成功后才会被 ZenithProxy 改写成真实 ID —— 所以这里返回 null
     * 表示"还没登录过"，不是读错了。
     */
    public static String readAccount(Path botDir) {
        Path cfg = botDir.resolve("config.json");
        if (!Files.isRegularFile(cfg)) return null;
        try (var r = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            if (root != null && root.has("authentication") && root.get("authentication").isJsonObject()) {
                JsonObject auth = root.getAsJsonObject("authentication");
                if (auth.has("username") && !auth.get("username").isJsonNull()) {
                    String u = auth.get("username").getAsString().trim();
                    if (!u.isEmpty() && !u.equalsIgnoreCase("unknown")) return u;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 读 bot 是否已登录（等价于 readAccount != null，单独留一个语义清晰的名字）。 */
    public static boolean loggedIn(Path botDir) {
        return readAccount(botDir) != null;
    }

    /**
     * 资源目录可能位于应用根目录，也可能在 jpackage 的 app/ 下。
     */
    private Path resolveAssetDir(String name) {
        Path a = home.resolve(name);
        if (Files.isDirectory(a)) return a;
        Path b = home.resolve("app").resolve(name);
        if (Files.isDirectory(b)) return b;
        return null;
    }

    private JsonObject loadConfigTemplate() {
        Path tpl = resolveAssetPath("template/config.json");
        if (tpl != null) {
            try (var r = Files.newBufferedReader(tpl, StandardCharsets.UTF_8)) {
                return GSON.fromJson(r, JsonObject.class);
            } catch (Exception e) {
                Log.warn("读取配置模板失败: " + e.getMessage());
            }
        }
        // 兜底：模板以 classpath 资源形式随 jar 一起分发
        try (var in = BotProcess.class.getResourceAsStream("/template/config.json")) {
            if (in != null) {
                return GSON.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
            }
        } catch (Exception e) {
            Log.warn("读取内置配置模板失败: " + e.getMessage());
        }
        return null;
    }

    private Path resolveAssetPath(String rel) {
        Path a = home.resolve(rel);
        if (Files.isRegularFile(a)) return a;
        Path b = home.resolve("app").resolve(rel);
        if (Files.isRegularFile(b)) return b;
        return null;
    }

    /** 返回是否发生了修改。 */
    private boolean patchPort(JsonObject root, int port) {
        boolean changed = false;
        if (root.has("client") && root.get("client").isJsonObject()) {
            JsonObject client = root.getAsJsonObject("client");
            if (client.has("server") && client.get("server").isJsonObject()) {
                JsonObject server = client.getAsJsonObject("server");
                int cur = server.has("port") ? server.get("port").getAsInt() : -1;
                if (cur != port) {
                    server.addProperty("port", port);
                    changed = true;
                }
            }
            if (client.has("connectionProxy") && client.get("connectionProxy").isJsonObject()) {
                JsonObject cp = client.getAsJsonObject("connectionProxy");
                if (cp.has("port") && cp.get("port").getAsInt() == port) {
                    // 入站代理端口与游戏端口错开，避免自连
                    cp.addProperty("port", port + 1000);
                    changed = true;
                }
            }
        }
        if (root.has("server") && root.get("server").isJsonObject()) {
            JsonObject s = root.getAsJsonObject("server");
            if (s.has("bind") && s.get("bind").isJsonObject()) {
                JsonObject bind = s.getAsJsonObject("bind");
                int cur = bind.has("port") ? bind.get("port").getAsInt() : -1;
                if (cur != port) {
                    bind.addProperty("port", port);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public boolean running(String name) {
        Process p = procs.get(name);
        return p != null && p.isAlive();
    }

    public long pid(String name) {
        Process p = procs.get(name);
        return p == null ? -1 : p.pid();
    }

    public long uptimeMs(String name) {
        Long t = startedAt.get(name);
        return t == null ? 0 : System.currentTimeMillis() - t;
    }

    /**
     * 启动前把 WebUI 插件配置对齐成 Manager 期望的样子：
     * 端口固定、关闭插件自身认证（由 Manager 统一把关）、开启。
     * <p>
     * 必须写**完整**字段：插件的 ConfigManager 会校验字段完整性，缺字段会被判定为
     * "invalid fields" 然后备份并重新生成默认配置（端口回到 8080），
     * 结果是新 bot 和已有 bot 撞端口。
     */
    public void ensureWebUiConfig(BotStore.Bot bot, Path botsRoot) throws IOException {
        Path cfgPath = bot.dir(botsRoot).resolve("plugins/webui/config.json");
        Files.createDirectories(cfgPath.getParent());

        JsonObject old = null;
        if (Files.isRegularFile(cfgPath)) {
            try (var r = Files.newBufferedReader(cfgPath, StandardCharsets.UTF_8)) {
                old = GSON.fromJson(r, JsonObject.class);
            } catch (Exception ignored) {
            }
        }

        JsonObject root = new JsonObject();
        root.addProperty("port", bot.webuiPort);
        root.addProperty("enabled", true);

        JsonObject auth = new JsonObject();
        auth.addProperty("enabled", false);
        auth.addProperty("username", "admin");
        // 认证已由 Manager 统一关闭，这里只需要一个占位密码；已存在就沿用，避免每次启动改动
        String pwd = "managed-by-bot-manager";
        int tokenExpiry = 86400000;
        if (old != null && old.has("auth") && old.get("auth").isJsonObject()) {
            JsonObject oa = old.getAsJsonObject("auth");
            if (oa.has("password") && !oa.get("password").isJsonNull()
                && !oa.get("password").getAsString().isBlank()) {
                pwd = oa.get("password").getAsString();
            }
            if (oa.has("tokenExpiry")) {
                try {
                    tokenExpiry = oa.get("tokenExpiry").getAsInt();
                } catch (Exception ignored) {
                }
            }
        }
        auth.addProperty("password", pwd);
        auth.addProperty("tokenExpiry", tokenExpiry);
        root.add("auth", auth);

        JsonObject ab = new JsonObject();
        ab.addProperty("enabled", true);
        ab.addProperty("rewriteOfflineUuid", true);
        ab.addProperty("bypassAuthenticateHello", true);
        root.add("authBypasser", ab);

        try (Writer w = Files.newBufferedWriter(cfgPath, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    public void start(BotStore.Bot bot, Path botsRoot) throws IOException {
        if (running(bot.name)) return;
        provision(bot, botsRoot);
        Path dir = bot.dir(botsRoot);
        Files.createDirectories(dir);

        String xmx = System.getProperty("bot.heap", "1500M");
        var cmd = new java.util.ArrayList<String>();
        cmd.add(javaExe.toString());
        cmd.add("-Xmx" + xmx);
        cmd.add("-Xms256M");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dstdout.encoding=UTF-8");
        // ZenithProxy 支持显式指定配置路径；用绝对路径避免工作目录歧义
        cmd.add("-Dzenith.config.file=" + dir.resolve("config.json").toAbsolutePath());
        cmd.add("-Dzenith.launch.config.file=" + dir.resolve("launch_config.json").toAbsolutePath());
        for (String a : extraJvmArgs.trim().split("\\s+")) {
            if (!a.isBlank()) cmd.add(a);
        }
        cmd.add("-jar");
        cmd.add(zenithJar.toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        // 关掉 ZenithProxy 的自动更新：它会把 app/ZenithProxy.jar 换回上游版本，
        // 我们的配置热重载补丁就没了。inDevEnv() 只被 AutoUpdater 用来做这个判断。
        pb.environment().put("ZENITH_DEV", "1");
        // ZenithProxy 的终端线程在 stdin EOF 时会主动关机，所以这里保持 stdin 管道打开
        // （默认就是 PIPE，我们不写也不关，子进程永远读不到 EOF）。
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("console.log").toFile()));

        Log.info("启动 bot [" + bot.name + "] proxy=" + bot.proxyPort + " webui=" + bot.webuiPort);
        Process p = pb.start();
        procs.put(bot.name, p);
        startedAt.put(bot.name, System.currentTimeMillis());
        p.onExit().thenAccept(proc -> {
            Log.info("bot [" + bot.name + "] 进程退出，exit=" + proc.exitValue());
            procs.remove(bot.name);
            startedAt.remove(bot.name);
        });
    }

    public boolean stop(String name) {
        Process p = procs.get(name);
        if (p == null || !p.isAlive()) return false;
        Log.info("停止 bot [" + name + "]");
        p.destroy();
        try {
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                Log.warn("bot [" + name + "] 未在 8 秒内退出，强制结束");
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        procs.remove(name);
        startedAt.remove(name);
        return true;
    }

    /** WebUI 端口是否已可连接。 */
    public static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 等到端口可连接，或超时。 */
    public static boolean waitPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen(port)) return true;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public void stopAll() {
        for (String name : procs.keySet().toArray(new String[0])) {
            stop(name);
        }
    }
}

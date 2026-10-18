package com.zenith.manager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * bots/&lt;name&gt;/ 目录的读写。每个 bot 一个独立工作目录，ZenithProxy 在该目录下
 * 生成 config.json、plugins/、log/ 等，进程级互相隔离。
 */
public final class BotStore {

    /** bot 名会作为目录名和 URL 路径段，只允许安全字符。 */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$");

    /** 新建 bot 的默认目标服务器 */
    public static final String DEFAULT_SERVER = "2b2t.org";
    public static final int DEFAULT_SERVER_PORT = 25565;

    /** 服务器地址只允许 host 形式的安全字符（端口单独存），避免写进 JSON 后出问题。 */
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._-]{1,255}$");

    public static boolean validHost(String host) {
        return host != null && HOST.matcher(host).matches();
    }

    public static boolean validPort(int port) {
        return port > 0 && port < 65536;
    }

    /**
     * 离线账号固定 UUID：和原版服务器的 {@code OfflinePlayer:<name>} 规则一致。
     * <p>
     * 不传 UUID 时 ZenithProxy 每次启动都会随机生成一个，同一个服务器上会被当成新玩家，
     * 白名单/领地/背包全丢，所以这里按名字算出一个稳定的值。
     */
    public static String offlineUuidFor(String username) {
        return java.util.UUID.nameUUIDFromBytes(
            ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /** 合法的账号类型，必须与 ZenithProxy 的 Config.Authentication.AccountType 对应。 */
    public static boolean validAccountType(String type) {
        return "device_code".equals(type) || "device_code_without_device_token".equals(type)
            || "msa".equals(type) || "prism".equals(type) || "offline".equals(type);
    }

    public static final class Bot {
        public String name;
        public int proxyPort;
        public int webuiPort;
        /** 上次退出时是否处于运行态 —— 用于启动时自动拉起 */
        public boolean autoStart = true;

        /** 机器人要连的目标服务器（2b2t 或其它），写入 config.json 的 client.server.address */
        public String server = DEFAULT_SERVER;
        public int serverPort = DEFAULT_SERVER_PORT;
        /** 子进程启动后是否自动连接目标服务器（写入 config.json 的 client.autoConnect） */
        public boolean autoConnect = false;

        /**
         * 账号相关。全部为 null 表示"这个 bot 的账号不由管理器接管"（老版本建的 bot
         * 就是这样），启动时不会去动 config.json 的 authentication 段。
         */
        public String accountType = null;
        /** 离线模式的游戏名；正版模式留空 */
        public String accountUsername = null;
        /** MSA 邮箱登录用 */
        public String accountEmail = null;
        public String accountPassword = null;
        /** 离线 UUID；留空则在首次保存时按游戏名生成固定值 */
        public String offlineUuid = null;

        public Bot() {
        }

        public Bot(String name, int proxyPort, int webuiPort) {
            this.name = name;
            this.proxyPort = proxyPort;
            this.webuiPort = webuiPort;
        }

        public Path dir(Path botsRoot) {
            return botsRoot.resolve(name);
        }
    }

    private final Path root;
    private final com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    public BotStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public List<Bot> list() {
        List<Bot> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var stream = Files.list(root)) {
            stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                .forEach(dir -> {
                    Bot b = read(dir);
                    if (b != null) out.add(b);
                });
        } catch (IOException e) {
            Log.warn("扫描 bots 目录失败: " + e.getMessage());
        }
        return out;
    }

    public Bot get(String name) {
        if (!validName(name)) return null;
        Path dir = root.resolve(name);
        if (!Files.isDirectory(dir)) return null;
        return read(dir);
    }

    private Bot read(Path dir) {
        Path f = dir.resolve("bot.json");
        String name = dir.getFileName().toString();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                Bot b = gson.fromJson(r, Bot.class);
                if (b != null) {
                    b.name = name;
                    return b;
                }
            } catch (Exception e) {
                Log.warn("读取 " + f + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    public void save(Bot bot) throws IOException {
        Path dir = root.resolve(bot.name);
        Files.createDirectories(dir);
        try (Writer w = Files.newBufferedWriter(dir.resolve("bot.json"), StandardCharsets.UTF_8)) {
            gson.toJson(bot, w);
        }
    }

    public Bot create(String name) throws IOException {
        return create(name, DEFAULT_SERVER, DEFAULT_SERVER_PORT, false);
    }

    public Bot create(String name, String server, int serverPort, boolean autoConnect) throws IOException {
        if (!validName(name)) throw new IOException("名称只能包含字母、数字、下划线、连字符（1-32 位）");
        if (Files.exists(root.resolve(name))) throw new IOException("bot 已存在: " + name);
        String host = (server == null || server.isBlank()) ? DEFAULT_SERVER : server.trim();
        if (!validHost(host)) throw new IOException("服务器地址不合法: " + host);
        if (!validPort(serverPort)) throw new IOException("服务器端口不合法: " + serverPort);
        int proxyPort = freePort(25565);
        int webuiPort = freePort(18080);
        Bot b = new Bot(name, proxyPort, webuiPort);
        b.server = host;
        b.serverPort = serverPort;
        b.autoConnect = autoConnect;
        Files.createDirectories(b.dir(root).resolve("plugins"));
        save(b);
        return b;
    }

    public void delete(String name) throws IOException {
        Bot b = get(name);
        if (b == null) throw new IOException("bot 不存在: " + name);
        Path dir = b.dir(root);
        // 递归删除
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    Log.warn("删除失败 " + p + ": " + e.getMessage());
                }
            });
        }
    }

    /** 从 start 开始向上找一个没被占用的端口。 */
    public static int freePort(int start) {
        for (int p = start; p < start + 200; p++) {
            if (available(p)) return p;
        }
        throw new IllegalStateException("找不到可用端口，起点 " + start);
    }

    public static boolean available(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

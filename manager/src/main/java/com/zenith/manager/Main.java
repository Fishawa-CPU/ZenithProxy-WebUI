package com.zenith.manager;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动器入口。
 * <p>
 * 一个进程负责：管理 N 个 ZenithProxy 子进程 + 提供唯一的 Web 入口。
 * 所有 bot 的内部端口都绑定 127.0.0.1，用户只会接触到一个地址。
 */
public final class Main {

    private static Path home;
    private static int port = 9000;

    public static void main(String[] args) throws Exception {
        boolean openBrowser = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--no-browser" -> openBrowser = false;
                case "--home" -> home = Path.of(args[++i]).toAbsolutePath();
                case "--help", "-h" -> {
                    System.out.println("""
                        ZenithProxy Bot Manager
                          用法: BotManager [选项]
                          --port <n>       管理界面端口（默认 9000）
                          --home <dir>     应用目录（默认 jar 所在目录）
                          --no-browser     启动后不自动打开浏览器
                        """);
                    return;
                }
                default -> {
                    System.out.println("未知参数: " + args[i]);
                    return;
                }
            }
        }

        home = resolveHome();
        Log.init(home.resolve("manager.log"));

        Path botsRoot = home.resolve("bots");
        Files.createDirectories(botsRoot);

        Path zenithJar = locateZenithJar();
        if (zenithJar == null) {
            Log.error("找不到 ZenithProxy.jar（应位于 " + home + " 或当前目录）");
            System.exit(1);
        }
        Path javaExe = locateJava();

        Log.info("应用目录: " + home);
        Log.info("ZenithProxy: " + zenithJar.getFileName());
        Log.info("运行时: " + javaExe);

        BotStore store = new BotStore(botsRoot);
        BotProcess process = new BotProcess(zenithJar, javaExe, System.getProperty("bot.jvmArgs", ""), home);

        Gateway gw = new Gateway(store, process, port);
        try {
            gw.start();
        } catch (java.io.IOException e) {
            Log.error("端口 " + port + " 被占用，可用 --port 指定其他端口");
            System.exit(1);
        }

        // 启动上次处于运行态的 bot
        List<BotStore.Bot> bots = store.list();
        if (bots.isEmpty()) {
            Log.info("还没有 bot。请打开管理界面新建。");
        }
        for (BotStore.Bot b : bots) {
            if (b.autoStart) {
                try {
                    process.start(b, botsRoot);
                } catch (Exception e) {
                    Log.error("启动 " + b.name + " 失败", e);
                }
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.info("正在关闭所有 bot…");
            process.stopAll();
            gw.stop();
        }));

        String uiUrl = "http://127.0.0.1:" + port + "/";

        // 托盘：关掉界面窗口后能从托盘退出，而不是只能杀进程
        Tray tray = new Tray();
        tray.install(uiUrl, () -> openBrowser(uiUrl), () -> {
            Log.info("从托盘退出");
            tray.remove();
            process.stopAll();
            gw.stop();
            System.exit(0);
        });

        if (openBrowser) {
            openBrowser(uiUrl);
        }

        Log.info("就绪。可从系统托盘退出。");
        Thread.currentThread().join();
    }

    private static Path resolveHome() {
        if (home != null) return home;
        try {
            Path p = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jarDir = Files.isRegularFile(p) ? p.getParent() : p;
            // jpackage 布局：<home>/app/BotManager.jar，应用根目录是 app 的父目录
            if (jarDir != null && jarDir.getFileName() != null
                && jarDir.getFileName().toString().equals("app")
                && jarDir.getParent() != null) {
                return jarDir.getParent();
            }
            return jarDir == null ? Path.of(".").toAbsolutePath() : jarDir;
        } catch (Exception e) {
            return Path.of(".").toAbsolutePath();
        }
    }

    private static Path locateZenithJar() {
        List<Path> candidates = new ArrayList<>();
        candidates.add(home.resolve("ZenithProxy.jar"));
        candidates.add(home.resolve("lib/ZenithProxy.jar"));
        candidates.add(home.resolve("app/ZenithProxy.jar"));
        candidates.add(Path.of("ZenithProxy.jar").toAbsolutePath());
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return null;
    }

    private static Path locateJava() {
        Path bundled = home.resolve("jre/bin/java.exe");
        if (Files.isRegularFile(bundled)) return bundled;
        Path bundledUnix = home.resolve("jre/bin/java");
        if (Files.isRegularFile(bundledUnix)) return bundledUnix;
        String home = System.getProperty("java.home");
        Path exe = Path.of(home, "bin", "java.exe");
        if (Files.isRegularFile(exe)) return exe;
        return Path.of(home, "bin", "java");
    }

    /**
     * 用 Chromium 内核的应用窗口打开界面。
     * <p>
     * 特意不走 JavaFX WebView：那是十多年前的 WebKit，渲染不了原 WebUI 使用的
     * 现代 CSS（嵌套变量、grid、:has 等），之前的"界面错乱"就是这个原因。
     * Edge 的 --app 模式是一个无地址栏的独立窗口，渲染与 Chrome 完全一致。
     */
    private static void openBrowser(String url) {
        for (String exe : new String[]{
            System.getenv("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            System.getenv("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe",
            System.getenv("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
            System.getenv("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe"
        }) {
            if (exe == null || exe.startsWith("null")) continue;
            if (!Files.isRegularFile(Path.of(exe))) continue;
            try {
                new ProcessBuilder(exe,
                    "--app=" + url,
                    "--window-size=1360,880",
                    "--user-data-dir=" + home.resolve("profile").toAbsolutePath(),
                    "--no-first-run",
                    "--no-default-browser-check")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
                Log.info("已打开应用窗口");
                return;
            } catch (Exception e) {
                Log.warn("打开应用窗口失败: " + e.getMessage());
            }
        }
        // 兜底：系统默认浏览器
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception e) {
            Log.warn("未能自动打开浏览器，请手动访问 " + url);
        }
    }
}

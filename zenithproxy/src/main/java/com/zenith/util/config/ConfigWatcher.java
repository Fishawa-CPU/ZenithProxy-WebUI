package com.zenith.util.config;

import com.zenith.Globals;
import com.zenith.util.Wait;

import java.io.File;

/**
 * 盯着 {@code config.json}，文件被外部改写后自动热加载。
 * <p>
 * 不用 {@code WatchService}：ZenithProxy 自己保存配置是"写临时文件 + move"，
 * 而管理器那边也是整文件覆盖，轮询 mtime+size 对这两种写法都稳。
 * <p>
 * 自己保存时调 {@link #markSaved()} 打个标记，避免把刚写下去的同一份内容再加载一遍。
 */
public final class ConfigWatcher {

    private static final long POLL_MS = 400;
    private static final long DEBOUNCE_MS = 300;
    private static final long SUPPRESS_AFTER_SAVE_MS = 1500;

    private static volatile long lastSavedAt = 0L;
    private static long lastMtime = -1L;
    private static long lastSize = -1L;
    private static long pendingAt = 0L;
    private static boolean started = false;

    private ConfigWatcher() {
    }

    /** 自己写盘前调用，避免自触发。 */
    public static void markSaved() {
        lastSavedAt = System.currentTimeMillis();
    }

    public static synchronized void start() {
        if (started) return;
        started = true;

        final File file = Globals.CONFIG_FILE;
        lastMtime = file.lastModified();
        lastSize = file.length();

        Thread thread = new Thread(ConfigWatcher::loop, "ZenithProxy Config Watcher");
        thread.setDaemon(true);
        thread.start();
        Globals.DEFAULT_LOG.debug("Watching {} for changes", file.getAbsolutePath());
    }

    private static void loop() {
        Wait.waitMs(3000); // 等启动流程走完再开始监听
        while (true) {
            try {
                Wait.waitMs((int) POLL_MS);
                tick();
            } catch (Throwable t) {
                Globals.DEFAULT_LOG.debug("config watcher tick failed: {}", t.toString());
            }
        }
    }

    private static void tick() {
        final File file = Globals.CONFIG_FILE;
        if (!file.isFile()) return;

        final long mtime = file.lastModified();
        final long size = file.length();
        final long now = System.currentTimeMillis();

        // 刚发生变化：先记下，等文件稳定（防抖）
        if (mtime != lastMtime || size != lastSize) {
            lastMtime = mtime;
            lastSize = size;
            pendingAt = now;
            return;
        }

        if (pendingAt == 0L || now - pendingAt < DEBOUNCE_MS) return;
        pendingAt = 0L;

        // 自己保存触发的变更不重复加载
        if (now - lastSavedAt < SUPPRESS_AFTER_SAVE_MS) return;

        reload();
    }

    private static void reload() {
        final long start = System.currentTimeMillis();
        try {
            final Config fresh = ConfigReloader.loadFresh();
            final boolean changed = ConfigReloader.merge(fresh, Globals.CONFIG);
            if (changed) {
                Globals.DEFAULT_LOG.info("ConfigReload: applied in {}ms (no restart needed)",
                    System.currentTimeMillis() - start);
            } else {
                Globals.DEFAULT_LOG.info("ConfigReload: no-op, parsed content identical");
            }
        } catch (Throwable t) {
            Globals.DEFAULT_LOG.error("ConfigReload: failed - {}", t.toString());
        }
    }
}

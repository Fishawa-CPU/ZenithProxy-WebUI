package com.zenith.manager;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 极简日志：同时输出到控制台和 manager.log。
 * <p>
 * 不用 {@code System.setOut} 重定向 —— jpackage 打包后的无控制台进程里，
 * 包装 stdout 的方式不可靠（写文件偶尔静默失败），这里直接自己持有文件句柄。
 */
public final class Log {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static PrintWriter file;

    private Log() {
    }

    public static synchronized void init(Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            file = new PrintWriter(Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND), true);
        } catch (Exception e) {
            System.out.println("[WARN] 无法写入 " + logFile + ": " + e.getMessage());
            file = null;
        }
    }

    private static synchronized void out(String level, String msg) {
        String line = "[" + LocalTime.now().format(TS) + "] [" + level + "] " + msg;
        System.out.println(line);
        if (file != null) {
            file.println(line);
        }
    }

    public static void info(String msg) {
        out("INFO", msg);
    }

    public static void warn(String msg) {
        out("WARN", msg);
    }

    public static void error(String msg) {
        out("ERROR", msg);
    }

    public static void error(String msg, Throwable t) {
        out("ERROR", msg);
        if (t != null) {
            System.out.println(t);
            if (file != null) {
                t.printStackTrace(file);
                file.flush();
            }
        }
    }
}

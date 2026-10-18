package com.zenith.manager;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * 系统托盘。关掉界面窗口后 Manager 仍在后台跑 bot，
 * 必须留一个明确的出口，否则用户只能去任务管理器结束进程。
 */
public final class Tray {

    private TrayIcon icon;

    public void install(String url, Runnable openUi, Runnable quit) {
        if (!SystemTray.isSupported()) {
            Log.warn("系统托盘不可用，关闭窗口后请用任务管理器结束本程序");
            return;
        }
        try {
            PopupMenu menu = new PopupMenu();

            MenuItem open = new MenuItem("打开管理界面");
            open.addActionListener(e -> openUi.run());
            menu.add(open);

            menu.addSeparator();

            MenuItem stopAll = new MenuItem("停止所有 Bot 并退出");
            stopAll.addActionListener(e -> {
                quit.run();
            });
            menu.add(stopAll);

            icon = new TrayIcon(drawIcon(), "ZenithProxy Bot Manager", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openUi.run());
            SystemTray.getSystemTray().add(icon);
            Log.info("已在系统托盘运行，右键图标可退出");
        } catch (Exception e) {
            Log.warn("安装托盘图标失败: " + e.getMessage());
        }
    }

    public void remove() {
        if (icon != null) {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Exception ignored) {
            }
            icon = null;
        }
    }

    public void message(String title, String text) {
        if (icon != null) {
            try {
                icon.displayMessage(title, text, TrayIcon.MessageType.INFO);
            } catch (Exception ignored) {
            }
        }
    }

    /** 程序内画一个图标，省去外部图片依赖。 */
    private static Image drawIcon() {
        int s = 64;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x8a, 0xad, 0xf4));
        g.fillRoundRect(0, 0, s, s, 18, 18);
        g.setColor(new Color(0x1e, 0x20, 0x30));
        g.setFont(new java.awt.Font("Segoe UI", java.awt.Font.BOLD, 42));
        var fm = g.getFontMetrics();
        String t = "Z";
        int w = fm.stringWidth(t);
        g.drawString(t, (s - w) / 2, (s - fm.getHeight()) / 2 + fm.getAscent() - 3);
        g.dispose();
        return img;
    }
}

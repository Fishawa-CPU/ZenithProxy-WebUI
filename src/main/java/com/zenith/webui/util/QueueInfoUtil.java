package com.zenith.webui.util;

import java.util.ArrayList;
import java.util.List;

/**
 * 排队信息工具类
 * 用于获取服务器排队状态
 * - 未连接时队列信息为空
 * - 已连接但不在排队中时显示"已进入"
 * - 支持多服务器扩展（通过 QueueDetector 接口）
 */
public class QueueInfoUtil {

    private static final List<QueueDetector> detectors = new ArrayList<>();

    static {
        // 注册服务器队列检测器
        detectors.add(new Queue3c3uDetector());
        // 未来可添加更多服务器检测器:
        // detectors.add(new QueueOtherServerDetector());
    }

    private static volatile String queuePos = "";
    private static volatile int onlinePlayers = 0;
    private static volatile int positionInQueue = -1;
    private static volatile boolean connected = false;
    private static volatile String currentServer = "";
    private static volatile QueueDetector activeDetector = null;

    private QueueInfoUtil() {}

    /**
     * 根据服务器地址获取对应的队列检测器
     * @param serverAddress 服务器地址
     * @return 匹配的检测器，如果没有匹配则返回null
     */
    private static QueueDetector findDetector(String serverAddress) {
        for (QueueDetector detector : detectors) {
            if (detector.supports(serverAddress)) {
                return detector;
            }
        }
        return null;
    }

    /**
     * 检查是否支持该服务器的队列检测
     * @param serverAddress 服务器地址
     * @return true表示支持
     */
    public static boolean isSupported(String serverAddress) {
        return findDetector(serverAddress) != null;
    }

    /**
     * 检查目标服务器是否为3c3u.org
     */
    public static boolean isTargetServer3c3u(String serverAddress) {
        QueueDetector detector = findDetector(serverAddress);
        return detector != null && "3c3u.org".equals(detector.getServerId());
    }

    /**
     * 是否已连接到服务器
     */
    public static boolean isConnected() {
        return connected;
    }

    /**
     * 设置连接状态
     */
    public static void setConnected(boolean isConnected, String serverAddress) {
        connected = isConnected;
        currentServer = isConnected ? (serverAddress != null ? serverAddress : "") : "";
        activeDetector = isConnected ? findDetector(currentServer) : null;
        if (!isConnected) {
            reset();
        }
    }

    /**
     * 是否在排队中
     */
    public static boolean isQueuing() {
        return connected && positionInQueue > 0;
    }

    /**
     * 是否已进入服务器（不在排队中）
     */
    public static boolean hasEnteredServer() {
        return connected && positionInQueue == -1;
    }

    /**
     * 获取在线人数
     */
    public static int getOnlinePlayers() {
        return onlinePlayers;
    }

    /**
     * 获取当前排队位置
     * @return -1 表示已进入服务器或不在排队中
     */
    public static int getPositionInQueue() {
        return positionInQueue;
    }

    /**
     * 获取原始排队字符串
     */
    public static String getQueuePos() {
        return queuePos;
    }

    /**
     * 获取当前服务器地址
     */
    public static String getCurrentServer() {
        return currentServer;
    }

    /**
     * 获取当前激活的检测器
     */
    public static QueueDetector getActiveDetector() {
        return activeDetector;
    }

    /**
     * 更新排队信息
     * @param position 排队位置，-1表示已进入服务器
     * @param online 在线人数
     */
    public static void updateQueueInfo(int position, int online) {
        if (!connected) {
            reset();
            return;
        }

        positionInQueue = position;
        onlinePlayers = online;

        if (positionInQueue == -1) {
            queuePos = "已进入";
        } else if (positionInQueue > 0) {
            queuePos = "排队中: 第 " + positionInQueue + " 位 | 在线人数: " + onlinePlayers;
        } else {
            queuePos = "";
        }
    }

    /**
     * 解析排队信息字符串
     * 使用当前激活的检测器进行解析
     */
    public static void parseQueueInfo(String text) {
        if (text == null || text.isEmpty() || !connected || activeDetector == null) {
            reset();
            return;
        }

        positionInQueue = activeDetector.parseQueuePosition(text);
        onlinePlayers = activeDetector.parseOnlinePlayers(text);

        if (positionInQueue == -1) {
            queuePos = "已进入";
        } else if (positionInQueue > 0) {
            queuePos = "排队中: 第 " + positionInQueue + " 位 | 在线人数: " + onlinePlayers;
        } else {
            queuePos = "";
        }
    }

    /**
     * 重置所有状态
     */
    public static void reset() {
        queuePos = "";
        onlinePlayers = 0;
        positionInQueue = -1;
        connected = false;
        currentServer = "";
        activeDetector = null;
    }

    /**
     * 获取排队状态摘要
     */
    public static String getSummary() {
        if (!connected) {
            return "";
        }
        if (positionInQueue == -1) {
            return "已进入";
        }
        if (positionInQueue > 0) {
            return "排队中: 第 " + positionInQueue + " 位 | 在线人数: " + onlinePlayers;
        }
        return "在线人数: " + onlinePlayers;
    }
}

package com.zenith.webui.util;

/**
 * 排队信息工具类
 * 用于获取3c3u.org服务器排队状态
 * - 如果未连接到服务器，队列信息为空
 * - 如果连接后positionInQueue为-1，表示已进入服务器（不在排队中）
 */
public class QueueInfoUtil {

    private static final String TARGET_SERVER = "3c3u.org";
    private static String queuePos = "";
    private static int onlinePlayers = 0;
    private static int positionInQueue = -1;
    private static boolean connected = false;

    private QueueInfoUtil() {}

    /**
     * 检查目标服务器是否为3c3u.org
     */
    public static boolean isTargetServer(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) {
            return false;
        }
        return serverAddress.toLowerCase().contains(TARGET_SERVER.toLowerCase());
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
    public static void setConnected(boolean isConnected) {
        connected = isConnected;
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
            queuePos = "已进入服务器";
        } else if (positionInQueue > 0) {
            queuePos = "排队中: 第 " + positionInQueue + " 位 | 在线人数: " + onlinePlayers;
        } else {
            queuePos = "";
        }
    }

    /**
     * 解析排队信息字符串
     * 格式: <onlinePlayers> | Position in queue: <position>
     */
    public static void parseQueueInfo(String text) {
        if (text == null || text.isEmpty()) {
            reset();
            return;
        }

        if (!connected) {
            reset();
            return;
        }

        try {
            String[] parts = text.split("\\|");
            if (parts.length >= 2) {
                String onlinePart = parts[0].trim();
                String[] onlineSplit = onlinePart.split("\\s+");
                if (onlineSplit.length >= 1) {
                    onlinePlayers = Integer.parseInt(onlineSplit[0].trim());
                }

                String positionPart = parts[1].trim();
                String[] positionSplit = positionPart.split(":");
                if (positionSplit.length >= 2) {
                    positionInQueue = Integer.parseInt(positionSplit[1].trim());
                }
            }
            queuePos = text;
        } catch (Exception e) {
            reset();
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
    }

    /**
     * 获取排队状态摘要
     */
    public static String getSummary() {
        if (!connected) {
            return "";
        }
        if (positionInQueue == -1) {
            return "已进入服务器";
        }
        if (positionInQueue > 0) {
            return "排队中: 第 " + positionInQueue + " 位 | 在线人数: " + onlinePlayers;
        }
        return "在线人数: " + onlinePlayers;
    }
}

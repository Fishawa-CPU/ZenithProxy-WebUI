package com.zenith.webui.util;

/**
 * 3c3u.org 服务器队列检测器
 * 解析格式: <onlinePlayers> | Position in queue: <position>
 */
public class Queue3c3uDetector implements QueueDetector {

    private static final String SERVER_ID = "3c3u.org";
    private static final String SERVER_KEYWORD = "3c3u.org";

    @Override
    public boolean supports(String serverAddress) {
        if (serverAddress == null || serverAddress.isEmpty()) {
            return false;
        }
        return serverAddress.toLowerCase().contains(SERVER_KEYWORD);
    }

    @Override
    public int parseQueuePosition(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        try {
            String[] parts = text.split("\\|");
            if (parts.length >= 2) {
                String positionPart = parts[1].trim();
                String[] positionSplit = positionPart.split(":");
                if (positionSplit.length >= 2) {
                    return Integer.parseInt(positionSplit[1].trim());
                }
            }
        } catch (NumberFormatException e) {
            // 解析失败
        }
        return 0;
    }

    @Override
    public int parseOnlinePlayers(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        try {
            String[] parts = text.split("\\|");
            if (parts.length >= 1) {
                String onlinePart = parts[0].trim();
                String[] onlineSplit = onlinePart.split("\\s+");
                if (onlineSplit.length >= 1) {
                    return Integer.parseInt(onlineSplit[0].trim());
                }
            }
        } catch (NumberFormatException e) {
            // 解析失败
        }
        return 0;
    }

    @Override
    public String getServerId() {
        return SERVER_ID;
    }
}

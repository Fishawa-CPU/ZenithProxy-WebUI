package com.zenith.webui.util;

/**
 * 队列检测器接口
 * 用于扩展不同服务器的队列检测逻辑
 */
public interface QueueDetector {

    /**
     * 检查是否支持该服务器的队列检测
     * @param serverAddress 服务器地址
     * @return true表示支持该服务器的队列检测
     */
    boolean supports(String serverAddress);

    /**
     * 解析队列信息字符串
     * @param text 原始队列信息
     * @return 解析后的队列位置，-1表示已进入服务器，0表示不在队列中
     */
    int parseQueuePosition(String text);

    /**
     * 解析在线人数
     * @param text 原始队列信息
     * @return 在线人数
     */
    int parseOnlinePlayers(String text);

    /**
     * 获取服务器标识
     * @return 服务器标识名称
     */
    String getServerId();
}

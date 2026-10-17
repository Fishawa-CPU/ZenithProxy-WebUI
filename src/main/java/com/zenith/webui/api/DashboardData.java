package com.zenith.webui.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated dashboard payload, serialized with Gson.
 * <p>
 * The public field names are the frontend contract ({@code app.js}), and
 * {@code health}/{@code food} must be emitted as explicit {@code null} when the
 * bot is offline — hence {@code HttpHelper.GSON_NULLS}.
 */
public class DashboardData {
    public boolean connected;
    public boolean inQueue;
    public int queuePosition;
    public int queueOnlinePlayers;
    public String queuePos = "";
    public String version = "";
    public String viaVersion = "";
    public String username = "";
    public String proxyAddress = "";
    public long uptime;
    public SystemInfo system = new SystemInfo();
    public LatencyInfo latency = new LatencyInfo();
    public PlayerInfo player = new PlayerInfo();

    public static class SystemInfo {
        public double cpuLoad = -1;
        public double memoryPercent;
        public double netBytesPerSecIn;
        public double netBytesPerSecOut;
    }

    public static class LatencyInfo {
        public long botLatency = -1;
        public List<PlayerPing> players = new ArrayList<>();
    }

    public static class PlayerPing {
        public String name;
        public long ping;

        public PlayerPing(String name, long ping) {
            this.name = name;
            this.ping = ping;
        }
    }

    public static class PlayerInfo {
        public boolean connected;
        public Float health;
        public Integer food;
    }
}

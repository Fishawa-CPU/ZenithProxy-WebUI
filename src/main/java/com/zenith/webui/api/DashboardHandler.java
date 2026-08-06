package com.zenith.webui.api;

import com.zenith.Proxy;
import com.zenith.webui.WebUIPlugin;
import com.zenith.webui.auth.AuthManager;
import com.zenith.webui.http.HttpHelper;
import com.zenith.webui.queue.QueueParser;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.zenith.Globals.*;

/**
 * Handles /api/dashboard and /api/dashboard/stream endpoints.
 * Builds aggregated proxy status data and pushes it via SSE.
 */
public class DashboardHandler {
    private final AuthManager authManager;
    private ScheduledExecutorService streamExecutor;

    private final AtomicLong lastNetTime = new AtomicLong(0);
    private final AtomicLong lastBytesIn = new AtomicLong(0);
    private final AtomicLong lastBytesOut = new AtomicLong(0);

    public DashboardHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    public void handleDashboard(ChannelHandlerContext ctx) {
        HttpHelper.sendJson(ctx, buildDashboardData());
    }

    public void handleDashboardStream(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response);
        WebUIPlugin.dashboardSseClients.add(ctx.channel());
    }

    public void startStream() {
        if (streamExecutor != null) return;
        streamExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "webui-dashboard-stream");
            t.setDaemon(true);
            return t;
        });
        streamExecutor.scheduleAtFixedRate(() -> {
            try {
                if (WebUIPlugin.dashboardSseClients.isEmpty()
                    && WebUIPlugin.sseClients.isEmpty()) return;
                validateSSETokens();
                if (WebUIPlugin.dashboardSseClients.isEmpty()) return;
                WebUIPlugin.broadcastSSE(WebUIPlugin.dashboardSseClients,
                    HttpHelper.GSON.toJson(buildDashboardData()), false);
            } catch (Exception ignored) {}
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void stopStream() {
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
            streamExecutor = null;
        }
    }

    private void validateSSETokens() {
        WebUIPlugin.sseClientTokens.entrySet().removeIf(entry -> {
            Channel ch = entry.getKey();
            if (!ch.isActive()) return true;
            String username = authManager.validateToken(entry.getValue());
            if (username == null) {
                ch.close();
                WebUIPlugin.sseClients.remove(ch);
                WebUIPlugin.dashboardSseClients.remove(ch);
                return true;
            }
            return false;
        });
    }

    private Map<String, Object> buildDashboardData() {
        Map<String, Object> data = new LinkedHashMap<>();
        try {
            // --- Status ---
            data.put("connected", Proxy.getInstance().isConnected());
            QueueData qd = getQueueData();
            data.put("inQueue", qd.inQueue);
            data.put("queuePosition", qd.position);
            data.put("queueOnlinePlayers", qd.onlinePlayers);
            data.put("queuePos", qd.queuePos);
            data.put("version", MC_VERSION);
            String viaVersionStr = "";
            try {
                var client = Proxy.getInstance().getClient();
                if (client != null && client.isConnected()) {
                    var protoVer = client.getProtocolVersion();
                    if (protoVer != null) {
                        viaVersionStr = "via " + protoVer.getName();
                    }
                }
            } catch (Exception ignored) {}
            data.put("viaVersion", viaVersionStr);
            data.put("username", CONFIG.authentication.username);
            data.put("proxyAddress", CONFIG.server.getProxyAddress());
            data.put("uptime", System.currentTimeMillis() - Proxy.getInstance().getStartTime());

            // --- System ---
            Map<String, Object> sys = new LinkedHashMap<>();
            double cpuLoad = -1;
            try {
                java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                cpuLoad = osBean.getSystemLoadAverage();
                if (cpuLoad >= 0) cpuLoad = Math.round(cpuLoad * 100.0) / 100.0;
            } catch (Exception ignored) {}
            sys.put("cpuLoad", cpuLoad);
            Runtime rt = Runtime.getRuntime();
            long usedMem = rt.totalMemory() - rt.freeMemory();
            sys.put("memoryPercent", Math.round(usedMem * 10000.0 / rt.maxMemory()) / 100.0);
            long now = System.currentTimeMillis();
            long totalBytesIn = 0, totalBytesOut = 0;
            try {
                List<String> lines = Files.readAllLines(Path.of("/proc/net/dev"));
                for (String line : lines) {
                    line = line.trim();
                    if (line.contains(":") && !line.startsWith("Inter") && !line.startsWith("face")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            String[] stats = parts[1].trim().split("\\s+");
                            if (stats.length >= 10) {
                                totalBytesIn += Long.parseLong(stats[0]);
                                totalBytesOut += Long.parseLong(stats[8]);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
            double bytesPerSecIn = 0, bytesPerSecOut = 0;
            long prevTime = lastNetTime.getAndSet(now);
            if (prevTime > 0) {
                double elapsed = (now - prevTime) / 1000.0;
                if (elapsed > 0) {
                    long prevBytesIn = lastBytesIn.getAndSet(totalBytesIn);
                    long prevBytesOut = lastBytesOut.getAndSet(totalBytesOut);
                    bytesPerSecIn = Math.round((totalBytesIn - prevBytesIn) / elapsed * 10.0) / 10.0;
                    bytesPerSecOut = Math.round((totalBytesOut - prevBytesOut) / elapsed * 10.0) / 10.0;
                }
            } else {
                lastBytesIn.set(totalBytesIn);
                lastBytesOut.set(totalBytesOut);
            }
            sys.put("netBytesPerSecIn", bytesPerSecIn);
            sys.put("netBytesPerSecOut", bytesPerSecOut);
            data.put("system", sys);

            // --- Latency ---
            Map<String, Object> lat = new LinkedHashMap<>();
            try {
                var client = Proxy.getInstance().getClient();
                boolean lc = client != null && client.isConnected();
                lat.put("botLatency", lc ? client.getPing() : -1);
                List<Map<String, Object>> players = new ArrayList<>();
                for (var session : Proxy.getInstance().getActiveConnections().getArray()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", session.getName());
                    p.put("ping", session.getPing());
                    players.add(p);
                }
                lat.put("players", players);
            } catch (Exception e) {
                lat.put("botLatency", -1);
                lat.put("players", new ArrayList<>());
            }
            data.put("latency", lat);

            // --- Player ---
            Map<String, Object> pl = new LinkedHashMap<>();
            try {
                var client = Proxy.getInstance().getClient();
                boolean pc = client != null && client.isConnected();
                pl.put("connected", pc);
                if (pc) {
                    var thePlayer = CACHE.getPlayerCache().getThePlayer();
                    pl.put("health", thePlayer != null ? thePlayer.getHealth() : null);
                    pl.put("food", thePlayer != null ? thePlayer.getFood() : null);
                } else {
                    pl.put("health", null);
                    pl.put("food", null);
                }
            } catch (Exception e) {
                pl.put("connected", false);
                pl.put("health", null);
                pl.put("food", null);
            }
            data.put("player", pl);
        } catch (Exception e) {
            data.put("error", e.getMessage());
        }
        return data;
    }

    private record QueueData(boolean inQueue, int position, int onlinePlayers, String queuePos) {}

    private static QueueData getQueueData() {
        String server = CONFIG.client.server.address;
        if (server == null) {
            return new QueueData(false, 0, 0, "");
        }

        String serverLower = server.toLowerCase();

        if (serverLower.contains("3c3u.org")) {
            return new QueueData(
                QueueParser.is3cQueue(),
                QueueParser.positionInQueue > 0 ? QueueParser.positionInQueue : 0,
                QueueParser.onlinePlayers,
                QueueParser.queuePos
            );
        }

        if (serverLower.contains("2b2t.org")) {
            return new QueueData(
                Proxy.getInstance().isInQueue(),
                Proxy.getInstance().getQueuePosition(),
                0,
                ""
            );
        }

        return new QueueData(false, 0, 0, "");
    }
}

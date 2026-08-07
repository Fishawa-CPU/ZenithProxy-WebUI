package com.zenith.webui.api;

import com.zenith.Proxy;
import com.zenith.webui.WebUIPlugin;
import com.zenith.webui.http.HttpHelper;
import com.zenith.webui.queue.QueueParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private ScheduledExecutorService streamExecutor;

    private final AtomicLong lastNetTime = new AtomicLong(0);
    private final AtomicLong lastBytesIn = new AtomicLong(0);
    private final AtomicLong lastBytesOut = new AtomicLong(0);

    public DashboardHandler() {
    }

    public void handleDashboard(ChannelHandlerContext ctx) {
        HttpHelper.sendJsonString(ctx, buildDashboardJson());
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
                if (WebUIPlugin.dashboardSseClients.isEmpty()) return;
                pushDashboardHeartbeat();
            } catch (Exception ignored) {}
        }, 0, 1, TimeUnit.SECONDS);
    }

    public void stopStream() {
        if (streamExecutor != null) {
            streamExecutor.shutdownNow();
            streamExecutor = null;
        }
    }

    /**
     * Pure heartbeat push — no token validation. Token validity is enforced
     * once at SSE connection setup and event-driven on logout/password-change
     * (see {@link com.zenith.webui.auth.AuthManager#invalidateToken}).
     */
    private void pushDashboardHeartbeat() {
        String json = buildDashboardJson();
        String frame = "data: " + json + "\n\n";
        // Use retainedDuplicate pattern — one allocation for all clients (same as broadcastSSE)
        ByteBuf template = Unpooled.copiedBuffer(frame, CharsetUtil.UTF_8);
        try {
            List<Channel> dead = new ArrayList<>(2);
            for (Channel ch : WebUIPlugin.dashboardSseClients) {
                if (!ch.isActive()) {
                    dead.add(ch);
                    continue;
                }
                try {
                    ch.writeAndFlush(new DefaultHttpContent(template.retainedDuplicate()))
                        .addListener(f -> { if (!f.isSuccess()) ch.close(); });
                } catch (Exception e) {
                    ch.close();
                    dead.add(ch);
                }
            }
            if (!dead.isEmpty()) {
                WebUIPlugin.dashboardSseClients.removeAll(dead);
            }
        } finally {
            template.release();
        }
    }

    /**
     * Build the dashboard JSON string directly, bypassing Gson and intermediate
     * Map/List allocations. Called every second by the SSE heartbeat — zero
     * allocation on the serialization path (only the data-fetching part creates
     * transient objects that are GC-able immediately).
     */
    private String buildDashboardJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');
        // --- Status ---
        sb.append("\"connected\":").append(Proxy.getInstance().isConnected()).append(',');
        QueueData qd = getQueueData();
        sb.append("\"inQueue\":").append(qd.inQueue).append(',');
        sb.append("\"queuePosition\":").append(qd.position).append(',');
        sb.append("\"queueOnlinePlayers\":").append(qd.onlinePlayers).append(',');
        sb.append("\"queuePos\":\""); HttpHelper.escapeJson(sb, qd.queuePos); sb.append("\",");
        sb.append("\"version\":\""); HttpHelper.escapeJson(sb, MC_VERSION); sb.append("\",");
        String viaVersionStr = "";
        try {
            var client = Proxy.getInstance().getClient();
            if (client != null && client.isConnected()) {
                var protoVer = client.getProtocolVersion();
                if (protoVer != null) viaVersionStr = "via " + protoVer.getName();
            }
        } catch (Exception ignored) {}
        sb.append("\"viaVersion\":\""); HttpHelper.escapeJson(sb, viaVersionStr); sb.append("\",");
        sb.append("\"username\":\""); HttpHelper.escapeJson(sb, CONFIG.authentication.username); sb.append("\",");
        sb.append("\"proxyAddress\":\""); HttpHelper.escapeJson(sb, CONFIG.server.getProxyAddress()); sb.append("\",");
        sb.append("\"uptime\":").append(System.currentTimeMillis() - Proxy.getInstance().getStartTime()).append(',');

        // --- System ---
        sb.append("\"system\":{");
        double cpuLoad = -1;
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            cpuLoad = osBean.getSystemLoadAverage();
            if (cpuLoad >= 0) cpuLoad = Math.round(cpuLoad * 100.0) / 100.0;
        } catch (Exception ignored) {}
        sb.append("\"cpuLoad\":").append(cpuLoad).append(',');
        Runtime rt = Runtime.getRuntime();
        long usedMem = rt.totalMemory() - rt.freeMemory();
        sb.append("\"memoryPercent\":").append(Math.round(usedMem * 10000.0 / rt.maxMemory()) / 100.0).append(',');
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
        sb.append("\"netBytesPerSecIn\":").append(bytesPerSecIn).append(',');
        sb.append("\"netBytesPerSecOut\":").append(bytesPerSecOut).append("},");

        // --- Latency ---
        sb.append("\"latency\":{");
        try {
            var client = Proxy.getInstance().getClient();
            boolean lc = client != null && client.isConnected();
            sb.append("\"botLatency\":").append(lc ? client.getPing() : -1).append(",\"players\":[");
            boolean first = true;
            for (var session : Proxy.getInstance().getActiveConnections().getArray()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\""); HttpHelper.escapeJson(sb, session.getName());
                sb.append("\",\"ping\":").append(session.getPing()).append('}');
            }
            sb.append(']');
        } catch (Exception e) {
            sb.append("\"botLatency\":-1,\"players\":[]");
        }
        sb.append("},");

        // --- Player ---
        sb.append("\"player\":{");
        try {
            var client = Proxy.getInstance().getClient();
            boolean pc = client != null && client.isConnected();
            sb.append("\"connected\":").append(pc);
            if (pc) {
                var thePlayer = CACHE.getPlayerCache().getThePlayer();
                sb.append(",\"health\":");
                if (thePlayer != null) sb.append(thePlayer.getHealth());
                else sb.append("null");
                sb.append(",\"food\":");
                if (thePlayer != null) sb.append(thePlayer.getFood());
                else sb.append("null");
            } else {
                sb.append(",\"health\":null,\"food\":null");
            }
        } catch (Exception e) {
            sb.append("\"connected\":false,\"health\":null,\"food\":null");
        }
        sb.append('}');

        sb.append('}');
        return sb.toString();
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

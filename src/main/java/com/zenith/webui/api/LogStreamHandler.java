package com.zenith.webui.api;

import com.google.gson.JsonObject;
import com.zenith.webui.WebUIPlugin;
import com.zenith.webui.http.HttpHelper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.Map;

/**
 * Handles /api/logs/stream — Server-Sent Events stream of log messages.
 */
public class LogStreamHandler {

    public void handleLogStream(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.writeAndFlush(response);

        // Push recent history first so the client sees existing logs
        for (Map<String, Object> entry : WebUIPlugin.logBuffer) {
            JsonObject log = new JsonObject();
            log.addProperty("level", (String) entry.get("level"));
            log.addProperty("logger", (String) entry.get("logger"));
            log.addProperty("message", (String) entry.get("message"));
            log.addProperty("timestamp", (long) entry.get("timestamp"));
            String json = HttpHelper.GSON.toJson(log);
            String sseData = "data: " + json + "\n\n";
            try {
                ByteBuf buf = Unpooled.copiedBuffer(sseData, CharsetUtil.UTF_8);
                ctx.write(new DefaultHttpContent(buf));
            } catch (Exception ignored) {}
        }
        ctx.flush();

        WebUIPlugin.sseClients.add(ctx.channel());
    }
}

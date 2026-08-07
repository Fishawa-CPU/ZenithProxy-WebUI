package com.zenith.webui.api;

import com.zenith.webui.WebUIPlugin;
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

        // Push recent history using pre-serialized SSE frames (no re-serialization)
        // Use approximate size to avoid O(n) ConcurrentLinkedDeque.size() traversal
        StringBuilder sb = new StringBuilder(WebUIPlugin.logBufferApproxSize.get() * 128);
        for (Map<String, Object> entry : WebUIPlugin.logBuffer) {
            String sseFrame = (String) entry.get("sseFrame");
            if (sseFrame != null) {
                sb.append(sseFrame);
            }
        }
        if (sb.length() > 0) {
            try {
                ByteBuf buf = Unpooled.copiedBuffer(sb.toString(), CharsetUtil.UTF_8);
                ctx.writeAndFlush(new DefaultHttpContent(buf));
            } catch (Exception ignored) {}
        }

        WebUIPlugin.sseClients.add(ctx.channel());
    }
}

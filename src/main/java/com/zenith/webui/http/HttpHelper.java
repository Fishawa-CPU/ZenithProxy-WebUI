package com.zenith.webui.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;

/**
 * HTTP response utility methods. Stateless, shared by all handlers.
 */
public final class HttpHelper {
    private HttpHelper() {}

    /** Shared Gson instance (disableHtmlEscaping for clean output). */
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static void sendJson(ChannelHandlerContext ctx, Object obj) {
        byte[] bytes = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        httpResponse.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.write(httpResponse);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    /** Send a pre-built JSON string as response, bypassing Gson serialization. */
    public static void sendJsonString(ChannelHandlerContext ctx, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        HttpResponse httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        httpResponse.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.write(httpResponse);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    /**
     * Append {@code s} to {@code sb}, escaping JSON control characters.
     * Inline (no allocation) for strings that contain no special chars.
     */
    public static void escapeJson(StringBuilder sb, String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
    }

    public static void sendBytes(ChannelHandlerContext ctx, byte[] content, String contentType) {
        ByteBuf buf = Unpooled.wrappedBuffer(content);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.write(response);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    public static void sendNotFound(ChannelHandlerContext ctx) {
        String notFound = "404 Not Found";
        ByteBuf buf = Unpooled.copiedBuffer(notFound, io.netty.util.CharsetUtil.UTF_8);
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        ctx.write(response);
        ctx.write(new DefaultHttpContent(buf));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    public static void sendCorsResponse(ChannelHandlerContext ctx) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, 86400);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    public static String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}

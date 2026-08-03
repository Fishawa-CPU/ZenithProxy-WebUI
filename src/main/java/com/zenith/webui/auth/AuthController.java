package com.zenith.webui.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handles login/logout API endpoints
 */
public class AuthController {
    private static final Gson GSON = new GsonBuilder().create();
    private final AuthManager authManager;

    public AuthController(AuthManager authManager) {
        this.authManager = authManager;
    }

    /**
     * Handle login request
     * POST /api/auth/login
     */
    public void handleLogin(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            String body = request.content().toString(StandardCharsets.UTF_8);
            JsonObject json = GSON.fromJson(body, JsonObject.class);

            if (json == null || !json.has("username") || !json.has("password")) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST,
                    Map.of("success", false, "error", "Missing username or password"));
                return;
            }

            String username = json.get("username").getAsString();
            String password = json.get("password").getAsString();
            String clientIp = getClientIp(ctx, request);

            String token = authManager.authenticate(username, password, clientIp);
            if (token != null) {
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("username", username);
                // Token is only set via HttpOnly cookie, NOT in response body

                String cookie = buildCookie(token, 86400);
                sendJsonWithCookie(ctx, HttpResponseStatus.OK, response, cookie);
            } else {
                sendJson(ctx, HttpResponseStatus.UNAUTHORIZED,
                    Map.of("success", false, "error", "Invalid username or password"));
            }
        } catch (Exception e) {
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                Map.of("success", false, "error", "Login failed"));
        }
    }

    /**
     * Handle logout request
     */
    public void handleLogout(ChannelHandlerContext ctx, FullHttpRequest request) {
        String token = extractToken(request);
        if (token != null) {
            authManager.invalidateToken(token);
        }

        String cookie = buildCookie("", 0);
        sendJsonWithCookie(ctx, HttpResponseStatus.OK,
            Map.of("success", true), cookie);
    }

    /**
     * Handle check auth status
     */
    public void handleStatus(ChannelHandlerContext ctx, FullHttpRequest request) {
        String token = extractToken(request);
        String username = token != null ? authManager.validateToken(token) : null;

        if (username != null) {
            sendJson(ctx, HttpResponseStatus.OK,
                Map.of("authenticated", true, "username", username));
        } else {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED,
                Map.of("authenticated", false));
        }
    }

    private String getClientIp(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Check X-Forwarded-For first (for reverse proxies)
        String xff = request.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        String xri = request.headers().get("X-Real-IP");
        if (xri != null && !xri.isEmpty()) {
            return xri;
        }
        Channel channel = ctx.channel();
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private String extractToken(FullHttpRequest request) {
        String cookieHeader = request.headers().get(HttpHeaderNames.COOKIE);
        if (cookieHeader != null) {
            String[] cookies = cookieHeader.split(";");
            for (String cookie : cookies) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && "webui_token".equals(parts[0].trim())) {
                    return parts[1].trim();
                }
            }
        }

        String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }

    private String buildCookie(String token, int maxAge) {
        if (maxAge > 0) {
            return String.format("webui_token=%s; Path=/; HttpOnly; SameSite=Strict; Max-Age=%d",
                token, maxAge);
        }
        return "webui_token=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0";
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object obj) {
        String json = GSON.toJson(obj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);

        ctx.write(response);
        ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void sendJsonWithCookie(ChannelHandlerContext ctx, HttpResponseStatus status,
                                     Object obj, String cookie) {
        String json = GSON.toJson(obj);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        response.headers().set(HttpHeaderNames.SET_COOKIE, cookie);

        ctx.write(response);
        ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}

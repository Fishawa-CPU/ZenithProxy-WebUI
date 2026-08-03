package com.zenith.webui.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * Handles authentication checks for incoming requests
 */
public class AuthHandler {
    private final AuthManager authManager;

    // Paths that don't require authentication
    private static final String[] PUBLIC_PATHS = {
        "/api/auth/login",
        "/api/auth/logout",
        "/api/auth/status",
        "/login.html",
        "/favicon.ico"
    };

    public AuthHandler(AuthManager authManager) {
        this.authManager = authManager;
    }

    /**
     * Check if request is authenticated
     * @return true if authenticated or public path, false otherwise
     */
    public boolean checkAuth(FullHttpRequest request) {
        String path = request.uri();
        // Strip query string for path matching
        if (path.contains("?")) path = path.substring(0, path.indexOf('?'));

        // Check public paths
        for (String publicPath : PUBLIC_PATHS) {
            if (path.equals(publicPath)) {
                return true;
            }
        }

        // Check for static files (no /api/ prefix)
        if (!path.startsWith("/api/")) {
            return true;
        }

        // Extract token from cookie or header
        String token = extractToken(request);
        if (token == null) return false;

        // Validate token
        return authManager.validateToken(token) != null;
    }

    /**
     * Validate that a file path stays within allowed directories
     * @return true if path is safe, false if traversal detected
     */
    public static boolean isSafePath(String path) {
        if (path == null) return false;
        // Normalize and check for traversal
        String normalized = path.replace("\\", "/");
        if (normalized.contains("..") || normalized.contains("//")) return false;
        if (normalized.contains("\0")) return false;
        // Must start with / and not contain drive letters (Windows)
        if (!normalized.startsWith("/")) return false;
        return true;
    }

    /**
     * Get authenticated username from request
     */
    public String getUsername(FullHttpRequest request) {
        String token = extractToken(request);
        if (token == null) return null;
        return authManager.validateToken(token);
    }

    private String extractToken(FullHttpRequest request) {
        // Try Authorization header first
        String authHeader = request.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // Try cookie
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

        return null;
    }

    /**
     * Send unauthorized response (safe from JSON injection)
     */
    public static void sendUnauthorized(ChannelHandlerContext ctx, String message) {
        JsonObject json = new JsonObject();
        json.add("error", new JsonPrimitive(message));
        json.add("code", new JsonPrimitive(401));
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);

        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"webui\"");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        ctx.write(response);
        ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(bytes)));
        ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Send redirect to login page
     */
    public static void sendRedirect(ChannelHandlerContext ctx, String location) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, location);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}

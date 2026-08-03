package com.zenith.webui.auth;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

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

        // Check public paths
        for (String publicPath : PUBLIC_PATHS) {
            if (path.startsWith(publicPath)) {
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
                String[] parts = cookie.trim().split("=");
                if (parts.length == 2 && "webui_token".equals(parts[0].trim())) {
                    return parts[1].trim();
                }
            }
        }

        return null;
    }

    /**
     * Send unauthorized response
     */
    public static void sendUnauthorized(ChannelHandlerContext ctx, String message) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UNAUTHORIZED);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, "Bearer realm=\"webui\"");

        String json = "{\"error\":\"" + message + "\",\"code\":401}";
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);

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

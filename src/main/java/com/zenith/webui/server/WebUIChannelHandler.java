package com.zenith.webui.server;

import com.zenith.webui.WebUIPlugin;
import com.zenith.webui.api.*;
import com.zenith.webui.auth.AuthController;
import com.zenith.webui.auth.AuthHandler;
import com.zenith.webui.staticfile.StaticFileHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;

import java.util.Map;

/**
 * Netty channel handler — routes incoming HTTP requests to the appropriate handler.
 * Manages SSE client lifecycle and token extraction.
 */
public class WebUIChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final AuthHandler authHandler;
    private final AuthController authController;
    private final CommandHandler commandHandler;
    private final DashboardHandler dashboardHandler;
    private final LogStreamHandler logStreamHandler;
    private final WhitelistHandler whitelistHandler;
    private final StaticFileHandler staticFileHandler;

    public WebUIChannelHandler(
            AuthHandler authHandler,
            AuthController authController,
            CommandHandler commandHandler,
            DashboardHandler dashboardHandler,
            LogStreamHandler logStreamHandler,
            WhitelistHandler whitelistHandler,
            StaticFileHandler staticFileHandler) {
        this.authHandler = authHandler;
        this.authController = authController;
        this.commandHandler = commandHandler;
        this.dashboardHandler = dashboardHandler;
        this.logStreamHandler = logStreamHandler;
        this.whitelistHandler = whitelistHandler;
        this.staticFileHandler = staticFileHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        HttpMethod method = req.method();

        // CORS preflight
        if (HttpMethod.OPTIONS.equals(method)) {
            com.zenith.webui.http.HttpHelper.sendCorsResponse(ctx);
            return;
        }

        // Auth endpoints (public)
        if (path.equals("/api/auth/login") && HttpMethod.POST.equals(method)) {
            authController.handleLogin(ctx, req);
            return;
        }
        if (path.equals("/api/auth/logout") && HttpMethod.POST.equals(method)) {
            authController.handleLogout(ctx, req);
            return;
        }
        if (path.equals("/api/auth/status")) {
            authController.handleStatus(ctx, req);
            return;
        }

        // Check authentication for protected resources
        if (!authHandler.checkAuth(req)) {
            if (path.startsWith("/api/")) {
                AuthHandler.sendUnauthorized(ctx, "Authentication required");
                return;
            }
            AuthHandler.sendRedirect(ctx, "/login.html");
            return;
        }

        // Route to handlers
        try {
            // Only SSE stream endpoints need per-channel token tracking for auth validation
            boolean isSseStream = path.equals("/api/logs/stream") || path.equals("/api/dashboard/stream");
            if (isSseStream) {
                String token = extractToken(req);
                if (token != null) {
                    WebUIPlugin.sseClientTokens.put(ctx.channel(), token);
                }
            }

            switch (path) {
                case "/api/commands" -> commandHandler.handleCommands(ctx, req);
                case "/api/logs/stream" -> logStreamHandler.handleLogStream(ctx);
                case "/api/dashboard" -> dashboardHandler.handleDashboard(ctx);
                case "/api/dashboard/stream" -> dashboardHandler.handleDashboardStream(ctx);
                case "/api/whitelist" -> whitelistHandler.handleWhitelist(ctx, req);
                default -> staticFileHandler.handleStaticFile(ctx, path);
            }
        } catch (Exception e) {
            com.zenith.webui.http.HttpHelper.sendJson(ctx, Map.of("error", "Internal server error"));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        WebUIPlugin.sseClients.remove(ctx.channel());
        WebUIPlugin.dashboardSseClients.remove(ctx.channel());
        WebUIPlugin.sseClientTokens.remove(ctx.channel());
    }

    private static String extractToken(FullHttpRequest request) {
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
}

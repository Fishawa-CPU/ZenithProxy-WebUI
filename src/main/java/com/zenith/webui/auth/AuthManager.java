package com.zenith.webui.auth;

import com.zenith.webui.config.WebUIConfig;
import io.netty.channel.Channel;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AuthManager {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI-Auth");
    private static final int MAX_ATTEMPTS_PER_MINUTE = 5;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;

    private final WebUIConfig.AuthConfig config;
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();
    // Rate limit: key = "ip:minute_timestamp"
    private final Map<String, Integer> rateLimitMap = new ConcurrentHashMap<>();
    /** SSE channels keyed by token — for event-driven disconnect on logout/password-change. */
    private final Map<String, Channel> tokenChannels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public AuthManager(WebUIConfig.AuthConfig config) {
        this.config = config;
        // Background cleanup: expired tokens + stale rate-limit entries every 5 minutes
        cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "webui-auth-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 5, 5, TimeUnit.MINUTES);
        LOG.info("Auth manager initialized, token expiry: {}ms", config.tokenExpiry);
    }

    /** Stop background cleanup (call on plugin unload). */
    public void shutdown() {
        cleanupExecutor.shutdownNow();
    }

    /**
     * Authenticate user with username and password, rate-limited by IP
     * @return token if successful, null otherwise
     */
    public String authenticate(String username, String password, String clientIp) {
        if (username == null || password == null || clientIp == null) return null;

        // IP-based rate limiting (stale entries cleaned by background task)
        String rateKey = clientIp + ":" + (System.currentTimeMillis() / RATE_LIMIT_WINDOW_MS);
        Integer attempts = rateLimitMap.get(rateKey);
        if (attempts != null && attempts >= MAX_ATTEMPTS_PER_MINUTE) {
            LOG.warn("Rate limit exceeded for IP: {}", clientIp);
            return null;
        }
        rateLimitMap.put(rateKey, attempts != null ? attempts + 1 : 1);

        // Constant-time credential comparison
        if (constantTimeEquals(config.username, username) && constantTimeEquals(config.password, password)) {
            String token = generateToken(username);
            LOG.info("User authenticated: {}", username);
            return token;
        }

        LOG.warn("Authentication failed for user: {} from IP: {}", username, clientIp);
        return null;
    }

    /**
     * Validate token. Called infrequently thanks to per-channel auth caching
     * (see {@link AuthHandler#AUTH_CACHE_MS}) and batched SSE validation.
     * @return Username if valid, null otherwise
     */
    public String validateToken(String token) {
        return validateToken(token, System.currentTimeMillis());
    }

    /**
     * Validate token with externally-supplied timestamp — avoids repeated
     * {@link System#currentTimeMillis()} calls when checking multiple tokens.
     */
    public String validateToken(String token, long now) {
        if (token == null || token.isEmpty()) return null;

        TokenInfo tokenInfo = tokens.get(token);
        if (tokenInfo == null) return null;

        if (now > tokenInfo.expiry) {
            tokens.remove(token);
            return null;
        }

        return tokenInfo.username;
    }

    /**
     * Register an SSE channel for a token. The channel will be closed
     * automatically when the token is invalidated (logout / password change).
     */
    public void registerChannel(String token, Channel channel) {
        if (token != null && channel != null) {
            tokenChannels.put(token, channel);
        }
    }

    /** Unregister an SSE channel (called on channelInactive). */
    public void unregisterChannel(Channel channel) {
        if (channel == null) return;
        tokenChannels.values().removeIf(ch -> ch == channel);
    }

    /**
     * Invalidate a specific token (logout). Also closes the associated SSE channel.
     */
    public void invalidateToken(String token) {
        if (token == null) return;
        tokens.remove(token);
        Channel ch = tokenChannels.remove(token);
        if (ch != null) {
            ch.close();
            LOG.info("SSE channel closed for invalidated token");
        }
    }

    /**
     * Invalidate all tokens for a given username (e.g., on password change).
     * Also closes all associated SSE channels.
     */
    public void invalidateAllTokensForUser(String username) {
        if (username == null) return;
        tokens.entrySet().removeIf(e -> {
            if (username.equals(e.getValue().username)) {
                Channel ch = tokenChannels.remove(e.getKey());
                if (ch != null) ch.close();
                return true;
            }
            return false;
        });
        LOG.info("All tokens invalidated for user: {}", username);
    }

    /**
     * Constant-time string comparison to prevent timing attacks
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    private String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + config.tokenExpiry;
        tokens.put(token, new TokenInfo(username, expiry));

        // Cleanup expired tokens periodically
        if (tokens.size() > 100) {
            tokens.entrySet().removeIf(e -> System.currentTimeMillis() > e.getValue().expiry);
        }

        return token;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        // Expired tokens
        tokens.entrySet().removeIf(e -> now > e.getValue().expiry);
        // Stale rate-limit entries (older than 2 windows)
        long cutoff = now - RATE_LIMIT_WINDOW_MS * 2;
        rateLimitMap.entrySet().removeIf(entry -> {
            try {
                long minuteTs = Long.parseLong(entry.getKey().substring(entry.getKey().lastIndexOf(':') + 1));
                return minuteTs * RATE_LIMIT_WINDOW_MS < cutoff;
            } catch (Exception e) {
                return true;
            }
        });
    }

    private static class TokenInfo {
        final String username;
        final long expiry;

        TokenInfo(String username, long expiry) {
            this.username = username;
            this.expiry = expiry;
        }
    }
}

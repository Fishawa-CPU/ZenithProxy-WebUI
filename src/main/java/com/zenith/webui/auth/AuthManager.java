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
    /** SSE channels keyed by token — for event-driven disconnect on token expiry/revocation. */
    private final Map<String, Channel> tokenChannels = new ConcurrentHashMap<>();
    /** Rate limit: key = "ip:minute_timestamp" */
    private final Map<String, Integer> rateLimitMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    // Pre-computed credential bytes for constant-time comparison (avoids per-auth allocation)
    private final byte[] usernameBytes;
    private final byte[] passwordBytes;

    public AuthManager(WebUIConfig.AuthConfig config) {
        this.config = config;
        this.usernameBytes = config.username.getBytes(StandardCharsets.UTF_8);
        this.passwordBytes = config.password.getBytes(StandardCharsets.UTF_8);
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
        int attempts = rateLimitMap.compute(rateKey, (k, v) -> v == null ? 1 : v + 1);
        if (attempts > MAX_ATTEMPTS_PER_MINUTE) {
            LOG.warn("Rate limit exceeded for IP: {}", clientIp);
            return null;
        }

        // Constant-time comparison using pre-computed byte arrays
        if (secureEquals(usernameBytes, username.getBytes(StandardCharsets.UTF_8))
            && secureEquals(passwordBytes, password.getBytes(StandardCharsets.UTF_8))) {
            String token = generateToken(username);
            LOG.info("User authenticated: {}", username);
            return token;
        }

        LOG.warn("Authentication failed for user: {} from IP: {}", username, clientIp);
        return null;
    }

    /**
     * Validate token and return username if not expired.
     * Called infrequently thanks to per-channel auth caching (30s).
     * Expired tokens are removed and their SSE channels closed.
     */
    public String validateToken(String token) {
        if (token == null || token.isEmpty()) return null;

        TokenInfo tokenInfo = tokens.get(token);
        if (tokenInfo == null) return null;

        if (System.currentTimeMillis() > tokenInfo.expiry) {
            tokens.remove(token);
            cleanupTokenChannel(token);
            return null;
        }

        return tokenInfo.username;
    }

    /**
     * Register an SSE channel for a token. The channel will be closed
     * automatically when the token is invalidated (logout / password change).
     */
    void registerChannel(String token, Channel channel) {
        if (token != null && channel != null) {
            tokenChannels.put(token, channel);
        }
    }

    /** Unregister an SSE channel (called on channelInactive). */
    void unregisterChannel(Channel channel) {
        if (channel == null) return;
        tokenChannels.values().removeIf(ch -> ch == channel);
    }

    /**
     * Invalidate a specific token (logout). Also closes the associated SSE channel.
     */
    public void invalidateToken(String token) {
        if (token == null) return;
        tokens.remove(token);
        cleanupTokenChannel(token);
    }

    /**
     * Invalidate all tokens for a given username (e.g., on password change).
     * Also closes all associated SSE channels.
     */
    public void invalidateAllTokensForUser(String username) {
        if (username == null) return;
        tokens.entrySet().removeIf(e -> {
            if (username.equals(e.getValue().username)) {
                cleanupTokenChannel(e.getKey());
                return true;
            }
            return false;
        });
        LOG.info("All tokens invalidated for user: {}", username);
    }

    private static boolean secureEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        tokens.put(token, new TokenInfo(username, System.currentTimeMillis() + config.tokenExpiry));
        return token;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        // Expired tokens — also close any lingering SSE channels
        tokens.entrySet().removeIf(e -> {
            if (now > e.getValue().expiry) {
                cleanupTokenChannel(e.getKey());
                return true;
            }
            return false;
        });
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

    private void cleanupTokenChannel(String token) {
        Channel ch = tokenChannels.remove(token);
        if (ch != null) {
            ch.close();
        }
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

package com.zenith.webui.auth;

import com.zenith.webui.config.WebUIConfig;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI-Auth");
    private static final int MAX_ATTEMPTS_PER_MINUTE = 5;
    private static final long RATE_LIMIT_WINDOW_MS = 60_000;

    private final WebUIConfig.AuthConfig config;
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();
    // Rate limit: key = "ip:minute_timestamp"
    private final Map<String, Integer> rateLimitMap = new ConcurrentHashMap<>();
    private long lastRateLimitCleanup = System.currentTimeMillis();

    public AuthManager(WebUIConfig.AuthConfig config) {
        this.config = config;
        LOG.info("Auth manager initialized, token expiry: {}ms", config.tokenExpiry);
    }

    /**
     * Authenticate user with username and password, rate-limited by IP
     * @return token if successful, null otherwise
     */
    public String authenticate(String username, String password, String clientIp) {
        if (username == null || password == null || clientIp == null) return null;

        cleanupRateLimitIfNeeded();

        // IP-based rate limiting
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
     * Validate token
     * @return Username if valid, null otherwise
     */
    public String validateToken(String token) {
        if (token == null || token.isEmpty()) return null;

        TokenInfo tokenInfo = tokens.get(token);
        if (tokenInfo == null) return null;

        if (System.currentTimeMillis() > tokenInfo.expiry) {
            tokens.remove(token);
            return null;
        }

        return tokenInfo.username;
    }

    /**
     * Invalidate a specific token (logout)
     */
    public void invalidateToken(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    /**
     * Invalidate all tokens for a given username (e.g., on password change)
     */
    public void invalidateAllTokensForUser(String username) {
        if (username == null) return;
        tokens.entrySet().removeIf(e -> username.equals(e.getValue().username));
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

    private void cleanupRateLimitIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRateLimitCleanup < RATE_LIMIT_WINDOW_MS * 2) return;
        lastRateLimitCleanup = now;
        long cutoff = now - RATE_LIMIT_WINDOW_MS * 2;
        // Remove entries older than 2 windows by parsing minute timestamp from key
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

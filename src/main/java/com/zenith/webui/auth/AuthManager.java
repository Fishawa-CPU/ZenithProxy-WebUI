package com.zenith.webui.auth;

import com.zenith.webui.config.WebUIConfig;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AuthManager {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI-Auth");
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebUIConfig.AuthConfig config;
    private final String secretKey;
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();
    private final Map<String, Long> rateLimitMap = new ConcurrentHashMap<>();

    public AuthManager(WebUIConfig.AuthConfig config) {
        this.config = config;
        this.secretKey = config.secretKey.isEmpty() ? generateSecretKey() : config.secretKey;
        LOG.info("Auth manager initialized, token expiry: {}ms", config.tokenExpiry);
    }

    private String generateSecretKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Authenticate user with username and password
     * @return JWT token if successful, null otherwise
     */
    public String authenticate(String username, String password) {
        if (username == null || password == null) return null;

        // Rate limiting: max 5 attempts per minute
        String rateKey = username + ":" + (System.currentTimeMillis() / 60000);
        Long attempts = rateLimitMap.get(rateKey);
        if (attempts != null && attempts >= 5) {
            LOG.warn("Rate limit exceeded for user: {}", username);
            return null;
        }
        rateLimitMap.put(rateKey, attempts != null ? attempts + 1 : 1L);

        if (config.username.equals(username) && config.password.equals(password)) {
            String token = generateToken(username);
            LOG.info("User authenticated: {}", username);
            return token;
        }

        LOG.warn("Authentication failed for user: {}", username);
        return null;
    }

    /**
     * Validate JWT token
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
     * Invalidate token (logout)
     */
    public void invalidateToken(String token) {
        if (token != null) {
            tokens.remove(token);
        }
    }

    private String generateToken(String username) {
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + config.tokenExpiry;
        tokens.put(token, new TokenInfo(username, expiry));

        // Cleanup old tokens periodically
        if (tokens.size() > 1000) {
            tokens.entrySet().removeIf(e -> System.currentTimeMillis() > e.getValue().expiry);
        }

        return token;
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

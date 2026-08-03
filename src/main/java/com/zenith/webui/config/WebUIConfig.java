package com.zenith.webui.config;

public class WebUIConfig {
    public boolean enabled = true;
    public int port = 8080;

    // Authentication settings
    public AuthConfig auth = new AuthConfig();

    public WebUIConfig() {}

    public WebUIConfig(boolean enabled, int port) {
        this.enabled = enabled;
        this.port = port;
    }

    public static class AuthConfig {
        public boolean enabled = true;
        public String username = "admin";
        public String password = "admin";
        public long tokenExpiry = 24 * 60 * 60 * 1000; // 24 hours
        public String secretKey = ""; // Auto-generated if empty

        public AuthConfig() {}
    }
}

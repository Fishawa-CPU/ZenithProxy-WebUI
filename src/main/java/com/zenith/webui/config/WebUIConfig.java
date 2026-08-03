package com.zenith.webui.config;

public class WebUIConfig {
    public int port = 8080;

    // Authentication settings
    public AuthConfig auth = new AuthConfig();

    public WebUIConfig() {}

    public static class AuthConfig {
        public boolean enabled = true;
        public String username = "admin";
        public String password = "admin";
        public long tokenExpiry = 24 * 60 * 60 * 1000; // 24 hours

        public AuthConfig() {}
    }
}

package com.zenith.webui.config;

public class WebUIConfig {
    public boolean enabled = true;
    public int port = 8080;

    public WebUIConfig() {}

    public WebUIConfig(boolean enabled, int port) {
        this.enabled = enabled;
        this.port = port;
    }
}

package com.zenith.webui.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private final Path configDir;
    private final Path configFile;
    private WebUIConfig config;

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
        this.configFile = configDir.resolve("config.json");
    }

    public void loadOrCreate() {
        try {
            Files.createDirectories(configDir);

            if (Files.exists(configFile)) {
                String content = Files.readString(configFile);
                config = GSON.fromJson(content, WebUIConfig.class);
                if (config == null) {
                    config = new WebUIConfig();
                }
                LOG.info("Loaded WebUI config: port={}", config.port);
            } else {
                config = new WebUIConfig();
                // Generate a random password on first run to avoid default credentials
                config.auth.password = java.util.UUID.randomUUID().toString().substring(0, 16);
                save();
                LOG.info("Created WebUI config with defaults (random password generated)");
                LOG.info("Default username: admin, password: {}", config.auth.password);
            }
        } catch (Exception e) {
            LOG.error("Failed to load config, using defaults", e);
            config = new WebUIConfig();
        }
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            Files.writeString(configFile, GSON.toJson(config));
        } catch (IOException e) {
            LOG.error("Failed to save config", e);
        }
    }

    public WebUIConfig getConfig() {
        return config;
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Path getConfigFile() {
        return configFile;
    }
}

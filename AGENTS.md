# AGENTS.md

## What This Is

A ZenithProxy plugin providing a browser-based management dashboard. Java backend (NIO via Netty + SSE) with a static-file frontend.

## Build

```bash
./gradlew build          # compiles + shadow JAR
./gradlew shadowJar      # fat JAR only (output: build/libs/ZenithProxy-WebUI-1.0.0.jar)
```

Requires **Java 21** (set in `build.gradle.kts` toolchain). No test suite exists.

## Project Structure

```
src/main/java/com/zenith/webui/
  WebUIPlugin.java          # Plugin entrypoint, HTTP handlers, SSE, static file server
  TitlePacketHandler.java   # Title packet handler
  config/
    WebUIConfig.java        # Configuration data class
    ConfigManager.java      # Config file management
src/main/resources/
  web/
    index.html, app.js, styles.css   # Frontend (vanilla JS, no build step)
  zenithproxy.plugin.json            # Plugin metadata (entrypoint class, id, version)
```

## Runtime Directory Structure

```
plugins/webui/
  config.json               # Plugin-specific configuration (port, enabled)
  web/                      # Optional: custom frontend files (overrides classpath)
```

## Key Facts for Agents

- **No tests, no CI, no linter.** There is no verification step beyond `./gradlew build`.
- **External dependency**: `ZenithProxy.jar` in `libs/` directory (compileOnly). If the build fails on dependency resolution, the JAR may be missing or outdated.
- **NIO Server**: Uses Netty `NioServerSocketChannel` for non-blocking I/O with zero-copy file transfer.
- **Static file serving**: Serves from classpath `/web/` first, then falls back to `plugins/webui/web/` at runtime.
- **SSE (Server-Sent Events)**: Real-time log streaming via `/api/logs/stream`. Uses Netty channels.
- **CORS**: All API handlers set `Access-Control-Allow-Origin: *` — intentional for local dev.
- **Independent config**: Plugin config stored in `plugins/webui/config.json`, separate from main ZenithProxy config.
- **Log translation**: Common English log messages are auto-translated to Chinese before pushing to SSE/HTTP polling.
- **Whitelist management**: Full CRUD API at `/api/whitelist` with enable/disable, add/remove, kick message, and auto-add bot account settings.

## Configuration

The plugin uses its own configuration file at `plugins/webui/config.json`:

```json
{
  "enabled": true,
  "port": 8080
}
```

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Proxy status (connected, queue, version, username, uptime) |
| `/api/config` | GET/POST | Read/update main ZenithProxy config.json |
| `/api/commands` | GET/POST | List commands / execute command |
| `/api/players` | GET | List connected players |
| `/api/queue` | GET | Queue status |
| `/api/logs` | GET | List log files |
| `/api/logs/live` | GET | Poll new logs (HTTP) |
| `/api/logs/stream` | GET | SSE real-time log stream |
| `/api/system` | GET | CPU, memory, network stats |
| `/api/latency` | GET | Bot-server and player-bot latency |
| `/api/whitelist` | GET/POST | Whitelist management |

## Frontend Features

- **Dashboard**: Proxy status, player connection, uptime, version, CPU/memory/network/latency charts
- **Modules**: Toggle modules with Chinese name subtitles
- **Config**: Edit auth/client/server settings, save triggers restart prompt
- **Terminal**: Command execution with autocomplete, history, log display with log level colors
- **Players**: Connected player list with kick button
- **Whitelist**: Enable/disable, auto-add bot account, kick message, add/remove players

## Conventions

- Frontend is vanilla HTML/CSS/JS — no framework, no npm, no bundler.
- Java backend uses Netty NIO (non-blocking I/O with event loop).
- JSON serialization via Gson (not Jackson).
- Plugin API uses ZenithProxy's `ZenithProxyPlugin` interface with `onLoad(PluginAPI)`.
- UI text is in Chinese (zh-CN) with English subtitles via `.en` span elements.
- CSS uses Catppuccin Macchiato color palette with `--ctp-*` variables.

# AGENTS.md

## What This Is

A ZenithProxy plugin providing a browser-based management dashboard. Java backend (HTTP + SSE) with a static-file frontend.

## Build

```bash
./gradlew build          # compiles + shadow JAR
./gradlew shadowJar      # fat JAR only (output: build/libs/ZenithProxy-WebUI-1.0.0.jar)
```

Requires **Java 21** (set in `build.gradle.kts` toolchain). No test suite exists.

## Project Structure

```
src/main/java/com/zenith/webui/
  WebUIPlugin.java      # Plugin entrypoint, HTTP handlers, SSE, static file server
src/main/resources/
  web/
    index.html, app.js, styles.css   # Frontend (vanilla JS, no build step)
  zenithproxy.plugin.json            # Plugin metadata (entrypoint class, id, version)
```

## Key Facts for Agents

- **No tests, no CI, no linter.** There is no verification step beyond `./gradlew build`.
- **External dependency**: `ZenithProxy.jar` in `libs/` directory (compileOnly). If the build fails on dependency resolution, the JAR may be missing or outdated.
- **Static file serving path**: The `StaticFileHandler` serves files from classpath `/web/` first, then falls back to `plugins/webui/web/` at runtime.
- **SSE (Server-Sent Events)**: Real-time log streaming via `/api/logs/stream`. No WebSocket dependency.
- **CORS**: All API handlers set `Access-Control-Allow-Origin: *` — intentional for local dev.
- **Lombok** is used (`io.freefair.lombok` plugin) but no `@lombok` annotations appear in current source — may be inherited from the base ZenithProxy dependency.
- **Shadow JAR** plugins: `com.gradleup.shadow` v9.5.1. The JAR is named `ZenithProxy-WebUI-1.0.0.jar`.
- **Log translation**: Common English log messages are auto-translated to Chinese before pushing to SSE/HTTP polling.
- **Module Chinese names**: Frontend displays Chinese module name subtitles (80+ modules mapped).
- **Module persistence**: Module enable/disable state is saved to `config.json` under `modules` key and restored on startup.
- **Whitelist management**: Full CRUD API at `/api/whitelist` with enable/disable, add/remove, kick message, and auto-add bot account settings.

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Proxy status (connected, queue, version, username, uptime) |
| `/api/config` | GET/POST | Read/update config.json |
| `/api/modules` | GET/POST | List/toggle modules (persisted to config.json) |
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
- **Modules**: Toggle modules with Chinese name subtitles, persisted to config.json
- **Config**: Edit auth/client/server settings, save triggers restart prompt
- **Terminal**: Command execution with autocomplete, history, log display with log level colors
- **Players**: Connected player list with kick button
- **Whitelist**: Enable/disable, auto-add bot account, kick message, add/remove players

## Conventions

- Frontend is vanilla HTML/CSS/JS — no framework, no npm, no bundler.
- Java code uses `com.sun.net.httpserver.HttpServer` (no Spring/Jetty).
- JSON serialization via Gson (not Jackson).
- Plugin API uses ZenithProxy's `ZenithProxyPlugin` interface with `onLoad(PluginAPI)`.
- UI text is in Chinese (zh-CN) with English subtitles via `.en` span elements.
- CSS uses Catppuccin Macchiato color palette with `--ctp-*` variables.

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
  WebUIPlugin.java          # Plugin entrypoint, HTTP handlers, SSE, static file server, log appender
  TitlePacketHandler.java   # Title packet handler
  auth/
    AuthHandler.java        # Authentication filter (cookie + bearer token)
    AuthManager.java        # Token generation, validation, rate limiting
    AuthController.java     # Login/logout/status HTTP handlers
  config/
    WebUIConfig.java        # Configuration data class (auth, port, enabled)
    ConfigManager.java      # Config file management (auto-generates random password on first run)
  queue/
    QueueParser.java        # 2b2t queue position packet parser
src/main/resources/
  web/
    index.html, app.js, styles.css   # Frontend (vanilla JS, no build step)
    login.html                       # Authentication login page
  zenithproxy.plugin.json            # Plugin metadata (entrypoint class, id, version)
```

## Runtime Directory Structure

```
plugins/webui/
  config.json               # Plugin-specific configuration (port, auth, enabled)
  web/                      # Optional: custom frontend files (overrides classpath)
```

## Key Facts for Agents

- **No tests, no CI, no linter.** There is no verification step beyond `./gradlew build`.
- **External dependency**: `ZenithProxy.jar` in `libs/` directory (compileOnly). If the build fails on dependency resolution, the JAR may be missing or outdated.
- **NIO Server**: Uses Netty `NioServerSocketChannel` for non-blocking I/O with zero-copy file transfer.
- **Static file serving**: Serves from classpath `/web/` first, then falls back to `plugins/webui/web/` at runtime. File cache is bounded at 128 entries.
- **SSE (Server-Sent Events)**: Real-time log streaming via `/api/logs/stream` and dashboard metrics via `/api/dashboard/stream`. Uses Netty channels with CopyOnWriteArrayList for thread-safe client management.
- **CORS**: All response handlers (sendJson, sendBytes, sendNotFound, sendCorsResponse, AuthController) set `Access-Control-Allow-Origin: *`. CORS preflight also allows `Authorization` header.
- **Independent config**: Plugin config stored in `plugins/webui/config.json`, separate from main ZenithProxy config. On first run, a random password is generated (logged for the admin).
- **Log translation**: Common English log messages are auto-translated to Chinese before pushing to SSE. Translation keys are sorted by length descending to prevent partial-match issues.
- **Whitelist management**: Full CRUD API at `/api/whitelist` with enable/disable, add/remove, kick message, and auto-add bot account settings. Directly modifies the main ZenithProxy CONFIG object (persistence is handled by ZenithProxy itself).
- **Thread safety**: `WebUIPlugin.instance` is `volatile`. Logback appender uses a local snapshot to avoid TOCTOU after `stop()`. `AuthManager.lastRateLimitCleanup` is `volatile`. SSE client lists use `CopyOnWriteArrayList`. Log buffer uses `ConcurrentLinkedDeque`.
- **start() idempotency**: `start()` checks server running state and cleans stale Netty resources before initializing. Returns `boolean` for callers to detect failure. On failure, all partially-initialized resources are cleaned up.
- **Input validation**: POST handlers validate JSON body is non-empty and required fields exist before processing. Empty commands are rejected with an error response.

## Configuration

The plugin uses its own configuration file at `plugins/webui/config.json`:

```json
{
  "enabled": true,
  "port": 8080,
  "auth": {
    "enabled": true,
    "username": "admin",
    "password": "<randomly generated on first run>"
  }
}
```

- `enabled`: Whether the WebUI server starts automatically on plugin load.
- `port`: HTTP server listen port.
- `auth.enabled`: Enable/disable authentication (login page + token validation).
- `auth.username` / `auth.password`: Login credentials. On first run, a random 16-character password is generated and logged.

To disable authentication, set `auth.enabled` to `false` in config.json.

## API Endpoints

| Endpoint | Method | Auth | Description |
|----------|--------|------|-------------|
| `/api/auth/login` | POST | No | Login with `{username, password}` → sets HttpOnly cookie |
| `/api/auth/logout` | POST | No | Clears auth cookie |
| `/api/auth/status` | GET | No | Returns `{authenticated, username}` (always accessible, reports if logged in) |
| `/api/dashboard` | GET | Yes | Aggregated proxy status: connection, queue, version, uptime, players, CPU, memory, network, latency |
| `/api/dashboard/stream` | GET | Yes | SSE stream: pushes aggregated dashboard data every second |
| `/api/commands` | GET | Yes | Lists available command names |
| `/api/commands` | POST | Yes | Executes a command `{command: "..."}`, returns parsed output |
| `/api/logs/stream` | GET | Yes | SSE stream: pushes log history (last 500) then real-time log entries |
| `/api/whitelist` | GET | Yes | Returns whitelist status, player list, kick message, auto-add config |
| `/api/whitelist` | POST | Yes | Whitelist CRUD: `{action: "toggle/add/remove/updateConfig", ...}` |

### Notes

- **Auth**: Cookie-based via `webui_token` (HttpOnly, SameSite=Strict, Path=/). Also supports `Authorization: Bearer <token>` header.
- **SSE authentication**: SSE connections (EventSource) automatically send cookies for same-origin requests. Client tokens are stored per-channel and validated every dashboard update cycle. Expired tokens cause channel closure.
- **Aggregated dashboard**: The single `/api/dashboard` endpoint replaces what was previously planned as separate endpoints (`/api/status`, `/api/players`, `/api/queue`, `/api/system`, `/api/latency`).
- **Static files**: All non-API paths serve static files without authentication (required for login page, JS, CSS). This includes `/`, `/index.html`, `/login.html`, `/app.js`, `/styles.css`, and any custom files in `plugins/webui/web/`.
- **Config save**: The main ZenithProxy config is managed by ZenithProxy itself. The WebUI plugin reads/writes `CONFIG` in memory but does not trigger saves — the proxy handles persistence.

## Command API Response Format

Command execution via `POST /api/commands` returns:
```json
{
  "success": true,
  "output": "formatted command output"
}
```

If the command produces structured output (embed, multi-line), it is converted to plain text on the server side.

## Frontend Features

- **Dashboard**: Proxy status, player connection, uptime, version, CPU/memory/network/latency charts (2-min window, 60 data points refreshed via SSE)
- **Modules**: Toggle modules with Chinese name subtitles (data from `/api/dashboard`)
- **Config**: Edit auth/client/server settings, save triggers restart prompt
- **Terminal**: Command execution with autocomplete (fetched from `/api/commands`), history, log display with log level colors (via SSE)
- **Players**: Connected player list with kick button (via command execution)
- **Whitelist**: Enable/disable, auto-add bot account, kick message, add/remove players

## Login Flow

1. Browser requests any path → if unauthenticated, receives `login.html`
2. User submits credentials → `POST /api/auth/login` → on success receives HttpOnly `webui_token` cookie → redirect to `/`
3. All subsequent API calls include the cookie automatically
4. Token expires after 24 hours of inactivity (rolling expiration on each request)
5. Rate limiting: 5 failed attempts per 15 minutes per IP

## Conventions

- Frontend is vanilla HTML/CSS/JS — no framework, no npm, no bundler.
- Java backend uses Netty NIO (non-blocking I/O with event loop).
- JSON serialization via Gson (not Jackson).
- Plugin API uses ZenithProxy's `ZenithProxyPlugin` interface with `onLoad(PluginAPI)`.
- UI text is in Chinese (zh-CN) with English subtitles via `.en` span elements.
- CSS uses Catppuccin Macchiato color palette with `--ctp-*` variables.
- `start()` returns `boolean` — callers check the return value and report failures accordingly.
- All POST endpoints validate JSON body is non-empty and required fields exist before processing.

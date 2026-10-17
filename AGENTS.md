# AGENTS.md

## What This Is

A ZenithProxy plugin providing a browser-based management dashboard. Java backend (NIO via Netty + SSE) with a static-file frontend.

## Build

```bash
./gradlew build          # compiles + shadow JAR
./gradlew shadowJar      # fat JAR only (output: build/libs/ZenithProxy-WebUI-1.4.0.jar)
```

Requires **Java 21** (set in `build.gradle.kts` toolchain) and network access to
`maven.2b2t.vc` for the ZenithProxy dependency. No test suite exists.

CI lives in `.github/workflows/build.yml`: every push/PR builds the fat JAR and uploads it as
a workflow artifact; pushing a `v*` tag also publishes a GitHub Release with the JAR attached.

## Project Structure

```
src/main/java/com/zenith/webui/
  WebUIPlugin.java          # Plugin entrypoint, lifecycle, SSE broadcast, log buffer
  TitlePacketHandler.java   # Title packet handler
  api/                      # /api handlers: commands, dashboard, log stream, whitelist
    DashboardHandler.java   # Aggregated dashboard payload (POJO → Gson)
    DashboardData.java      # Dashboard DTO — field names are the frontend contract
  auth/                     # Token auth: filter, manager, login/logout controller
  authbypass/               # Offline-auth bypass packet handlers (hybrid-auth servers)
  command/                  # /webui enable|disable|status
  config/                   # WebUIConfig data class + config.json manager
  http/HttpHelper.java      # Response helpers + shared Gson (+ SSE frame builder)
  queue/QueueParser.java    # 3c3u.org / 2b2t.org queue-position packet parser
  server/                   # Netty bootstrap + request routing
  staticfile/               # Static file serving with bounded LRU cache
  terminal/                 # Log translation, Logback appender, LogEntry DTO
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

- **No test suite, no linter.** CI (`.github/workflows/build.yml`) is the only verification step; it compiles, checks the version pair (build.gradle.kts ↔ plugin.json) and uploads the JAR.
- **External dependency**: ZenithProxy is resolved from Maven — `com.zenith:ZenithProxy:<proxy>+<mc>`
  (e.g. `3.7.3+1.21.4`) as `compileOnly`. Repositories (`maven.2b2t.vc/releases`, `/remote`) are declared
  in `settings.gradle.kts`; the version lives in `build.gradle.kts` as `zenithProxyVersion`.
  There is no local JAR in `libs/` anymore — bump the version if the plugin API changes.
- **NIO Server**: Uses Netty `NioServerSocketChannel` for non-blocking I/O with zero-copy file transfer.
- **JSON policy**: every response body and SSE frame is produced by Gson (`HttpHelper.GSON` / `GSON_NULLS` / `sseFrame`). Never assemble JSON with `StringBuilder` — the old hand-rolled escaping silently dropped control characters and broke `JSON.parse` in the browser.
- **Dashboard nulls**: `DashboardData` is serialized with `GSON_NULLS` because `player.health`/`player.food` must appear as explicit `null` when the bot is offline; the default Gson would omit the fields.
- **Static file serving**: Serves from classpath `/web/` first, then falls back to `plugins/webui/web/` at runtime. File cache is bounded at 128 entries. Filesystem paths are normalized and must stay inside the web directory (blocks `/C:/...` traversal on Windows).
- **SSE (Server-Sent Events)**: `/api/logs/stream` and `/api/dashboard/stream`. Uses Netty channels with `CopyOnWriteArrayList` for thread-safe client management.
- **CORS**: All response helpers set `Access-Control-Allow-Origin: *`. Preflight also allows `Authorization`.
- **Independent config**: `plugins/webui/config.json`, separate from the main ZenithProxy config. On first run a random password is generated and logged. `enabled: false` skips auto-start; `/webui enable` still works.
- **Log translation**: Common English log messages are auto-translated to Chinese before pushing to SSE. Keys are sorted by length descending to prevent partial matches.
- **Whitelist management**: Full CRUD API at `/api/whitelist`. Directly modifies the main ZenithProxy CONFIG object (persistence is handled by ZenithProxy itself).
- **Thread safety**: `WebUIPlugin.instance` is `volatile`. Logback appender uses a local snapshot to avoid TOCTOU after `stop()`. SSE client lists use `CopyOnWriteArrayList`. Log buffer uses `ConcurrentLinkedDeque<LogEntry>` plus an approximate size counter.
- **start() idempotency**: `start()` returns early when the server is already running, so repeated `/webui enable` cannot register duplicate packet codecs, log appenders or event subscribers. Returns `boolean` for callers to detect failure.
- **stop() semantics**: `stop()` disables the WebUI but does not shut down `AuthManager` (tokens must survive a disable/enable cycle). It is safe to call repeatedly.
- **Auth cache**: `AuthHandler` caches successful validation per TCP channel for 30s, guarded by `AuthManager.epoch()` — logout/password change bumps the epoch and invalidates every channel's cache immediately.
- **Input validation**: POST handlers validate JSON body is non-empty and required fields exist before processing. Unsupported methods return `405`, unknown `/api/*` paths return `404` (never the SPA index).

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

- **Auth**: Cookie-based via `webui_token` (HttpOnly, SameSite=Lax, Path=/). Also supports `Authorization: Bearer <token>`. Blank/missing password in config.json triggers regeneration with a random password.
- **SSE authentication**: EventSource sends cookies for same-origin requests. Channels are tied to their token and closed when the token is revoked.
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
5. Rate limiting: 5 failed attempts per minute per IP (successful logins clear the counter)

## Conventions

- Frontend is vanilla HTML/CSS/JS — no framework, no npm, no bundler.
- Java backend uses Netty NIO (non-blocking I/O with event loop).
- JSON serialization via Gson (not Jackson), always through `HttpHelper` — no hand-built JSON strings.
- Plugin API uses ZenithProxy's `ZenithProxyPlugin` interface with `onLoad(PluginAPI)`.
- UI text is in Chinese (zh-CN) with English subtitles via `.en` span elements.
- CSS uses Catppuccin Macchiato color palette with `--ctp-*` variables.
- `start()` returns `boolean` — callers check the return value and report failures accordingly.
- All POST endpoints validate JSON body is non-empty and required fields exist before processing.

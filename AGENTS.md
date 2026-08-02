# AGENTS.md

## What This Is

A ZenithProxy plugin providing a browser-based management dashboard. Java backend (HTTP + WebSocket) with a static-file frontend.

## Build

```bash
./gradlew build          # compiles + shadow JAR
./gradlew shadowJar      # fat JAR only (output: build/libs/ZenithProxy-WebUI-1.0.0.jar)
```

Requires **Java 25** (set in `build.gradle.kts` toolchain). No test suite exists.

## Project Structure

```
src/main/java/com/zenith/webui/
  WebUIPlugin.java      # Plugin entrypoint, HTTP handlers, static file server
  WebSocketServer.java  # Raw WebSocket server (no library dependency)
webui/
  index.html, app.js, styles.css   # Frontend (vanilla JS, no build step)
src/main/resources/
  zenithproxy.plugin.json  # Plugin metadata (entrypoint class, id, version)
```

## Key Facts for Agents

- **No tests, no CI, no linter.** There is no verification step beyond `./gradlew build`.
- **External dependency**: `com.zenith:ZenithProxy:26.1.2-SNAPSHOT` from `maven.2b2t.vc` custom repos. If the build fails on dependency resolution, the upstream artifact may have changed — check `build.gradle.kts` version.
- **Static file serving path mismatch**: The `StaticFileHandler` in `WebUIPlugin.java:163` serves files from `plugins/webui/web/` at runtime, NOT from the repo's `webui/` directory. When deploying, the `webui/` contents must be copied to `plugins/webui/web/` alongside the JAR.
- **WebSocket**: The frontend connects to `ws://hostname:8081` by default (`app.js:414`). The WS port is configurable via `config.json` `webUI.websocketPort`.
- **CORS**: All API handlers set `Access-Control-Allow-Origin: *` — intentional for local dev.
- **Lombok** is used (`io.freefair.lombok` plugin) but no `@lombok` annotations appear in current source — may be inherited from the base ZenithProxy dependency.
- **Shadow JAR** plugins: `com.gradleup.shadow` v9.5.1. The JAR is named `ZenithProxy-WebUI-1.0.0.jar` (no version suffix due to `archiveClassifier.set("")`).

## Conventions

- Frontend is vanilla HTML/CSS/JS — no framework, no npm, no bundler.
- Java code uses `com.sun.net.httpserver.HttpServer` (no Spring/Jetty).
- JSON serialization via Gson (not Jackson).
- Plugin API uses ZenithProxy's `ZenithProxyPlugin` interface with `onLoad(PluginAPI)`.
- UI text is in Chinese (zh-CN). Preserve language if editing UI strings.

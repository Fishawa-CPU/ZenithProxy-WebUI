package com.zenith.webui.staticfile;

import com.zenith.webui.auth.AuthHandler;
import com.zenith.webui.http.HttpHelper;
import io.netty.channel.ChannelHandlerContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves static files from classpath and plugin directory with caching.
 * Both classpath resources and filesystem files share the same cache
 * to avoid repeated reads.
 */
public class StaticFileHandler {
    private static final int FILE_CACHE_MAX_SIZE = 128;
    private static final ConcurrentHashMap<Path, byte[]> fileCache = new ConcurrentHashMap<>();
    /** Cache classpath resources keyed by path (e.g. "index.html"). */
    private static final ConcurrentHashMap<String, byte[]> resourceCache = new ConcurrentHashMap<>();
    /** Sentinel for files that don't exist, to avoid repeated I/O. */
    private static final byte[] NOT_FOUND = new byte[0];

    private final Path webDir;

    public StaticFileHandler(Path configDir) {
        this.webDir = configDir.resolve("web");
    }

    public void handleStaticFile(ChannelHandlerContext ctx, String path) {
        if ("/".equals(path)) path = "/index.html";

        if (!AuthHandler.isSafePath(path)) {
            HttpHelper.sendNotFound(ctx);
            return;
        }

        byte[] content = loadResourceCached(path);
        if (content == null) {
            content = loadFile(webDir.resolve(path.substring(1)));
        }

        if (content != null) {
            HttpHelper.sendBytes(ctx, content, HttpHelper.getContentType(path));
        } else {
            // SPA fallback
            content = loadResourceCached("/index.html");
            if (content == null) {
                content = loadFile(webDir.resolve("index.html"));
            }
            if (content != null) {
                HttpHelper.sendBytes(ctx, content, "text/html");
            } else {
                HttpHelper.sendNotFound(ctx);
            }
        }
    }

    /** Cached wrapper around classpath resource loading. */
    private byte[] loadResourceCached(String path) {
        String cacheKey = path.startsWith("/") ? path.substring(1) : path;
        byte[] cached = resourceCache.get(cacheKey);
        if (cached != null) {
            return cached == NOT_FOUND ? null : cached;
        }
        byte[] content = loadResource(cacheKey);
        resourceCache.put(cacheKey, content != null ? content : NOT_FOUND);
        return content;
    }

    private byte[] loadResource(String path) {
        // path already normalized (no leading /) by loadResourceCached
        try (InputStream is = getClass().getResourceAsStream("/web/" + path)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    byte[] loadFile(Path path) {
        byte[] cached = fileCache.get(path);
        if (cached != null) {
            return cached == NOT_FOUND ? null : cached; // sentinel: file doesn't exist
        }
        try {
            byte[] content = null;
            if (Files.exists(path) && !Files.isDirectory(path)) {
                if (fileCache.size() >= FILE_CACHE_MAX_SIZE) {
                    var it = fileCache.keySet().iterator();
                    if (it.hasNext()) it.remove();
                }
                content = Files.readAllBytes(path);
            }
            // Use sentinel to cache the "not found" result, avoiding repeated I/O
            fileCache.put(path, content != null ? content : NOT_FOUND);
            return content;
        } catch (IOException e) {
            fileCache.put(path, NOT_FOUND);
            return null;
        }
    }
}

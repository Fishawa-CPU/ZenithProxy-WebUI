package com.zenith.webui.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.zenith.webui.config.ConfigPatcher;
import com.zenith.webui.http.HttpHelper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.zenith.Globals.CONFIG;

/**
 * 配置中心：读取当前运行的 ZenithProxy 配置、按点分路径写回标量字段。
 *
 * <p>GET 返回整棵配置树的快照（敏感字段打码）。前端结合
 * {@code /config-meta.json} 里的中文标签和 {@code /config-default.json} 里的默认值
 * 渲染表单，再把改动以 {@code {"patch": {"点分路径": 值}}} 的形式提交回来。
 *
 * <p>设计取舍：只写标量字段。数组/对象字段（白名单、聊天架构之类）改动风险高，
 * 且往往有专用接口（如 {@code /api/whitelist}），这里一律跳过并在响应里报出来。
 */
public class ConfigHandler {
    private static final ComponentLogger LOG = ComponentLogger.logger("WebUI-Config");

    /** 敏感字段的占位值。前端原样回传时表示"没有改动"，不写回。 */
    static final String MASK = "********";

    /** 字段名（去掉下划线、转小写后）命中即打码。 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "passwd", "secret", "token", "apikey", "privatekey", "webhookurl", "authtoken"
    );

    /** 请求体上限的保护：补丁条数上限在 ConfigPatcher 里，这里再限制一次单条路径长度。 */
    private static final int MAX_PATH_LENGTH = 200;

    public void handleConfig(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (HttpMethod.GET.equals(req.method())) {
            handleGet(ctx);
        } else if (HttpMethod.POST.equals(req.method())) {
            handlePost(ctx, req);
        } else {
            HttpHelper.sendError(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
        }
    }

    private void handleGet(ChannelHandlerContext ctx) {
        try {
            JsonElement tree = HttpHelper.GSON.toJsonTree(CONFIG);
            if (!tree.isJsonObject()) {
                HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Config is not an object"));
                return;
            }
            List<String> masked = new ArrayList<>();
            maskSensitive(tree, "", masked);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("config", tree);
            body.put("masked", masked);
            body.put("mask", MASK);
            HttpHelper.sendJson(ctx, body);
        } catch (RuntimeException e) {
            LOG.warn("Failed to serialize config: {}", e.toString());
            HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Failed to read config: " + e.getClass().getSimpleName()));
        }
    }

    private void handlePost(ChannelHandlerContext ctx, FullHttpRequest req) {
        String body = req.content().toString(CharsetUtil.UTF_8);
        if (body == null || body.isBlank()) {
            HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Empty request body"));
            return;
        }

        JsonObject payload;
        try {
            payload = HttpHelper.GSON.fromJson(body, JsonObject.class);
        } catch (RuntimeException e) {
            HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Malformed JSON"));
            return;
        }
        if (payload == null || !payload.has("patch") || !payload.get("patch").isJsonObject()) {
            HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Missing patch object"));
            return;
        }

        JsonObject patchJson = payload.getAsJsonObject("patch");
        if (patchJson.size() > ConfigPatcher.MAX_PATCH_ENTRIES) {
            HttpHelper.sendJson(ctx, Map.of("success", false,
                "error", "Too many fields in one request (max " + ConfigPatcher.MAX_PATCH_ENTRIES + ")"));
            return;
        }

        Map<String, Object> patch = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        List<String> unmasked = new ArrayList<>();

        for (Map.Entry<String, JsonElement> entry : patchJson.entrySet()) {
            String path = entry.getKey();
            if (path == null || path.isBlank() || path.length() > MAX_PATH_LENGTH) {
                unsupported.add(String.valueOf(path));
                continue;
            }
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                // 不支持把字段置 null —— JSON 里 null 的语义在 Java 字段上不明确
                unsupported.add(path);
                continue;
            }
            if (!value.isJsonPrimitive()) {
                // 数组/对象：交给 config.json 手工编辑或专用接口
                unsupported.add(path);
                continue;
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString() && MASK.equals(primitive.getAsString())) {
                // 前端把打码占位原样传回来了，说明用户没改这个敏感字段
                unmasked.add(path);
                continue;
            }
            patch.put(path, toJava(primitive));
        }

        if (patch.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("applied", 0);
            resp.put("skipped", unsupported);
            resp.put("failed", List.of());
            resp.put("note", "没有可写入的字段");
            HttpHelper.sendJson(ctx, resp);
            return;
        }

        ConfigPatcher.Result result;
        try {
            result = ConfigPatcher.apply(CONFIG, patch);
        } catch (RuntimeException e) {
            LOG.warn("Config patch failed: {}", e.toString());
            HttpHelper.sendJson(ctx, Map.of("success", false, "error", "Config update failed: " + e.getClass().getSimpleName()));
            return;
        }

        List<String> skipped = new ArrayList<>(unsupported);
        skipped.addAll(result.skipped());
        List<String> failed = new ArrayList<>(result.failed());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", result.ok());
        resp.put("applied", result.applied());
        resp.put("skipped", skipped);
        resp.put("failed", failed);
        resp.put("unchanged", unmasked);
        resp.put("note", result.applied() > 0
            ? "已写入内存，部分设置需重启 ZenithProxy 生效"
            : "没有字段被写入");

        LOG.info("Config patch: applied={}, skipped={}, failed={}",
            result.applied(), skipped.size(), failed.size());
        HttpHelper.sendJson(ctx, resp);
    }

    private static Object toJava(JsonPrimitive p) {
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsNumber();
        return p.getAsString();
    }

    /** 递归给敏感字段打码，同时收集被打码的完整路径。 */
    private static void maskSensitive(JsonElement element, String prefix, List<String> masked) {
        if (element instanceof JsonObject obj) {
            List<String> hits = new ArrayList<>(2);
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey();
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                JsonElement value = e.getValue();
                if (value instanceof JsonPrimitive p && p.isString()
                    && isSensitiveKey(key) && !p.getAsString().isEmpty()) {
                    hits.add(key);
                    masked.add(path);
                } else if (value instanceof JsonObject || value instanceof JsonArray) {
                    maskSensitive(value, path, masked);
                }
            }
            for (String key : hits) {
                obj.add(key, new JsonPrimitive(MASK));
            }
        } else if (element instanceof JsonArray arr) {
            for (JsonElement child : arr) {
                maskSensitive(child, prefix, masked);
            }
        }
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return SENSITIVE_KEYS.contains(normalized);
    }
}

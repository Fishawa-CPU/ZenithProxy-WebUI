package com.zenith.webui.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按点分路径把 JSON 补丁写回 ZenithProxy 的 CONFIG 对象。
 *
 * <p>只处理标量字段（boolean / 整数 / 浮点 / String / 枚举）。数组、集合、
 * 嵌套对象一律跳过 —— 这些字段要么由专用 API 维护（例如 {@code /api/whitelist}），
 * 要么改动风险过高，交给 config.json 手工编辑更安全。
 *
 * <p>逐字段赋值而不是整体替换 CONFIG 引用：ZenithProxy 内部大量持有
 * {@code CONFIG.client} / {@code CONFIG.server} 这类子对象引用，整体替换会让
 * 那些缓存失效。
 */
public final class ConfigPatcher {
    /** 单次请求最多接受多少条补丁，避免恶意请求长时间占用反射路径。 */
    public static final int MAX_PATCH_ENTRIES = 512;

    /** 写入结果：成功条数 + 跳过的路径（非标量）+ 失败的路径（带原因）。 */
    public record Result(int applied, List<String> skipped, List<String> failed) {
        public boolean ok() {
            return failed.isEmpty();
        }
    }

    private enum Outcome { APPLIED, SKIPPED }

    /** 哨兵：表示"这个值不能写进目标字段"，与合法的 null 区分开。 */
    private static final Object SKIP = new Object();

    private ConfigPatcher() {}

    public static Result apply(Object root, Map<String, Object> patch) {
        int applied = 0;
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String path = entry.getKey();
            try {
                if (writePath(root, path, entry.getValue()) == Outcome.APPLIED) {
                    applied++;
                } else {
                    skipped.add(path);
                }
            } catch (ReflectiveOperationException | IllegalArgumentException | ClassCastException e) {
                failed.add(path + ": " + describe(e));
            } catch (RuntimeException e) {
                failed.add(path + ": " + describe(e));
            }
        }
        return new Result(applied, skipped, failed);
    }

    private static Outcome writePath(Object root, String path, Object value) throws ReflectiveOperationException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("empty path");
        }
        String[] keys = path.split("\\.");
        if (keys.length == 0) {
            throw new IllegalArgumentException("empty path");
        }

        Object owner = root;
        for (int i = 0; i < keys.length - 1; i++) {
            Field segment = findField(owner.getClass(), keys[i]);
            if (segment == null) {
                throw new NoSuchFieldException(keys[i]);
            }
            owner = segment.get(owner);
            if (owner == null) {
                throw new IllegalStateException("parent is null at " + keys[i]);
            }
        }

        String leafName = keys[keys.length - 1];
        Field leaf = findField(owner.getClass(), leafName);
        if (leaf == null) {
            throw new NoSuchFieldException(leafName);
        }
        if (!isWritableScalar(leaf.getType())) {
            return Outcome.SKIPPED;
        }

        Object coerced = coerce(leaf.getType(), value);
        if (coerced == SKIP) {
            return Outcome.SKIPPED;
        }
        leaf.set(owner, coerced);
        return Outcome.APPLIED;
    }

    /**
     * 沿类层次向上找字段，允许非 public（ZenithProxy 的 Config 字段多数是 public，
     * 但嵌套类型里存在 private 成员）。
     */
    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || f.isSynthetic()) {
                    return null;
                }
                if (!Modifier.isPublic(mod)) {
                    // 非 public 字段需要显式打开；在普通 classpath 应用里这是允许的。
                    f.setAccessible(true);
                }
                return f;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            } catch (RuntimeException ignored) {
                // setAccessible 被模块系统拒绝时，退化为只读 public 字段
                try {
                    Field f = c.getDeclaredField(name);
                    if (Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())) {
                        return f;
                    }
                } catch (NoSuchFieldException ignored2) {
                    // 忽略
                }
                return null;
            }
        }
        return null;
    }

    private static boolean isWritableScalar(Class<?> type) {
        return type == boolean.class || type == Boolean.class
            || type == int.class || type == Integer.class
            || type == long.class || type == Long.class
            || type == short.class || type == Short.class
            || type == byte.class || type == Byte.class
            || type == double.class || type == Double.class
            || type == float.class || type == Float.class
            || type == String.class
            || type.isEnum();
    }

    /** 把 JSON 解出来的值转成目标字段类型；转不了返回 {@link #SKIP}。 */
    private static Object coerce(Class<?> type, Object raw) {
        if (raw == null) {
            return SKIP;
        }
        if (type == String.class) {
            return raw instanceof String s ? s : String.valueOf(raw);
        }
        if (type == boolean.class || type == Boolean.class) {
            if (raw instanceof Boolean b) return b;
            if (raw instanceof String s) {
                String t = s.trim();
                if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
            }
            return SKIP;
        }
        if (type.isEnum()) {
            String name = String.valueOf(raw).trim();
            if (name.isEmpty()) return SKIP;
            return enumValue(type, name);
        }

        Double numeric = toDouble(raw);
        if (numeric == null || !Double.isFinite(numeric)) {
            return SKIP;
        }
        double d = numeric;

        if (type == int.class || type == Integer.class) {
            return (int) clamp(d, Integer.MIN_VALUE, Integer.MAX_VALUE, type);
        }
        if (type == long.class || type == Long.class) {
            return (long) clamp(d, (double) Long.MIN_VALUE, (double) Long.MAX_VALUE, type);
        }
        if (type == short.class || type == Short.class) {
            return (short) clamp(d, Short.MIN_VALUE, Short.MAX_VALUE, type);
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) clamp(d, Byte.MIN_VALUE, Byte.MAX_VALUE, type);
        }
        if (type == double.class || type == Double.class) {
            return d;
        }
        if (type == float.class || type == Float.class) {
            return (float) d;
        }
        return SKIP;
    }

    /** 枚举名大小写必须精确匹配，写错时让 IllegalArgumentException 冒泡成一条可读的失败原因。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class) type, name);
    }

    private static Double toDouble(Object raw) {
        if (raw instanceof Number n) return n.doubleValue();
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 越界直接报错而不是静默截断 —— 静默截断会让用户以为配置生效了。 */
    private static double clamp(double value, double min, double max, Class<?> type) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("value out of range for " + type.getSimpleName() + ": " + value);
        }
        return value;
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        if (e instanceof NoSuchFieldException) {
            return "unknown field '" + msg + "'";
        }
        if (e instanceof IllegalArgumentException) {
            return msg != null ? msg : "invalid value";
        }
        return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}

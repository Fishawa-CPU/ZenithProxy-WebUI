package com.zenith.util.config;

import com.zenith.Globals;
import com.zenith.plugin.DefaultGsonConfigSerializer;

import java.io.FileReader;
import java.io.Reader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 热重载支持：把磁盘上新解析出来的 {@link Config} 原地合并进正在使用的实例。
 * <p>
 * {@code Globals.CONFIG} 是 {@code static final}，不能换引用，所以只能逐字段覆盖。
 * 目标对象里那些 {@code public final} 的嵌套配置对象（{@code client}、{@code server}…）
 * 通过递归写进去，而不是替换引用——否则那些持有旧引用的子系统会看不到新值。
 */
public final class ConfigReloader {

    private ConfigReloader() {
    }

    /** 解析 config.json，但不走 {@link Globals#loadConfig()}：那条路径解析失败会直接退出进程。 */
    public static Config loadFresh() throws Exception {
        try (Reader reader = new FileReader(Globals.CONFIG_FILE)) {
            Config config = DefaultGsonConfigSerializer.INSTANCE.read(Config.class, reader);
            if (config == null) throw new IllegalStateException("config.json 解析结果为空");
            return config;
        }
    }

    /**
     * 把 src 的字段值合并进 dst。
     *
     * @return 是否有字段真的变了
     */
    public static boolean merge(Object src, Object dst) {
        return copy(src, dst, new IdentityHashMap<>());
    }

    private static boolean copy(Object src, Object dst, IdentityHashMap<Object, Object> seen) {
        if (src == null || dst == null || src.getClass() != dst.getClass()) return false;
        if (seen.put(dst, dst) != null) return false;

        boolean changed = false;
        for (Field f : fieldsOf(dst.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
            try {
                f.setAccessible(true);
                Object sv = f.get(src);
                Object dv = f.get(dst);

                if (sv == null) {
                    if (!Modifier.isFinal(f.getModifiers()) && dv != null) {
                        f.set(dst, null);
                        changed = true;
                    }
                    continue;
                }

                Class<?> type = f.getType();

                // 值类型：直接覆盖
                if (type.isPrimitive() || type == String.class || type.isEnum()
                    || type == Boolean.class || type == Character.class
                    || Number.class.isAssignableFrom(type)) {
                    if (dv == null || !dv.equals(sv)) {
                        f.set(dst, sv);
                        changed = true;
                    }
                    continue;
                }

                if (type.isArray()) {
                    int n = Array.getLength(sv);
                    Object arr = Array.newInstance(type.getComponentType(), n);
                    System.arraycopy(sv, 0, arr, 0, n);
                    f.set(dst, arr);
                    changed = true;
                    continue;
                }

                // 集合：final 引用不能换，只能清空重填
                if (dv instanceof Collection<?> dc && sv instanceof Collection<?> sc) {
                    List<Object> want = new ArrayList<>(sc);
                    List<Object> have = new ArrayList<>(dc);
                    if (!have.equals(want)) {
                        @SuppressWarnings("unchecked")
                        Collection<Object> target = (Collection<Object>) dc;
                        target.clear();
                        target.addAll(want);
                        changed = true;
                    }
                    continue;
                }

                if (dv instanceof Map<?, ?> dm && sv instanceof Map<?, ?> sm) {
                    if (!dm.equals(sm)) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> target = (Map<Object, Object>) dm;
                        target.clear();
                        target.putAll((Map<?, ?>) sm);
                        changed = true;
                    }
                    continue;
                }

                // 嵌套配置对象：递归合并
                if (dv != null && dv.getClass() == sv.getClass()) {
                    if (copy(sv, dv, seen)) changed = true;
                    continue;
                }

                // 其余情况（类型对不上、或原来是 null）只能换引用
                if (!Modifier.isFinal(f.getModifiers())) {
                    f.set(dst, sv);
                    changed = true;
                }
            } catch (Throwable t) {
                // 单个字段失败不影响其它字段
                Globals.DEFAULT_LOG.debug("reload: skip field {}: {}", f.getName(), t.toString());
            }
        }
        return changed;
    }

    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) out.add(f);
        }
        return out;
    }
}

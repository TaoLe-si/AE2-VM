package com.ae2vm.addon.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 8 版的 {@code List.of}/{@code Map.of}/{@code List.copyOf}/{@code Map.copyOf}
 * （1.20 的 bench 测试与 {@code com.moakiee} 参考实现是 Java 16 写的，整包迁到
 * 1.12.2-nova 时必须降级语法/API —— 测试运行在 JDK 8 上）。
 *
 * <p>只用于测试。与 JDK 版的语义差别：返回的集合不保证真不可变
 * （{@code Arrays.asList} 支持 set、不支持 add；map 是 {@code LinkedHashMap} 包一层 unmodifiable），
 * 用例本身只读不增删。{@link #map(Object...)} 用交替的 k,v 变长参数，
 * 键值类型靠目标类型推断，所以调用点必须写成有目标类型的形式（赋值/实参位置），
 * 不要拿来当 {@code Object} 用。
 */
public final class J8 {

    private J8() {
    }

    @SafeVarargs
    public static <T> List<T> list(T... items) {
        return items.length == 0 ? Collections.<T>emptyList() : Arrays.asList(items);
    }

    public static <T> List<T> copyOf(java.util.Collection<? extends T> src) {
        return src.isEmpty() ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(src));
    }

    public static <K, V> Map<K, V> mapCopyOf(Map<? extends K, ? extends V> src) {
        return src.isEmpty() ? Collections.<K, V>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<K, V>(src));
    }

    /** 交替的 {@code k1, v1, k2, v2, ...}；空参返回空表。 */
    public static <K, V> Map<K, V> map(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("J8.map needs an even number of args, was " + kv.length);
        }
        if (kv.length == 0) {
            return Collections.<K, V>emptyMap();
        }
        Map<K, V> m = new LinkedHashMap<K, V>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((K) kv[i], (V) kv[i + 1]);
        }
        return m;
    }
}

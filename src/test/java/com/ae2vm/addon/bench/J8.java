package com.ae2vm.addon.bench;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java 8 版的 {@code List.of}/{@code Map.of}（1.20 的 bench 测试是 Java 16 写的，
 * 整包迁到 1.12.2-nova 时必须降级语法/API —— 测试运行在 JDK 8 上）。
 * 只用于测试，语义差别：返回的集合不保证不可变（{@code Arrays.asList} 支持 set，
 * 不支持 add），bench 用例只读不增删。
 */
public final class J8 {

    private J8() {
    }

    @SafeVarargs
    public static <T> List<T> list(T... items) {
        return items.length == 0 ? Collections.<T>emptyList() : Arrays.asList(items);
    }

    public static <T> List<T> copyOf(List<? extends T> src) {
        return src.isEmpty() ? Collections.<T>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<T>(src));
    }

    public static Map<Object, Object> map() {
        return Collections.emptyMap();
    }

    public static <K, V> Map<K, V> map(K k1, V v1) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        return m;
    }

    public static <K, V> Map<K, V> map(K k1, V v1, K k2, V v2) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    public static <K, V> Map<K, V> map(K k1, V v1, K k2, V v2, K k3, V v3) {
        Map<K, V> m = new LinkedHashMap<K, V>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }
}

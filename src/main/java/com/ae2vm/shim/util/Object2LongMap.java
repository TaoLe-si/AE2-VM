package com.ae2vm.shim.util;

import java.util.Set;

/**
 * 1.10.2 替身：本版本**用不了 fastutil**。实测三重证据 ——
 * <ul>
 *   <li>MC 1.10.2 运行时带的是 Mojang 裁剪版 {@code fastutil-7.0.12_mojang}：全 jar 只有
 *       355 个类，{@code objects} 包只剩 55 个，{@code Object2LongMap} 与
 *       {@code Object2LongOpenHashMap} 都不在其中（javap 报"找不到类"）；</li>
 *   <li>Forge 1.10.2 的 {@code userdev/dev.json} 18 个依赖里没有 fastutil，所以 FG2 也
 *       给不出编译类路径；</li>
 *   <li>AE2 rv4 自己对 {@code it.unimi.dsi} 的引用数 = 0（它那一代还没用 fastutil）。</li>
 * </ul>
 *
 * <p>类型名与成员签名（{@code Entry.getKey()/getLongValue()/getValue()/setValue(long)}）
 * 刻意与 fastutil 保持一致：内核 {@code CraftingVM}（9 处）与 {@code AE2VMCrafting}（3 处）
 * 里的全限定引用只需换包名，算式一行都不动 —— 这是"与 1.20.1 基线同口径"的前提。
 */
public interface Object2LongMap<K> {

    /** key → long 的一条记账。{@link #setValue(long)} 写回宿主表，与 fastutil 的活视图同语义。 */
    interface Entry<K> {

        K getKey();

        long getLongValue();

        Long getValue();

        Long setValue(long value);
    }

    Set<Entry<K>> entrySet();
}

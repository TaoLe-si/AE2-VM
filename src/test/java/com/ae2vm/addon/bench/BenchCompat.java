package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import com.ae2vm.shim.api.stacks.AEKey;
import java.util.Map;
import java.util.TreeMap;

/**
 * (v9, 1.17.1) Bridge from a {@link ICraftingPlan}'s v9 {@code MixedStackList}
 * collections to the string-keyed maps the bench asserts on.
 * <p>
 * The 1.18.1 fork iterated {@code plan.usedItems()} as a v10+ {@code KeyCounter-like}
 * collection with {@code getKey()/getLongValue()}; v9 plans carry
 * {@code IAEStack}-typed lists whose amounts live in the stack size. This shim reads
 * them back into the bench's string-key domain.
 */
public final class BenchCompat {

    private BenchCompat() {
    }

    /** missingItems as {benchKey.toString() → amount}. */
    public static Map<String, Long> missing(ICraftingPlan plan) {
        return list(plan.missingItems());
    }

    /** usedItems as {benchKey.toString() → amount}. */
    public static Map<String, Long> used(ICraftingPlan plan) {
        return list(plan.usedItems());
    }

    /** emittedItems as {benchKey.toString() → amount}. */
    public static Map<String, Long> emitted(ICraftingPlan plan) {
        return list(plan.emittedItems());
    }

    /**
     * bench 断言域里的规范字符串键，取的就是 1.20.1 里 {@code BenchAEKey}/{@code VariantKey}
     * 的 {@code toString()} 形状：无变体 → {@code "A"}，有变体 → {@code "A[bee_b]"}。
     *
     * <p>不能再依赖 {@code String.valueOf(...)}：R2 之后键是产品里的真 {@code AEItemKey}
     * （它的 {@code toString()} 只给注册名），而计划集合里装的是 {@code IAEItemStack}
     * （假栈的 {@code toString()} 带数量），两边永远对不上，于是一切 {@code usedOf/missingOf}
     * 查出 0、参考规划器也把自己的图认成别人的键。这里统一从一个地方还原。
     */
    public static String stringOf(Object keyOrStack) {
        AEKey key = null;
        if (keyOrStack instanceof AEKey) {
            key = (AEKey) keyOrStack;
        } else if (keyOrStack instanceof appeng.api.storage.data.IAEItemStack) {
            key = com.ae2vm.shim.api.stacks.AEItemKey
                    .wrap((appeng.api.storage.data.IAEItemStack) keyOrStack);
        }
        if (key == null) {
            return String.valueOf(keyOrStack);
        }
        String base = BenchAEKey.id(key);
        String variant = VariantKey.variant(key);
        return variant.isEmpty() ? base : base + "[" + variant + "]";
    }

    private static Map<String, Long> list(Iterable<? extends appeng.api.storage.data.IAEStack> it) {
        Map<String, Long> out = new TreeMap<>();
        if (it == null) return out;
        for (appeng.api.storage.data.IAEStack st : it) {
            if (st == null || st.getStackSize() == 0) continue;
            out.put(stringOf(st), st.getStackSize());
        }
        return out;
    }

    /**
     * Amount of a key in one of the string-key maps read via {@link #missing}/{@link #used}
     * (0 when absent) — the bench's old {@code BenchCompat.missingOf(plan, key))} idiom.
     */
    public static long get(Map<String, Long> map, AEKey key) {
        Long v = map.get(stringOf(key));
        return v == null ? 0L : v;
    }

    /** Amount of a key in a plan's usedItems (0 when absent). */
    public static long usedOf(ICraftingPlan plan, AEKey key) {
        return get(used(plan), key);
    }

    /** Amount of a key in a plan's missingItems (0 when absent). */
    public static long missingOf(ICraftingPlan plan, AEKey key) {
        return get(missing(plan), key);
    }
}

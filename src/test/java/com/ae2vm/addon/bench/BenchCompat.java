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

    private static Map<String, Long> list(Iterable<? extends appeng.api.storage.data.IAEStack> it) {
        Map<String, Long> out = new TreeMap<>();
        if (it == null) return out;
        for (appeng.api.storage.data.IAEStack st : it) {
            if (st == null || st.getStackSize() == 0) continue;
            out.put(String.valueOf(st), st.getStackSize());
        }
        return out;
    }

    /**
     * Amount of a key in one of the string-key maps read via {@link #missing}/{@link #used}
     * (0 when absent) — the bench's old {@code BenchCompat.missingOf(plan, key))} idiom.
     */
    public static long get(Map<String, Long> map, AEKey key) {
        Long v = map.get(String.valueOf(key));
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

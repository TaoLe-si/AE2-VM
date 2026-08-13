package com.ae2vm.addon.api;

import appeng.api.config.FuzzyMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * rv4 shim for AE2 15.x's {@code KeyCounter}: a long-valued map keyed by {@link AEKey}.
 */
public class KeyCounter extends HashMap<AEKey, Long> {

    public void add(AEKey key, long amount) {
        long cur = get(key);
        long next = cur + amount;
        if (next == 0) {
            remove(key);
        } else {
            put(key, next);
        }
    }

    /** Primitive get with 0 default (mirrors the modern {@code KeyCounter.get}). */
    public long get(AEKey key) {
        Long v = super.get(key);
        return v == null ? 0L : v.longValue();
    }

    /**
     * Entries whose key matches {@code filter} under the given {@link FuzzyMode}.
     * {@code IGNORE_ALL} matches the same item (any NBT/damage) or the same fluid.
     */
    public Iterable<Map.Entry<AEKey, Long>> findFuzzy(AEKey filter, FuzzyMode mode) {
        List<Map.Entry<AEKey, Long>> out = new ArrayList<>();
        if (filter == null) {
            return out;
        }
        for (Map.Entry<AEKey, Long> e : entrySet()) {
            AEKey k = e.getKey();
            if (k == null) {
                continue;
            }
            if (k.equals(filter)) {
                out.add(e);
                continue;
            }
            if (mode == FuzzyMode.IGNORE_ALL) {
                if (filter.isItem() && k.isItem() && filter.getItem() == k.getItem()) {
                    out.add(e);
                } else if (!filter.isItem() && !k.isItem() && filter.getFluid() == k.getFluid()) {
                    out.add(e);
                }
            }
        }
        return out;
    }
}

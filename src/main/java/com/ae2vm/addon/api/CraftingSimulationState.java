package com.ae2vm.addon.api;

import appeng.api.config.Actionable;

/**
 * rv4 shim for AE2 15.x's {@code CraftingSimulationState}.
 *
 * <p>Keeps an internal (VM-inserted / crafted) stock which is consumed on
 * {@code MODULATE} extract, and reads the live network snapshot through
 * {@link #simulateExtractParent}. The network parent is a read-only snapshot — the VM
 * performs its own stock conservation in the aggregation ({@code usedItems} /
 * {@code stockFromNetwork} / {@code realStockOf}), exactly as it did against the 1.20.1
 * {@code RealtimeNetworkCraftingSimulationState}.
 *
 * <p>Byte accounting is a plain double field; no mixin accessor is needed (the 1.20.1
 * {@code CraftingSimulationStateAccessor} is dropped).
 */
public abstract class CraftingSimulationState {

    private final KeyCounter inventory = new KeyCounter();
    private double bytes = 0.0;

    /** Live network stock available for {@code what} (read-only snapshot). */
    protected abstract long simulateExtractParent(AEKey what, long amount);

    /** Fuzzy variants of {@code input} present in the live network. */
    protected abstract Iterable<AEKey> findFuzzyParent(AEKey input);

    public void addBytes(double delta) {
        bytes += delta;
    }

    public double getBytes() {
        return bytes;
    }

    /** Byte-accounting helper (no side effects in the rv4 shim). */
    public void addStackBytes(AEKey what, long count, long amount) {
        // accounted via addBytes by the VM; no-op here.
    }

    /** Ignore a key (no-op in the rv4 shim — the VM handles it explicitly). */
    public void ignore(AEKey what) {
        // no-op
    }

    public long insert(AEKey what, long amount, Actionable mode) {
        if (mode == Actionable.MODULATE) {
            inventory.add(what, amount);
        }
        return amount;
    }

    public long extract(AEKey what, long amount, Actionable mode) {
        long fromInternal = Math.min(inventory.get(what), amount);
        long fromParent = simulateExtractParent(what, amount - fromInternal);
        long got = fromInternal + fromParent;
        if (mode == Actionable.MODULATE && fromInternal > 0) {
            inventory.add(what, -fromInternal);
        }
        return got;
    }

    /** Crafting bookkeeping (no side effects in the rv4 shim). */
    public void addCrafting(IPatternDetails pattern, long amount) {
        // tracked by the VM's patternTimes; no-op here.
    }
}

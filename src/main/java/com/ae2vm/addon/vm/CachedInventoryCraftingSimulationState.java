package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import com.google.common.collect.Iterables;

import java.util.Map;

/**
 * A {@link CraftingSimulationState} backed by the network's CACHED inventory
 * ({@link IStorageService#getCachedInventory()} — updated at most once per tick,
 * O(1) when fresh). The KeyCounter is materialized LAZILY on first access, so a
 * warm-path miss costs nothing extra (no inventory walk at all).
 * <p>
 * Used ONLY to re-verify a memoized plan on the warm path (see
 * {@code CraftingVM.tryCachedPlan}). The slow path always uses the LIVE
 * {@link RealtimeNetworkCraftingSimulationState}; this class never leaks into the
 * actual plan construction.
 */
public class CachedInventoryCraftingSimulationState extends CraftingSimulationState {
    private final IStorageService storage;
    private KeyCounter list;

    public CachedInventoryCraftingSimulationState(IStorageService storage) {
        this.storage = storage;
    }

    private KeyCounter list() {
        if (list == null) {
            // Copy into a private KeyCounter — the cached inventory is shared and must
            // not be modified (mirrors AE2's own NetworkCraftingSimulationState).
            list = new KeyCounter();
            for (var stack : storage.getCachedInventory()) {
                long amount = stack.getLongValue();
                if (amount > 0) {
                    list.add(stack.getKey(), amount);
                }
            }
        }
        return list;
    }

    @Override
    protected long simulateExtractParent(AEKey what, long amount) {
        return Math.min(list().get(what), amount);
    }

    @Override
    protected Iterable<AEKey> findFuzzyParent(AEKey input) {
        return Iterables.transform(list().findFuzzy(input, FuzzyMode.IGNORE_ALL), Map.Entry::getKey);
    }
}
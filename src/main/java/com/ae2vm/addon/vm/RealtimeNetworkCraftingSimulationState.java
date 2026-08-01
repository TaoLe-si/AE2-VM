package com.ae2vm.addon.vm;

import appeng.api.config.FuzzyMode;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.inv.CraftingSimulationState;
import com.google.common.collect.Iterables;

import java.util.Map;

/**
 * A {@link CraftingSimulationState} that ALWAYS snapshots the LIVE network
 * inventory ({@code MEStorage.getAvailableStacks()}), regardless of who the
 * requester is.
 * <p>
 * AE2's own {@code NetworkCraftingSimulationState} falls back to
 * {@code getCachedInventory()} for non-player requesters. That cached snapshot
 * can be stale, which makes the plan's {@code usedItems} exceed what the CPU
 * can actually extract at submit time — AE2 then refuses the job with
 * {@code CraftErrorMissingIngredient} ("无法从网络中取出某些材料").
 * <p>
 * We always read the live inventory so the plan matches exactly what
 * {@code CraftingCpuHelper.tryExtractInitialItems} can extract.
 */
public class RealtimeNetworkCraftingSimulationState extends CraftingSimulationState {
    private final KeyCounter list;

    public RealtimeNetworkCraftingSimulationState(IStorageService storage) {
        this.list = storage.getInventory().getAvailableStacks();
    }

    @Override
    protected long simulateExtractParent(AEKey what, long amount) {
        return Math.min(list.get(what), amount);
    }

    @Override
    protected Iterable<AEKey> findFuzzyParent(AEKey input) {
        return Iterables.transform(list.findFuzzy(input, FuzzyMode.IGNORE_ALL), Map.Entry::getKey);
    }
}

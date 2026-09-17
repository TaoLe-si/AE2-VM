package com.ae2vm.addon.vm;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.StorageChannels;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.MixedStackList;
import appeng.crafting.inv.CraftingSimulationState;
import java.util.Collection;

/**
 * A {@link CraftingSimulationState} that ALWAYS snapshots the LIVE network
 * inventory, regardless of who the requester is.
 * <p>
 * AE2's own {@code NetworkCraftingSimulationState} falls back to
 * {@code getCachedInventory()} for non-player requesters. That cached snapshot
 * can be stale, which makes the plan's {@code usedItems} exceed what the CPU
 * can actually extract at submit time — AE2 then refuses the job with
 * {@code CraftErrorMissingIngredient} ("无法从网络中取出某些材料").
 * <p>
 * We always read the live inventory so the plan matches exactly what
 * {@code CraftingCpuHelper.tryExtractInitialItems} can extract.
 * <p>
 * AE2 v9 (1.17.1): the network inventory is channel-based
 * ({@code IStorageService.getInventory(channel).getStorageList()}); the item
 * channel covers everything the VM models (the mod is item-only).
 */
public class RealtimeNetworkCraftingSimulationState extends CraftingSimulationState {
    private final MixedStackList list = new MixedStackList();

    public RealtimeNetworkCraftingSimulationState(IStorageService storage) {
        this(storage, null);
    }

    public RealtimeNetworkCraftingSimulationState(IStorageService storage, IActionSource src) {
        var monitor = storage.getInventory(StorageChannels.items());
        if (monitor == null) {
            return;
        }
        for (var stack : monitor.getStorageList()) {
            this.list.addStorage(monitor.extractItems(stack, Actionable.SIMULATE, src));
        }
    }

    @Override
    protected IAEStack simulateExtractParent(IAEStack input) {
        var precise = list.findPrecise(input);
        if (precise == null) {
            return null;
        }
        return IAEStack.copy(input, Math.min(input.getStackSize(), precise.getStackSize()));
    }

    @Override
    protected Collection<IAEStack> findFuzzyParent(IAEStack input) {
        return list.findFuzzy(input, FuzzyMode.IGNORE_ALL);
    }

    /** Live stock of an item key (0 when absent) — used by the VM's realtime stock checks. */
    public long stockOf(appeng.api.stacks.AEItemKey key) {
        if (key == null) {
            return 0;
        }
        var precise = list.findPrecise(key.toStack(1));
        return precise == null ? 0 : precise.getStackSize();
    }
}

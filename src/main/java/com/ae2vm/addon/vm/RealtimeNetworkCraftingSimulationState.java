package com.ae2vm.addon.vm;

import java.util.Collection;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.security.IActionSource;
import com.ae2vm.shim.api.networking.storage.IStorageService;
import appeng.api.storage.IMEMonitor;
import com.ae2vm.shim.api.storage.StorageChannels;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import com.ae2vm.shim.api.storage.data.MixedStackList;
import com.ae2vm.shim.crafting.inv.CraftingSimulationState;

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
 * We always read the live inventory so the plan matches exactly what the crafting
 * CPU can extract at submit time.
 * <p>
 * AE2 v8 (1.16.5): the network inventory is channel-based
 * ({@code IStorageGrid.getInventory(IItemStorageChannel)} → {@code IMEMonitor}); the item
 * channel covers everything the VM models (the mod is item-only).
 */
public class RealtimeNetworkCraftingSimulationState extends CraftingSimulationState {
    private final MixedStackList list = new MixedStackList();

    public RealtimeNetworkCraftingSimulationState(IStorageService storage) {
        this(storage, null);
    }

    public RealtimeNetworkCraftingSimulationState(IStorageService storage, IActionSource src) {
        IMEMonitor<IAEItemStack> monitor = storage == null ? null : storage.getInventory(StorageChannels.items());
        if (monitor == null) {
            return;
        }
        for (IAEItemStack stack : monitor.getStorageList()) {
            this.list.addStorage(monitor.extractItems(stack, Actionable.SIMULATE, src));
        }
    }

    @Override
    protected IAEStack simulateExtractParent(IAEStack input) {
        IAEStack precise = this.list.findPrecise(input);
        if (precise == null) {
            return null;
        }
        IAEStack copy = input.copy();
        copy.setStackSize(Math.min(input.getStackSize(), precise.getStackSize()));
        return copy;
    }

    @Override
    protected Collection<IAEStack> findFuzzyParent(IAEStack input) {
        return this.list.findFuzzy(input, FuzzyMode.IGNORE_ALL);
    }

    /** Live stock of an item key (0 when absent) — used by the VM's realtime stock checks. */
    public long stockOf(com.ae2vm.shim.api.stacks.AEItemKey key) {
        if (key == null) {
            return 0;
        }
        IAEStack precise = this.list.findPrecise(key.toStack(1));
        return precise == null ? 0 : precise.getStackSize();
    }
}

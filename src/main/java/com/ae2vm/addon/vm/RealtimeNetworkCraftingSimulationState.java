package com.ae2vm.addon.vm;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2vm.addon.api.AEKey;
import com.ae2vm.addon.api.CraftingSimulationState;
import com.ae2vm.addon.api.KeyCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link CraftingSimulationState} that ALWAYS snapshots the LIVE network
 * inventory ({@code MEStorage.getAvailableStacks()}), regardless of who the
 * requester is.
 *
 * <p>On rv4 (1.10.2) this snapshots {@link IStorageGrid#getItemInventory()} and
 * {@link IStorageGrid#getFluidInventory()} into a {@link KeyCounter}. The snapshot
 * is read-only for the VM — the VM's own aggregation performs the stock
 * conservation, exactly as on 1.20.1.
 */
public class RealtimeNetworkCraftingSimulationState extends CraftingSimulationState {
    private final KeyCounter list = new KeyCounter();

    public RealtimeNetworkCraftingSimulationState(IStorageGrid storage) {
        IItemList<IAEItemStack> items = AEApi.instance().storage().createItemList();
        storage.getItemInventory().getAvailableItems(items);
        for (IAEItemStack is : items) {
            list.add(AEKey.of(is), is.getStackSize());
        }

        IItemList<IAEFluidStack> fluids = AEApi.instance().storage().createFluidList();
        storage.getFluidInventory().getAvailableItems(fluids);
        for (IAEFluidStack fs : fluids) {
            list.add(AEKey.of(fs), fs.getStackSize());
        }
    }

    @Override
    protected long simulateExtractParent(AEKey what, long amount) {
        return Math.min(list.get(what), amount);
    }

    @Override
    protected Iterable<AEKey> findFuzzyParent(AEKey input) {
        List<AEKey> out = new ArrayList<>();
        for (Map.Entry<AEKey, Long> e : list.findFuzzy(input, FuzzyMode.IGNORE_ALL)) {
            out.add(e.getKey());
        }
        return out;
    }
}

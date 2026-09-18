package com.ae2vm.shim.crafting.inv;

import java.util.Collection;

import appeng.api.storage.data.IAEStack;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code com.ae2vm.shim.crafting.inv.ChildCraftingSimulationState}.
 * <p>
 * A discardable child state: everything it accumulates (bytes, craft counts) can be merged
 * back into the parent with {@link #applyDiff(CraftingSimulationState)}, and stock lookups
 * are delegated to the parent.
 */
public class ChildCraftingSimulationState extends CraftingSimulationState {

    private final ICraftingInventory parent;

    public ChildCraftingSimulationState(ICraftingInventory parent) {
        this.parent = parent;
    }

    @Override
    protected IAEStack simulateExtractParent(IAEStack input) {
        return this.simulateExtractParent(input, appeng.api.config.Actionable.SIMULATE);
    }

    @Override
    protected IAEStack simulateExtractParent(IAEStack input, appeng.api.config.Actionable mode) {
        return this.parent == null ? null : this.parent.extractItems(input, mode);
    }

    @Override
    protected Collection<IAEStack> findFuzzyParent(IAEStack input) {
        return this.parent == null ? java.util.Collections.emptyList() : this.parent.findFuzzyTemplates(input);
    }
}

package com.ae2vm.addon.v8;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;

/**
 * The VM's v8-shaped crafting job.
 * <p>
 * AE2 v8 (1.16.5) consumes jobs through {@code appeng.api.networking.crafting.ICraftingJob}
 * (output + byte total + plan population). Unlike v9 there is no plan object carrying
 * per-pattern craft counts, so this class carries the VM's result:
 * <ul>
 *   <li>{@code usedItems} — what must be pulled out of the network up front</li>
 *   <li>{@code emittedItems} — what the job will push back in</li>
 *   <li>{@code patternTimes} — pattern × craft count, handed to
 *       {@code CraftingCPUCluster#addCrafting} at submit time</li>
 * </ul>
 * {@code com.ae2vm.addon.mixin.CraftingCPUClusterMixin} intercepts
 * {@code CraftingCPUCluster#submitJob} (which otherwise rejects anything that is not a
 * vanilla {@code appeng.crafting.CraftingJob}) and executes this plan directly.
 */
public final class VMCraftingJob implements ICraftingJob {

    private final IAEItemStack output;
    private final long bytes;
    private final boolean simulation;
    private final IItemList<IAEItemStack> usedItems;
    private final IItemList<IAEItemStack> emittedItems;
    private final IItemList<IAEItemStack> missingItems;
    private final Map<ICraftingPatternDetails, Long> patternTimes;

    public VMCraftingJob(IAEItemStack output, long bytes, boolean simulation,
            IItemList<IAEItemStack> usedItems, IItemList<IAEItemStack> emittedItems,
            IItemList<IAEItemStack> missingItems, Map<ICraftingPatternDetails, Long> patternTimes) {
        this.output = output;
        this.bytes = bytes;
        this.simulation = simulation;
        this.usedItems = usedItems;
        this.emittedItems = emittedItems;
        this.missingItems = missingItems;
        this.patternTimes = patternTimes == null ? new LinkedHashMap<>() : patternTimes;
    }

    @Override
    public boolean isSimulation() {
        return this.simulation;
    }

    @Override
    public long getByteTotal() {
        return this.bytes;
    }

    public IAEItemStack getOutput() {
        return this.output;
    }

    public IItemList<IAEItemStack> getUsedItems() {
        return this.usedItems;
    }

    public IItemList<IAEItemStack> getEmittedItems() {
        return this.emittedItems;
    }

    public IItemList<IAEItemStack> getMissingItems() {
        return this.missingItems;
    }

    /** v8 pattern × craft count — consumed by the crafting CPU at submit time. */
    public Map<ICraftingPatternDetails, Long> getPatternTimes() {
        return Collections.unmodifiableMap(this.patternTimes);
    }

    @Override
    public void populatePlan(IItemList<IAEItemStack> plan) {
        if (plan == null) {
            return;
        }
        if (this.usedItems != null) {
            for (IAEItemStack is : this.usedItems) {
                plan.add(is.copy());
            }
        }
        if (this.missingItems != null) {
            for (IAEItemStack is : this.missingItems) {
                IAEItemStack missing = is.copy();
                plan.add(missing);
            }
        }
        if (this.emittedItems != null) {
            for (IAEItemStack is : this.emittedItems) {
                IAEItemStack emitted = is.copy();
                emitted.setCountRequestable(emitted.getStackSize());
                plan.addRequestable(emitted);
            }
        }
    }
}

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

    /**
     * 把 VM 的 plan 摊成 v8 合成确认界面的那张表。
     * <p>
     * v8 的 {@code appeng.container.me.crafting.CraftingPlanSummary.fromJob} 只从这张列表算三列：
     * {@code stored = 网络对 stackSize 的 SIMULATE 可用量}、{@code missing = stackSize - stored}、
     * {@code crafting = countRequestable}。
     * <p>
     * 1.20.1 baseline 上界面数字是对的，因为 AE2 v15 的 fromJob 自己按下面这套累加
     * （反编译 15.4.10 的 appeng.menu.me.crafting.CraftingPlanSummary 核实；
     * v15 完全不读 finalOutput，产物行是靠 patternTimes 才出现在表里的）：
     * <pre>
     *   usedItems[key]                    -> stored
     *   missingItems[key]                 -> stored
     *   emittedItems[key]                 -> stored 和 crafting
     *   patternTimes[p] x p.getOutputs()  -> crafting += out.amount * times
     * </pre>
     * 这里就是把同一套累加搬到 v8 的两个载体上：stackSize 装 stored 基数，countRequestable 装
     * crafting。数值全部取 VM 已算出的结果，本类不做任何重新推导。
     */
    @Override
    public void populatePlan(IItemList<IAEItemStack> plan) {
        if (plan == null) {
            return;
        }

        // stored 基数 = usedItems + missingItems + emittedItems
        if (this.usedItems != null) {
            for (IAEItemStack is : this.usedItems) {
                plan.add(is.copy());
            }
        }
        if (this.missingItems != null) {
            for (IAEItemStack is : this.missingItems) {
                plan.add(is.copy());
            }
        }
        if (this.emittedItems != null) {
            for (IAEItemStack is : this.emittedItems) {
                plan.add(is.copy());

                final IAEItemStack emittedCrafted = is.copy();
                emittedCrafted.setCountRequestable(emittedCrafted.getStackSize());
                plan.addRequestable(emittedCrafted);
            }
        }

        // crafting = Σ (样板执行次数 x 该样板每个 output 的单次产量)
        for (Map.Entry<ICraftingPatternDetails, Long> e : this.patternTimes.entrySet()) {
            final ICraftingPatternDetails details = e.getKey();
            final Long times = e.getValue();
            if (details == null || times == null || times <= 0L || details.getOutputs() == null) {
                continue;
            }
            for (IAEItemStack out : details.getOutputs()) {
                if (out == null) {
                    continue;
                }
                final IAEItemStack crafted = out.copy();
                crafted.setCountRequestable(crafted.getStackSize() * times);
                plan.addRequestable(crafted);
            }
        }
    }
}

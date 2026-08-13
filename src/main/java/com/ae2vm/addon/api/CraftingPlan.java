package com.ae2vm.addon.api;

import java.util.Map;

/**
 * rv4 shim for AE2 15.x's {@code CraftingPlan}: a plain data holder for the VM result.
 */
public class CraftingPlan implements ICraftingPlan {

    private final GenericStack finalOutput;
    private final long bytes;
    private final boolean simulation;
    private final boolean multiplePaths;
    private final KeyCounter usedItems;
    private final KeyCounter emittedItems;
    private final KeyCounter missingItems;
    private final Map<IPatternDetails, Long> patternTimes;

    public CraftingPlan(GenericStack finalOutput, long bytes, boolean simulation, boolean multiplePaths,
                        KeyCounter usedItems, KeyCounter emittedItems, KeyCounter missingItems,
                        Map<IPatternDetails, Long> patternTimes) {
        this.finalOutput = finalOutput;
        this.bytes = bytes;
        this.simulation = simulation;
        this.multiplePaths = multiplePaths;
        this.usedItems = usedItems;
        this.emittedItems = emittedItems;
        this.missingItems = missingItems;
        this.patternTimes = patternTimes;
    }

    @Override
    public GenericStack finalOutput() {
        return finalOutput;
    }

    @Override
    public long bytes() {
        return bytes;
    }

    @Override
    public boolean simulation() {
        return simulation;
    }

    @Override
    public boolean multiplePaths() {
        return multiplePaths;
    }

    @Override
    public KeyCounter usedItems() {
        return usedItems;
    }

    @Override
    public KeyCounter emittedItems() {
        return emittedItems;
    }

    @Override
    public KeyCounter missingItems() {
        return missingItems;
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return patternTimes;
    }
}

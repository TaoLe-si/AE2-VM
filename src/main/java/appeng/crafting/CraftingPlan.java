package appeng.crafting;

import java.util.Collections;
import java.util.Map;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.MixedStackList;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code appeng.crafting.CraftingPlan}.
 * <p>
 * v9 declares it as a {@code record}, which Java 8 does not have; this is the equivalent
 * immutable value class with the same constructor order and accessor names, so the VM core
 * compiles unchanged.
 */
public final class CraftingPlan implements ICraftingPlan {

    private final IAEStack finalOutput;
    private final long bytes;
    private final boolean simulation;
    private final boolean multiplePaths;
    private final MixedStackList usedItems;
    private final MixedStackList emittedItems;
    private final MixedStackList missingItems;
    private final Map<IPatternDetails, Long> patternTimes;

    public CraftingPlan(IAEStack finalOutput, long bytes, boolean simulation, boolean multiplePaths,
            MixedStackList usedItems, MixedStackList emittedItems, MixedStackList missingItems,
            Map<IPatternDetails, Long> patternTimes) {
        this.finalOutput = finalOutput;
        this.bytes = bytes;
        this.simulation = simulation;
        this.multiplePaths = multiplePaths;
        this.usedItems = usedItems;
        this.emittedItems = emittedItems;
        this.missingItems = missingItems;
        this.patternTimes = patternTimes == null ? Collections.emptyMap() : patternTimes;
    }

    @Override
    public IAEStack finalOutput() {
        return this.finalOutput;
    }

    @Override
    public long bytes() {
        return this.bytes;
    }

    @Override
    public boolean simulation() {
        return this.simulation;
    }

    @Override
    public boolean multiplePaths() {
        return this.multiplePaths;
    }

    @Override
    public MixedStackList usedItems() {
        return this.usedItems;
    }

    @Override
    public MixedStackList emittedItems() {
        return this.emittedItems;
    }

    @Override
    public MixedStackList missingItems() {
        return this.missingItems;
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return this.patternTimes;
    }
}

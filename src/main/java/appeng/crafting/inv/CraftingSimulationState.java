package appeng.crafting.inv;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.MixedStackList;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code appeng.crafting.inv.CraftingSimulationState}.
 * <p>
 * v8 has no simulation state at all — its legacy engine simulates directly on
 * {@code appeng.crafting.MECraftingInventory} inside the {@code CraftingJob} tree walk.
 * This shim keeps only what the VM core touches (bytes accounting, per-pattern craft
 * counts, emitted/ignored stacks) and delegates the actual stock lookups to the subclass
 * ({@code simulateExtractParent} / {@code findFuzzyParent}), exactly like v9.
 */
public abstract class CraftingSimulationState implements ICraftingInventory,
        com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {

    private double bytes = 0;
    private final Map<IPatternDetails, Long> crafts = new HashMap<>();
    private final MixedStackList emittedItems = new MixedStackList();
    private final MixedStackList ignored = new MixedStackList();

    /** Parent-side extraction — implemented by the concrete network-backed state. */
    protected abstract IAEStack simulateExtractParent(IAEStack input);

    /** Parent-side fuzzy lookup — implemented by the concrete network-backed state. */
    protected abstract Collection<IAEStack> findFuzzyParent(IAEStack input);

    public void addBytes(double bytes) {
        this.bytes += bytes;
    }

    public double getBytes() {
        return this.bytes;
    }

    public void addCrafting(IPatternDetails details, long crafts) {
        if (details == null || crafts <= 0) {
            return;
        }
        Long prev = this.crafts.get(details);
        this.crafts.put(details, prev == null ? crafts : prev + crafts);
    }

    public Map<IPatternDetails, Long> getCrafts() {
        return this.crafts;
    }

    public void emitItems(IAEStack what) {
        if (what != null && what.isMeaningful()) {
            this.emittedItems.addStorage(what);
        }
    }

    public MixedStackList getEmittedItems() {
        return this.emittedItems;
    }

    public void ignore(IAEStack stack) {
        if (stack != null && stack.isMeaningful()) {
            this.ignored.add(stack);
        }
    }

    public boolean isIgnored(IAEStack stack) {
        return stack != null && this.ignored.findPrecise(stack) != null;
    }

    @Override
    public IAEStack extractItems(IAEStack input, Actionable mode) {
        if (input == null || !input.isMeaningful()) {
            return null;
        }
        return this.simulateExtractParent(input);
    }

    @Override
    public void injectItems(IAEStack input, Actionable mode) {
        // v8 shim: injections are not tracked (the VM models stock, not returns).
    }

    @Override
    public Collection<IAEStack> findFuzzyTemplates(IAEStack input) {
        if (input == null) {
            return java.util.Collections.emptyList();
        }
        return this.findFuzzyParent(input);
    }

    /**
     * v9 {@code addStackBytes(IAEStack, long)}: accounts the byte cost of moving
     * {@code stack} × multiplier. v8 has no simulation state, so the same formula is
     * applied here from the channel's transfer factor / units-per-byte.
     */
    public void addStackBytes(appeng.api.storage.data.IAEStack stack, long multiplier) {
        if (stack == null || !stack.isMeaningful() || multiplier <= 0) {
            return;
        }
        appeng.api.storage.IStorageChannel<?> channel = stack.getChannel();
        double perByte = channel == null ? 8.0 : (double) channel.transferFactor() * channel.getUnitsPerByte();
        if (perByte <= 0) {
            perByte = 8.0;
        }
        double bytes = stack.getStackSize() * (double) multiplier / perByte;
        this.bytes += Math.ceil(bytes);
    }

    /** Merge this child state's diff into its parent (v9 {@code applyDiff}). */
    public void applyDiff(CraftingSimulationState parent) {
        if (parent == null) {
            return;
        }
        parent.bytes += this.bytes;
        for (Map.Entry<IPatternDetails, Long> e : this.crafts.entrySet()) {
            Long prev = parent.crafts.get(e.getKey());
            parent.crafts.put(e.getKey(), prev == null ? e.getValue() : prev + e.getValue());
        }
    }
}

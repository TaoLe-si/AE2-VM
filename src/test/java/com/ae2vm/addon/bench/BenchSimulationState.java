package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.mixin.CraftingSimulationStateAccessor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * In-memory {@link CraftingSimulationState} so the VM runs offline (no IGrid,
 * no Minecraft world). Implements {@link CraftingSimulationStateAccessor} so the
 * VM's {@code buildPlan} can read {@code bytes} exactly like the mixin accessor
 * does in-game.
 */
public final class BenchSimulationState extends CraftingSimulationState
        implements CraftingSimulationStateAccessor {

    private final Map<BenchAEKey, Long> stock;

    public BenchSimulationState(Map<BenchAEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    protected long simulateExtractParent(AEKey what, long amount) {
        long available = what instanceof BenchAEKey k ? stock.getOrDefault(k, 0L) : 0L;
        return Math.min(available, amount);
    }

    @Override
    protected Iterable<AEKey> findFuzzyParent(AEKey input) {
        return List.of(input);
    }

    @Override
    public double getBytes() {
        // Same semantics as the @Accessor mixin: read the private parent `bytes` field.
        try {
            Field f = CraftingSimulationState.class.getDeclaredField("bytes");
            f.setAccessible(true);
            return f.getDouble(this);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Cannot read CraftingSimulationState.bytes", e);
        }
    }
}

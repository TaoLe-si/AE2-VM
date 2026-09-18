package com.ae2vm.addon.bench;

import com.ae2vm.shim.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.mixin.CraftingSimulationStateAccessor;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * In-memory {@link CraftingSimulationState} so the VM runs offline (no IGrid,
 * no Minecraft world). Implements {@link CraftingSimulationStateAccessor} so the
 * VM's {@code buildPlan} can read {@code bytes} exactly like the mixin accessor
 * does in-game.
 * <p>
 * (v9, 1.17.1) The base class's abstract methods are IAEStack-based; string bench
 * keys cannot cross that boundary, so the bench state is compile-retained only
 * (§5) — runtime execution of the bench under v9 is not possible.
 */
public final class BenchSimulationState extends CraftingSimulationState
        implements CraftingSimulationStateAccessor {

    private final Map<BenchAEKey, Long> stock;

    public BenchSimulationState(Map<BenchAEKey, Long> stock) {
        this.stock = stock;
    }

    @Override
    protected appeng.api.storage.data.IAEStack simulateExtractParent(appeng.api.storage.data.IAEStack input) {
        throw new UnsupportedOperationException("bench sim-state cannot bridge into the v9 IAEStack world");
    }

    @Override
    protected java.util.Collection<appeng.api.storage.data.IAEStack> findFuzzyParent(appeng.api.storage.data.IAEStack input) {
        throw new UnsupportedOperationException("bench sim-state cannot bridge into the v9 IAEStack world");
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

package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;

/**
 * (v9, 1.17.1) Bench-side access to a pattern's outputs as shim {@link GenericStack}s.
 * <p>
 * AE2 v9's {@code IPatternDetails} speaks {@code IAEStack[]}, which string bench keys
 * cannot produce. Every bench {@code IPatternDetails} implementation therefore keeps
 * its real, key-based data under {@link #benchOutputs()} and inherits this interface's
 * UOE defaults for the v9 surface ({@code getOutputs} / {@code copyDefinition}).
 * Extending {@code IPatternDetails} here lets those defaults legally override the
 * inherited abstract methods, so bench classes stay one-liners.
 */
public interface BenchPatternAccess extends IPatternDetails {

    /** The pattern's outputs as shim GenericStacks (first = primary). */
    GenericStack[] benchOutputs();

    /** v9 surface: no IAEStack representation exists for bench keys. */
    @Override
    default appeng.api.storage.data.IAEStack[] getOutputs() {
        throw new UnsupportedOperationException("bench pattern cannot bridge into the v9 IAEStack world");
    }

    /** v9 surface: no ItemStack representation exists for bench keys. */
    @Override
    default net.minecraft.world.item.ItemStack copyDefinition() {
        throw new UnsupportedOperationException("bench pattern has no ItemStack definition");
    }
}

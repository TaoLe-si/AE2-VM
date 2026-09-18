package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * (v9, 1.17.1) Bench-side access to a pattern input's variants as shim
 * {@link GenericStack}s.
 * <p>
 * AE2 v9's {@code IPatternDetails.IInput} speaks {@code IAEStack}, which string bench
 * keys cannot produce. Bench input implementations keep their key-based data under
 * {@link #benchPossibleInputs()} / {@link #benchContainerItem(AEKey)} and inherit
 * this interface's defaults for the v9 surface. Extending {@code IInput} here lets
 * those defaults legally override the inherited abstract methods.
 */
public interface BenchInputAccess extends IPatternDetails.IInput {

    /** The input's possible variants as shim GenericStacks (first = primary). */
    GenericStack[] benchPossibleInputs();

    /**
     * (v1.10.x CATALYST) A returned/catalyst input is handed back unchanged: the
     * remaining key is the input itself; null otherwise.
     */
    AEKey benchContainerItem(AEKey template);

    /** v9 surface: no IAEStack representation exists for bench keys. */
    @Override
    default appeng.api.storage.data.IAEStack[] getPossibleInputs() {
        throw new UnsupportedOperationException("bench input cannot bridge into the v9 IAEStack world");
    }

    /** v9 surface: bench keys never reach isValid on the AE2 runtime path. */
    @Override
    default boolean isValid(appeng.api.storage.data.IAEStack input, net.minecraft.world.World level) {
        return false;
    }

    /** v9 surface: no IAEStack representation exists for bench keys. */
    @Override
    default appeng.api.storage.data.IAEStack getContainerItem(appeng.api.storage.data.IAEStack template) {
        return null;
    }
}

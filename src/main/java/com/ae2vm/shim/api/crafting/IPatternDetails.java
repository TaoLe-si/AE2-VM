package com.ae2vm.shim.api.crafting;

import appeng.api.storage.data.IAEStack;
import net.minecraft.item.ItemStack;

/**
 * AE2 v8 (1.16.5) shim of the v9 {@code com.ae2vm.shim.api.crafting.IPatternDetails} API.
 * <p>
 * AE2 v9 introduced the modern {@code IPatternDetails} / {@code CraftingService} /
 * {@code CraftingSimulationState} engine; v8 (1.16.5) only has the legacy
 * {@code appeng.api.networking.crafting.ICraftingPatternDetails} + {@code CraftingJob}
 * tree engine. The VM core (CraftingVM / PatternCompiler / AE2VMCrafting) is written
 * against the v9 surface, so we shadow that surface here and bridge it to v8 with
 * {@code com.ae2vm.addon.v8.V8PatternDetails}.
 * <p>
 * Only the members the VM actually uses are declared (minimal surface).
 */
public interface IPatternDetails {

    /** A copy of the encoded pattern item (identity carrier for caches). */
    ItemStack copyDefinition();

    /** Pattern inputs, in slot order. */
    IInput[] getInputs();

    /** Convenience: first output. */
    default IAEStack getPrimaryOutput() {
        return getOutputs()[0];
    }

    /** Pattern outputs (1 entry for crafting patterns, up to 3 for processing). */
    IAEStack[] getOutputs();

    interface IInput {
        /** Every stack that may satisfy this input slot (fuzzy capable). */
        IAEStack[] getPossibleInputs();

        /** How many of the input are consumed per craft. */
        long getMultiplier();

        /** Whether the given stack may be used for this input. */
        boolean isValid(IAEStack input, net.minecraft.world.World level);

        /** Container item left over after crafting (null when none). */
        IAEStack getContainerItem(IAEStack template);
    }
}

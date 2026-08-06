package com.ae2vm.addon.mixin;

import appeng.crafting.inv.CraftingSimulationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor to read CraftingSimulationState.bytes (package-private field).
 * Allows our VM to use the EXACT same byte calculation as original AE2.
 */
@Mixin(value = CraftingSimulationState.class, remap = false)
public interface CraftingSimulationStateAccessor {
    @Accessor
    double getBytes();
}

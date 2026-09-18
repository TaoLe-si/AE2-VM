package com.ae2vm.addon.mixin;

import com.ae2vm.shim.crafting.inv.CraftingSimulationState;

/**
 * Read-side accessor for {@link CraftingSimulationState#getBytes()}.
 * <p>
 * On v9/v10+ (1.17.1+) this is a real Mixin {@code @Accessor} because AE2's
 * {@code CraftingSimulationState#bytes} field is package-private. On AE2 v8
 * (1.16.5) the class in {@code appeng.crafting.inv} is our own v9-surface shim,
 * which already exposes {@code getBytes()}, so the interface is kept with the
 * identical signature and the shim simply implements it — the VM core and the
 * bench tests compile unchanged against either version.
 */
public interface CraftingSimulationStateAccessor {
    double getBytes();
}

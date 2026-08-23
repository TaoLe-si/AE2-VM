package com.ae2vm.addon.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

import java.util.Collection;

/**
 * (v1.12.x GTL) Duck-typed access to the ORIGINAL AE2 CraftingService.getCraftingFor
 * logic. CraftingServiceMixin implements this; at runtime the woven CraftingService
 * instance also implements it, so callers can request the UNMODIFIED vanilla pattern
 * lookup (craftingProviders.getCraftingFor) instead of the possibly-wrapped
 * CraftingService.getCraftingFor (other mods' mixins, autopattern wrappers, etc).
 * Must live OUTSIDE the mixin package — mixin-package classes cannot be referenced
 * directly (IllegalClassLoadError).
 */
public interface IVanillaCraftingAccess {
    /**
     * @return the ORIGINAL AE2 pattern candidates for {@code what} — exactly what the
     *         upstream CraftingService.getCraftingFor returned before any mixin.
     */
    Collection<IPatternDetails> vmGetCraftingForVanilla(AEKey what);
}

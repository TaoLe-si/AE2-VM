package com.ae2vm.addon.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="appeng.crafting.execution.ExecutingCraftingJob$TaskProgress",remap=false)
public interface GrowthTaskAccessor {
 @Accessor("value") long ae2vm$getValue();
}

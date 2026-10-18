package com.ae2vm.addon.mixin;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.execution.ElapsedTimeTracker;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.GenericStack;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value=ExecutingCraftingJob.class,remap=false)
public interface GrowthJobAccessor {
 @Accessor("tasks") Map<IPatternDetails,?> ae2vm$getTasks();
 @Mutable @Accessor("tasks") void ae2vm$setTasks(Map<IPatternDetails,?> tasks);
 @Accessor("waitingFor") ListCraftingInventory ae2vm$getWaitingFor();
 @Accessor("timeTracker") ElapsedTimeTracker ae2vm$getTimeTracker();
 @Accessor("finalOutput") GenericStack ae2vm$getFinalOutput();
}

package com.ae2vm.addon.mixin;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.execution.ExecutingCraftingJob;
import appeng.crafting.inv.ListCraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ae2vm.addon.vm.TwoNodeGrowthPlanner;
import com.ae2vm.addon.vm.FeedbackReservation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Keep feedback inputs inside the CPU instead of delivering them as final output.
 * Uses the REAL remaining task counts, waiting-for gate and CPU inventory; pending
 * operations already dispatched have consumed their inputs and need no reservation.
 * Only activated on jobs whose actual pattern set contains the supported growth pair.
 */
@Mixin(value=CraftingCpuLogic.class,remap=false)
public abstract class GrowthCpuMixin {
 @Shadow private ExecutingCraftingJob job;
 @Shadow @Final private ListCraftingInventory inventory;
 @Shadow @Final private CraftingCPUCluster cluster;
 @Unique private boolean ae2vm$growthFeedback;
 @Unique private long ae2vm$restoreTargetSeed;
 @Inject(method="trySubmitJob",at=@At("RETURN"))
 private void ae2vm$mark(IGrid grid,ICraftingPlan plan,IActionSource src,ICraftingRequester requester,
        CallbackInfoReturnable<ICraftingSubmitResult> cir) {
     if(job!=null && cir.getReturnValue().successful())
     {
         ae2vm$growthFeedback=TwoNodeGrowthPlanner.containsRing(plan.patternTimes().keySet());
         ae2vm$restoreTargetSeed=ae2vm$growthFeedback && com.ae2vm.addon.vm.NetProduction.sufficient(plan)
                 ?plan.usedItems().get(plan.finalOutput().what()):0;
         ae2vm$orderFeedback();
     }
 }
 @Unique private void ae2vm$orderFeedback() {
     if(ae2vm$growthFeedback && job!=null) {
         GrowthJobAccessor a=(GrowthJobAccessor)(Object)job;
         a.ae2vm$setTasks(TwoNodeGrowthPlanner.prioritizeFeedback(a.ae2vm$getTasks()));
     }
 }
 @WrapMethod(method="insert")
 private long ae2vm$reserveFeedback(AEKey what,long amount,Actionable mode,Operation<Long> original) {
     if(!ae2vm$growthFeedback || job==null || what==null || amount<=0)
         return original.call(what,amount,mode);
     GrowthJobAccessor access=(GrowthJobAccessor)(Object)job;
     if(!what.matches(access.ae2vm$getFinalOutput()))return original.call(what,amount,mode);
     long pending=0;
     try {
         for(var task:access.ae2vm$getTasks().entrySet()) {
             long times=((GrowthTaskAccessor)task.getValue()).ae2vm$getValue();
             if(times<=0)continue;
             for(var in:task.getKey().getInputs()) {
                 var choices=in.getPossibleInputs();
                 if(choices==null || choices.length==0 || choices[0]==null || !what.equals(choices[0].what()))continue;
                 if(what.equals(in.getRemainingKey(what)))continue;
                 pending=Math.addExact(pending,Math.multiplyExact(times,
                         Math.multiplyExact(in.getMultiplier(),choices[0].amount())));
             }
         }
     }catch(ArithmeticException ex){pending=Long.MAX_VALUE;}
     var waiting=access.ae2vm$getWaitingFor();
     long reserve=FeedbackReservation.reserve(amount,waiting.extract(what,amount,Actionable.SIMULATE),
             pending,inventory.extract(what,Long.MAX_VALUE,Actionable.SIMULATE));
     // Seed replacement is not order delivery. Keep it as CPU surplus, which
     // native storeItems returns to ME after completion. This guarantees a NET
     // increase even for one-item output packets (e.g. requesting dust itself).
     long expected=waiting.extract(what,amount,Actionable.SIMULATE);
     long restore=Math.min(ae2vm$restoreTargetSeed,Math.min(amount-reserve,Math.max(0,expected-reserve)));
     long retained=reserve+restore;
     if(retained==0)return original.call(what,amount,mode);
     if(mode==Actionable.MODULATE) {
         waiting.extract(what,retained,Actionable.MODULATE);
         inventory.insert(what,retained,Actionable.MODULATE);
         ae2vm$restoreTargetSeed-=restore;
         ((GrowthTimeAccessor)(Object)access.ae2vm$getTimeTracker()).ae2vm$decrementItems(retained,what.getType());
         cluster.markDirty();
     }
     return retained+(amount>retained?original.call(what,amount-retained,mode):0L);
 }
 @Inject(method="writeToNBT",at=@At("TAIL"))
 private void ae2vm$save(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
     tag.putBoolean("ae2vmGrowthFeedback",ae2vm$growthFeedback && job!=null);
     tag.putLong("ae2vmRestoreTargetSeed",ae2vm$restoreTargetSeed);
 }
 @Inject(method="readFromNBT",at=@At("TAIL"))
 private void ae2vm$load(CompoundTag tag,HolderLookup.Provider registries,CallbackInfo ci) {
     ae2vm$growthFeedback=job!=null && tag.getBoolean("ae2vmGrowthFeedback");
     ae2vm$restoreTargetSeed=ae2vm$growthFeedback?Math.max(0,tag.getLong("ae2vmRestoreTargetSeed")):0;
     ae2vm$orderFeedback();
 }
}

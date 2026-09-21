package com.ae2vm.addon.mixin;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.World;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.BaseActionSource;
import com.ae2vm.shim.api.networking.storage.IStorageService;
import com.ae2vm.shim.api.storage.StorageChannels;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.MECraftingInventory;
import appeng.me.cache.CraftingGridCache;

import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
import com.ae2vm.addon.config.AE2VMConfig;
import com.ae2vm.addon.v8.V8PatternDetails;
import com.ae2vm.addon.v8.VMCraftingJob;
import com.ae2vm.addon.v8.V8StorageService;
import com.ae2vm.addon.vm.VmMixinState;

/**
 * AE2 v8 (1.16.5) entry point of the VM accelerator.
 * <p>
 * v8 has no {@code CraftingService.beginCraftingCalculation}; the equivalent hook is
 * {@code CraftingGridCache.beginCraftingJob(World, IGrid, BaseActionSource, IAEItemStack,
 * ICraftingCallback)}. We intercept it, run the VM calculation, and hand back a
 * {@link VMCraftingJob} carrying the resulting pattern × craft counts.
 * {@code CraftingCPUClusterMixin} then executes that plan.
 */
@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class CraftingGridCacheMixin {

    /**
     * Set while a failed VM request is retried through the original (native) crafting path.
     * <p>
     * ⚠️ 状态放在 {@link com.ae2vm.addon.vm.VmMixinState}（普通类）而不是这里：
     * mixin 类里的匿名内部类会被 Mixin 合并进目标类并改名成
     * {@code CraftingGridCache$Anonymous$<hash>}，而 AE2 jar 是签名的 ——
     * 往 {@code appeng.me.cache} 包注入新类会触发
     * {@code SecurityException: signer information does not match}，
     * 表现为 AE2 自己的 FMLCommonSetupEvent 派发失败（ClassNotFoundException）。
     */

    @Inject(method = "beginCraftingJob", at = @At("HEAD"), cancellable = true)
    private void vmBeginCraftingJob(World world, IGrid grid, BaseActionSource actionSrc, IAEItemStack slotItem,
            ICraftingCallback cb, CallbackInfoReturnable<Future<ICraftingJob>> cir) {
        // 配置开关：proxy.enabled=false 时完全禁用 VM 代理，交给原生 AE2 递归计算
        if (!AE2VMConfig.isProxyEnabled()) {
            return;
        }

        // Native fallback in progress (VM failed) → let the original method run untouched.
        if (VmMixinState.isVmFallback()) {
            return;
        }

        if (grid == null || slotItem == null || slotItem.getStackSize() <= 0) {
            return;
        }

        com.ae2vm.shim.api.stacks.AEItemKey what = com.ae2vm.shim.api.stacks.AEItemKey.wrap(slotItem);
        if (what == null) {
            return;
        }
        long amount = slotItem.getStackSize();

        long reqId = VmMixinState.nextRequestId();
        long startTime = System.nanoTime();

        try {
            CompletableFuture<ICraftingPlan> vmFuture =
                    AE2VMCrafting.calculate(grid, null, what, amount, null);

            CompletableFuture<ICraftingJob> jobFuture = vmFuture.thenApply(plan -> {
                long okUs = (System.nanoTime() - startTime) / 1000;
                VMCraftingJob v8 = toV8Job(plan);
                if (AE2VMConfig.isDebugLogging()) {
                    AE2VMAddon.LOGGER.info("[AE2-VM] VM OK #" + reqId + ": " + okUs + " us"
                            + " output=" + v8.getOutput()
                            + " bytes=" + v8.getByteTotal()
                            + " simulation=" + v8.isSimulation()
                            + " patterns=" + v8.getPatternTimes().size()
                            + " used=" + v8.getUsedItems().size()
                            + " emitted=" + v8.getEmittedItems().size()
                            + " missing=" + v8.getMissingItems().size());
                }
                return (ICraftingJob) v8;
            }).handle((job, ex) -> {
                if (ex == null) {
                    return job;
                }
                if (AE2VMConfig.isDebugLogging()) {
                    AE2VMAddon.LOGGER.warn("[AE2-VM] VM FAILED #" + reqId + " -> "
                            + (vmShouldFallback(ex) ? "native fallback" : "cancelled (no fallback)"), ex);
                }
                // A cancelled request must NOT trigger a blocking native re-calculation.
                if (!vmShouldFallback(ex)) {
                    throw new java.util.concurrent.CancellationException("AE2-VM request cancelled (no native fallback)");
                }
                // VM could not handle the request → fall back to the ORIGINAL crafting path.
                VmMixinState.setVmFallback(true);
                try {
                    Future<ICraftingJob> nativeFuture =
                            ((CraftingGridCache) (Object) this).beginCraftingJob(world, grid, actionSrc, slotItem, cb);
                    try {
                        return nativeFuture.get();
                    } catch (Exception e) {
                        throw new RuntimeException("Native crafting fallback failed", e);
                    }
                } finally {
                    VmMixinState.clearVmFallback();
                }
            });

            cir.cancel();
            cir.setReturnValue(jobFuture);
        } catch (Exception e) {
            // fall through to the native calculation
        }
    }

    /** v9-shaped VM plan → v8 crafting job. */
    @Unique
    private static VMCraftingJob toV8Job(ICraftingPlan plan) {
        IAEItemStack output = plan.finalOutput() instanceof IAEItemStack
                ? (IAEItemStack) plan.finalOutput()
                : null;

        Map<ICraftingPatternDetails, Long> times = new LinkedHashMap<>();
        for (Map.Entry<IPatternDetails, Long> e : plan.patternTimes().entrySet()) {
            ICraftingPatternDetails v8 = unwrap(e.getKey());
            if (v8 != null && e.getValue() != null && e.getValue() > 0) {
                Long prev = times.get(v8);
                times.put(v8, prev == null ? e.getValue() : prev + e.getValue());
            }
        }

        return new VMCraftingJob(output, plan.bytes(), plan.simulation(),
                asItemList(plan.usedItems()), asItemList(plan.emittedItems()),
                asItemList(plan.missingItems()), times);
    }

    @Unique
    private static ICraftingPatternDetails unwrap(IPatternDetails details) {
        if (details instanceof V8PatternDetails) {
            return ((V8PatternDetails) details).getDelegate();
        }
        return null;
    }

    @Unique
    private static IItemList<IAEItemStack> asItemList(com.ae2vm.shim.api.storage.data.MixedStackList list) {
        IItemList<IAEItemStack> out = appeng.api.AEApi.instance().storage().createItemList();
        if (list == null) {
            return out;
        }
        for (appeng.api.storage.data.IAEStack st : list) {
            if (st instanceof IAEItemStack) {
                out.add((IAEItemStack) st.copy());
            }
        }
        return out;
    }

    /** Only REAL VM failures may fall back to the ORIGINAL AE2 path; cancellations are not failures. */
    @Unique
    private static boolean vmShouldFallback(Throwable ex) {
        Throwable t = ex;
        for (int i = 0; i < 4 && t instanceof java.util.concurrent.CompletionException; i++) {
            t = t.getCause();
        }
        if (t == null) {
            return false;
        }
        return !(t instanceof java.util.concurrent.CancellationException);
    }
}

package com.ae2vm.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;
import java.util.concurrent.Future;

/**
 * Mixin into CraftingService to replace recursive calculation with VM execution.
 * <p>
 * AE2 v9 (1.17.1): {@code beginCraftingCalculation(Level, ICraftingSimulationRequester,
 * IAEStack)} — the amount is carried INSIDE the IAEStack (no separate long/strategy
 * parameters like v10+). The VM request amount is read back from the stack size.
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin {
    @Shadow
    @Final
    private IGrid grid;

    @Shadow
    public abstract com.google.common.collect.ImmutableCollection<IPatternDetails> getCraftingFor(IAEStack what);

    private static long requestCounter = 0;

    /** Set while a failed VM request is retried through the original (native) crafting path. */
    private static final ThreadLocal<Boolean> VM_FALLBACK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * (v1.11.x PATTERN-REFRESH, v9) v9's CraftingService has no per-node
     * refreshNodeCraftingProvider; the network-wide private updatePatterns() is the
     * equivalent hook (any provider add/remove on the grid routes through it). Bump the
     * global pattern version so the CraftingVM drops its stale JIT bundleCache on the
     * next execute(), and kick the debounced background re-plan of hot outputs.
     */
    @Inject(method = "updatePatterns", at = @At("TAIL"))
    private void vmUpdatePatterns(CallbackInfo ci) {
        com.ae2vm.addon.compiler.PatternCompiler.bumpPatternVersion();
        com.ae2vm.addon.api.AE2VMCrafting.onPatternsChanged(this.grid);
    }

    /** True if the requester belongs to a third-party mod that has NOT opted in to AE2 VM. */
    private boolean isUnregisteredThirdPartyRequester(ICraftingSimulationRequester simRequester) {
        if (simRequester == null) return false;
        return AE2VMCraftingRegistry.isUnregisteredThirdParty(simRequester.getClass().getName());
    }

    /**
     * 顶层 mixin：希望先于 ECO（order=500）等第三方 mixin 执行。
     * 注意：Forge 37（1.17.1）运行时捆绑的 Mixin 0.8.4 同样不支持 @Inject 的 order 属性。
     * 若需确保本注入先于第三方 mixin，应通过 mixin 配置的 priority 来控制（见 ae2vm.mixins.json）。
     */
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true)
    private void vmBeginCraftingCalculation(
            net.minecraft.world.level.Level level,
            ICraftingSimulationRequester simRequester,
            IAEStack slotItem,
            CallbackInfoReturnable<Future<ICraftingPlan>> cir) {

        // 配置开关：proxy.enabled=false 时完全禁用 VM 代理，交给原生 AE2 递归计算
        if (!com.ae2vm.addon.config.AE2VMConfig.isProxyEnabled()) {
            return;
        }

        // Native fallback in progress (VM failed) → let the original method run untouched.
        if (VM_FALLBACK.get()) {
            return;
        }

        long reqId = ++requestCounter;
        long startTime = System.nanoTime();

        // If already cancelled, don't override
        if (cir.isCancelled()) {
            return;
        }

        try {
            // Third-party requester that has NOT registered → do not take over.
            if (isUnregisteredThirdPartyRequester(simRequester)) {
                VM_FALLBACK.set(Boolean.TRUE);
                try {
                    cir.setReturnValue(((CraftingService) (Object) this).beginCraftingCalculation(
                        level, simRequester, slotItem));
                    cir.cancel();
                } finally {
                    VM_FALLBACK.remove();
                }
                return;
            }

            // v9: amount lives inside the IAEStack; extract key + amount into the shim.
            if (!(slotItem instanceof IAEItemStack itemStack)) {
                return; // fluid/other channel — VM is item-only, fall back to native
            }
            var what = appeng.api.stacks.AEItemKey.wrap(itemStack);
            long amount = slotItem.getStackSize();
            if (what == null || amount <= 0) {
                return;
            }

            Collection<IPatternDetails> patterns = getCraftingFor(slotItem);
            if (patterns.isEmpty()) return;

            // Delegate core computation to the public API.
            // Third-party mods can also call AE2VMCrafting.calculate directly.
            // (v9: no CalculationStrategy — pass null, the API signature keeps the slot for compat.)
            var vmFuture = com.ae2vm.addon.api.AE2VMCrafting.calculate(grid, simRequester, what, amount, null)
                .thenApply(result -> {
                    long okUs = (System.nanoTime() - startTime) / 1000;
                    if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
                    AE2VMAddon.LOGGER.info("[AE2-VM] VM OK #{}: {} us ({} ms)", reqId, okUs, String.format("%.2f", okUs / 1000.0D));
                    }
                    return (ICraftingPlan) result;
                })
                .handle((plan, ex) -> {
                    if (ex == null) return plan;
                    // (v1.12.x GTL) A cancelled request must NOT trigger a blocking native
                    // re-calculation. Propagate the cancellation as-is.
                    if (!vmShouldFallback(ex)) {
                        throw new java.util.concurrent.CancellationException("AE2-VM request cancelled (no native fallback)");
                    }
                    // VM could not handle the request (e.g. a third-party pattern it
                    // cannot compile). Fall back to the ORIGINAL crafting path so the
                    // job still starts instead of failing with an error.
                    VM_FALLBACK.set(Boolean.TRUE);
                    try {
                        var nativeFuture = ((CraftingService) (Object) this).beginCraftingCalculation(
                                level, simRequester, slotItem);
                        try {
                            return nativeFuture.get();
                        } catch (Exception e) {
                            throw new RuntimeException("Native crafting fallback failed", e);
                        }
                    } finally {
                        VM_FALLBACK.remove();
                    }
                });

            // Return future immediately — don't block server thread
            cir.cancel();
            cir.setReturnValue(vmFuture);

        } catch (Exception e) {
            // AE2VMAddon.LOGGER.warn("[AE2-VM] VM failed, falling back: {}", e.toString());
        }
    }

    /**
     * (v1.12.x GTL) Only REAL VM failures may fall back to the ORIGINAL AE2/GTL
     * crafting path. Cancellations are NOT failures.
     */
    @Unique
    private static boolean vmShouldFallback(Throwable ex) {
        Throwable t = ex;
        for (int i = 0; i < 4 && t instanceof java.util.concurrent.CompletionException; i++) {
            t = t.getCause();
        }
        if (t == null) return false;
        return !(t instanceof java.util.concurrent.CancellationException);
    }
}

package com.ae2vm.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
import com.ae2vm.addon.compiler.PatternCompiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.concurrent.Future;

/**
 * Mixin into CraftingService to replace recursive calculation with VM execution.
 */
@Mixin(value = CraftingService.class, remap = false)
public abstract class CraftingServiceMixin {
    @Shadow
    @Final
    private IGrid grid;
    
    @Shadow
    public abstract Collection<IPatternDetails> getCraftingFor(AEKey what);
    
    private static long requestCounter = 0;
    
    /** Set while a failed VM request is retried through the original (native) crafting path. */
    private static final ThreadLocal<Boolean> VM_FALLBACK = ThreadLocal.withInitial(() -> Boolean.FALSE);
    
    /** True if the requester belongs to a third-party mod that has NOT opted in to AE2 VM. */
    private boolean isUnregisteredThirdPartyRequester(ICraftingSimulationRequester simRequester) {
        if (simRequester == null) return false;
        return AE2VMCraftingRegistry.isUnregisteredThirdParty(simRequester.getClass().getName());
    }
    
    /**
     * 顶层 mixin：order=100，先于 ECO（order=500）等第三方 mixin 执行。
     * 这样当我们（VM）能处理时优先接管计算；对未注册的第三方 requester
     * 走原生路径（此时 ECO 的 order=500 注入会在原生调用链中被触发，不受影响）。
     */
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true, order = 100)
    private void vmBeginCraftingCalculation(
            net.minecraft.world.level.Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
            CallbackInfoReturnable<Future<ICraftingPlan>> cir) {
        
        // Native fallback in progress (VM failed) → let the original method run untouched.
        if (VM_FALLBACK.get()) {
            return;
        }
        
        long reqId = ++requestCounter;
        long startTime = System.nanoTime();
        
        AE2VMAddon.LOGGER.info("[AE2-VM] ====== VM Request #{} ======", reqId);
        AE2VMAddon.LOGGER.info("[AE2-VM] Target: {} x {}, strategy={}", amount, what, strategy);
        
        // If already cancelled, don't override
        if (cir.isCancelled()) {
            AE2VMAddon.LOGGER.info("[AE2-VM] Already cancelled, VM skipping");
            return;
        }
        
        try {
            // Third-party requester that has NOT registered → do not take over.
            // Directly set the original return value: call the original crafting
            // path (VM_FALLBACK prevents recursion) and return its future.
            if (isUnregisteredThirdPartyRequester(simRequester)) {
                AE2VMAddon.LOGGER.info("[AE2-VM] Third-party requester {} (not registered) → native crafting",
                    simRequester.getClass().getName());
                VM_FALLBACK.set(Boolean.TRUE);
                try {
                    cir.setReturnValue(((CraftingService) (Object) this).beginCraftingCalculation(
                        level, simRequester, what, amount, strategy));
                    cir.cancel();
                } finally {
                    VM_FALLBACK.remove();
                }
                return;
            }
            
            Collection<IPatternDetails> patterns = getCraftingFor(what);
            if (patterns.isEmpty()) return;
            
            long setupTime = (System.nanoTime() - startTime) / 1_000_000;
            AE2VMAddon.LOGGER.info("[AE2-VM] VM try: {}x{} setup={}ms", amount, what, setupTime);
            
            // Delegate core computation to the public API.
            // Third-party mods can also call AE2VMCrafting.calculate directly.
            var vmFuture = com.ae2vm.addon.api.AE2VMCrafting.calculate(grid, simRequester, what, amount, strategy)
                .thenApply(result -> {
                    if (result == null) {
                        AE2VMAddon.LOGGER.warn("[AE2-VM] VM returned null plan, falling back to native");
                        return null;
                    }
                    AE2VMAddon.LOGGER.info("[AE2-VM] plan: output={}x{} sim={} bytes={} used={} missing={} patterns={}",
                        result.finalOutput().amount(), result.finalOutput().what(), result.simulation(), result.bytes(),
                        result.usedItems().size(), result.missingItems().size(), result.patternTimes().size());
                    if (!result.usedItems().isEmpty()) {
                        var used = new StringBuilder("[AE2-VM]   USED:");
                        for (var k : result.usedItems().keySet()) used.append(" ").append(result.usedItems().get(k)).append("x").append(k);
                        AE2VMAddon.LOGGER.info(used.toString());
                    }
                    if (!result.missingItems().isEmpty()) {
                        var miss = new StringBuilder("[AE2-VM]   MISSING:");
                        for (var k : result.missingItems().keySet()) miss.append(" ").append(result.missingItems().get(k)).append("x").append(k);
                        AE2VMAddon.LOGGER.info(miss.toString());
                    }
                    for (var entry : result.patternTimes().entrySet()) {
                        AE2VMAddon.LOGGER.info("[AE2-VM]   Pattern: {} x {} (output {} x {})",
                            entry.getValue(),
                            entry.getKey().getPrimaryOutput().what(),
                            entry.getKey().getPrimaryOutput().amount(),
                            entry.getKey().getPrimaryOutput().what());
                    }
                    AE2VMAddon.LOGGER.info("[AE2-VM] VM OK #{}: {}ms", reqId, (System.nanoTime() - startTime) / 1_000_000);
                    return result;
                })
                .handle((plan, ex) -> {
                    if (ex == null && plan != null) return plan;
                    // VM timed out, failed, or returned null plan —
                    // fall back to AE2 native crafting.
                    AE2VMAddon.LOGGER.warn("[AE2-VM] VM failed ({}), native crafting fallback", ex != null ? ex.toString() : "null plan");
                    VM_FALLBACK.set(Boolean.TRUE);
                    try {
                        return ((CraftingService) (Object) this).beginCraftingCalculation(
                                level, simRequester, what, amount, strategy).get(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e2) {
                        AE2VMAddon.LOGGER.error("[AE2-VM] Native fallback also failed", e2);
                        return null;
                    } finally {
                        VM_FALLBACK.remove();
                    }
                });
            
            // Cancel native path, replace with our future
            cir.cancel();
            cir.setReturnValue(vmFuture);
            
        } catch (Exception e) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] VM failed, falling back: {}", e.toString());
        }
    }
    
    /** Compile a pattern into the given network's cache, ignoring (and logging) failures so a bad pattern never breaks the grid. */
    private static void safeCompile(IGrid network, IPatternDetails p) {
        try {
            PatternCompiler.compileIfAbsent(network, p);
        } catch (Exception e) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Skipping uncompilable pattern {}: {}", p, e.toString());
        }
    }
    
    /**
     * Mixin into refreshNodeCraftingProvider — called when ANY node-based
     * pattern provider's patterns are registered or refreshed on the grid.
     * This covers ALL ICraftingProvider implementations regardless of origin
     * (standard PatternProvider, ECO, ExtendedAE).
     * 
     * We compile all patterns from the provider into THIS network's cache at
     * registration time. This is a ONE-TIME cost per pattern. Subsequent lookups
     * hit the compiled cache (T4 fallback) in O(1).
     */
    @Inject(method = "refreshNodeCraftingProvider", at = @At("TAIL"), remap = false)
    private void onRefreshNodeCraftingProvider(IGridNode node, CallbackInfo ci) {
        if (node == null) return;
        var network = node.getGrid();
        var provider = node.getService(ICraftingProvider.class);
        if (network == null || provider == null) return;
        var patterns = provider.getAvailablePatterns();
        if (patterns == null || patterns.isEmpty()) return;
        if (patterns.size() > 4) patterns.parallelStream().forEach(p -> safeCompile(network, p));
        else for (var p : patterns) safeCompile(network, p);
    }
    
    @Inject(method = "addGlobalCraftingProvider", at = @At("TAIL"), remap = false)
    private void onAddGlobalCraftingProvider(ICraftingProvider provider, CallbackInfo ci) {
        if (provider == null) return;
        var patterns = provider.getAvailablePatterns();
        if (patterns == null || patterns.isEmpty()) return;
        if (patterns.size() > 4) patterns.parallelStream().forEach(p -> safeCompile(grid, p));
        else for (var p : patterns) safeCompile(grid, p);
    }
}





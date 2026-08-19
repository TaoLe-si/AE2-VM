package com.ae2vm.addon.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AE2VMCraftingRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    /**
     * (v1.11.x PATTERN-REFRESH) Every time ANY crafting provider's node is refreshed on
     * the network (e.g. pattern provider inventory changed → ICraftingProvider.requestUpdate
     * → refreshNodeCraftingProvider), bump the global pattern version. This clears the
     * CraftingVM's stale JIT bundleCache on the next execute().
     *
     * This is a more RELIABLE hook than PatternProviderLogicMixin.onUpdatePatterns:
     * third-party mods (ExtendedAE, AdvancedAE, etc.) that add/override their own pattern
     * provider logic may NOT route through PatternProviderLogic.updatePatterns(), but they
     * ALL call ICraftingProvider.requestUpdate() → refreshNodeCraftingProvider() to
     * notify the network. Without this hook, those provider updates never bump the
     * pattern version → bundleCache stays stale → a newly-added intermediate pattern
     * is never re-resolved in the chain.
     */
    @Inject(method = "refreshNodeCraftingProvider", at = @At("HEAD"))
    private void vmRefreshNodeCraftingProvider(IGridNode node, CallbackInfo ci) {
        com.ae2vm.addon.compiler.PatternCompiler.bumpPatternVersion();
    }
    
    /** True if the requester belongs to a third-party mod that has NOT opted in to AE2 VM. */
    private boolean isUnregisteredThirdPartyRequester(ICraftingSimulationRequester simRequester) {
        if (simRequester == null) return false;
        return AE2VMCraftingRegistry.isUnregisteredThirdParty(simRequester.getClass().getName());
    }
    
    /**
     * 顶层 mixin：希望先于 ECO（order=500）等第三方 mixin 执行。
     * 注意：Forge 47（1.20.1）运行时捆绑 Mixin 0.8.5，其 @Inject 不支持 order 属性，
     * 因此这里不能写 order=100（编译期会报错，运行期也会被忽略）。
     * 若需确保本注入先于第三方 mixin，应通过 mixin 配置的 priority 来控制（见 ae2vm.mixins.json）。
     */
    @Inject(method = "beginCraftingCalculation", at = @At("HEAD"), cancellable = true)
    private void vmBeginCraftingCalculation(
            net.minecraft.world.level.Level level,
            ICraftingSimulationRequester simRequester,
            AEKey what,
            long amount,
            CalculationStrategy strategy,
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
        
        // Request/Target logging disabled (v1.8.20) — keep only total calc time.
        // AE2VMAddon.LOGGER.info("[AE2-VM] ====== VM Request #{} ======", reqId);
        // AE2VMAddon.LOGGER.info("[AE2-VM] Target: {} x {}, strategy={}", amount, what, strategy);
        
        // If already cancelled, don't override
        if (cir.isCancelled()) {
            // AE2VMAddon.LOGGER.info("[AE2-VM] Already cancelled, VM skipping");
            return;
        }
        
        try {
            // Third-party requester that has NOT registered → do not take over.
            // Directly set the original return value: call the original crafting
            // path (VM_FALLBACK prevents recursion) and return its future.
            if (isUnregisteredThirdPartyRequester(simRequester)) {
                // AE2VMAddon.LOGGER.info("[AE2-VM] Third-party requester {} (not registered) → native crafting",
                //     simRequester.getClass().getName());
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
            
            // long setupTime = (System.nanoTime() - startTime) / 1_000_000;
            // AE2VMAddon.LOGGER.info("[AE2-VM] VM try: {}x{} setup={}ms", amount, what, setupTime);
            
            // Delegate core computation to the public API.
            // Third-party mods can also call AE2VMCrafting.calculate directly.
            var vmFuture = com.ae2vm.addon.api.AE2VMCrafting.calculate(grid, simRequester, what, amount, strategy)
                .thenApply(result -> {
                    // Plan/Pattern detail logging disabled (v1.8.20) — keep only total time.
                    // AE2VMAddon.LOGGER.info("[AE2-VM] plan: output={}x{} sim={} bytes={} used={} missing={} patterns={}",
                    //     result.finalOutput().amount(), result.finalOutput().what(), result.simulation(), result.bytes(),
                    //     result.usedItems().size(), result.missingItems().size(), result.patternTimes().size());
                    // if (!result.usedItems().isEmpty()) {
                    //     var used = new StringBuilder("[AE2-VM]   USED:");
                    //     for (var k : result.usedItems().keySet()) used.append(" ").append(result.usedItems().get(k)).append("x").append(k);
                    //     AE2VMAddon.LOGGER.info(used.toString());
                    // }
                    // if (!result.missingItems().isEmpty()) {
                    //     var miss = new StringBuilder("[AE2-VM]   MISSING:");
                    //     for (var k : result.missingItems().keySet()) miss.append(" ").append(result.missingItems().get(k)).append("x").append(k);
                    //     AE2VMAddon.LOGGER.info(miss.toString());
                    // }
                    // for (var entry : result.patternTimes().entrySet()) {
                    //     AE2VMAddon.LOGGER.info("[AE2-VM]   CRAFT {} x {} (pattern={})",
                    //         entry.getValue() * entry.getKey().getPrimaryOutput().amount(),
                    //         entry.getKey().getPrimaryOutput().what(), entry.getKey());
                    // }
                    long okUs = (System.nanoTime() - startTime) / 1_000;
                    AE2VMAddon.LOGGER.info("[AE2-VM] VM OK #{}: {} us ({} ms)", reqId, okUs, String.format("%.2f", okUs / 1000.0D));
                    return result;
                })
                .handle((plan, ex) -> {
                    if (ex == null) return plan;
                    // VM could not handle the request (e.g. a third-party pattern it
                    // cannot compile). Fall back to the ORIGINAL crafting path so the
                    // job still starts instead of failing with an error.
                    // AE2VMAddon.LOGGER.warn("[AE2-VM] VM failed ({}), falling back to native crafting", ex.toString());
                    VM_FALLBACK.set(Boolean.TRUE);
                    try {
                        var nativeFuture = ((CraftingService) (Object) this).beginCraftingCalculation(
                                level, simRequester, what, amount, strategy);
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
}








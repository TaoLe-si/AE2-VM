package com.ae2vm.addon.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.ChildCraftingSimulationState;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.neoforged.fml.ModList;

import java.math.BigInteger;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * Public facade for the AE2 VM crafting engine.
 * <p>
 * This mod is <b>optional</b> for third-party crafting mods (ECO, ExtendedAE,
 * etc.). They should <b>not</b> declare a hard dependency on {@code ae2vm};
 * instead use {@link #isLoaded()} to detect the VM at runtime and call
 * {@link #calculate(IGrid, ICraftingSimulationRequester, AEKey, long,
 * CalculationStrategy)} from their own {@code beginCraftingCalculation}
 * implementation to compute a crafting plan using the VM, then submit it
 * through their own job pipeline. The VM handles pure AE2 pattern trees at
 * maximum speed; items with no AE2 pattern are reported as missing.
 */
public final class AE2VMCrafting {

    private AE2VMCrafting() {
    }

    /**
     * Whether the AE2 VM mod is loaded in the current game instance.
     * <p>
     * Use this as the runtime gate for optional integration so that your mod
     * keeps working unchanged when AE2 VM is not installed:
     *
     * <pre>{@code
     * if (AE2VMCrafting.isLoaded()) {
     *     AE2VMCrafting.calculate(grid, requester, what, amount, strategy)
     *         .thenAccept(plan -> submit(plan));
     * } else {
     *     // native AE2 (or your own) calculation path
     * }
     * }</pre>
     *
     * @return {@code true} if the {@code ae2vm} mod is present
     */
    public static boolean isLoaded() {
        return ModList.get().isLoaded("ae2vm");
    }

    /**
     * Compute a crafting plan for the given item using the AE2 VM engine.
     *
     * @param grid      the grid to compute against
     * @param requester the simulation requester (crafting link or terminal)
     * @param what      the item to craft
     * @param amount    how many are requested
     * @param strategy  calculation strategy
     * @return a future resolving to the crafting plan, or completing
     *         exceptionally if the VM could not compute (e.g. no pattern)
     */
    public static CompletableFuture<ICraftingPlan> calculate(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy) {
        return calculateAsync(grid, requester, what, amount, strategy);
    }

    /**
     * Synchronous variant of {@link #calculate(IGrid, ICraftingSimulationRequester, AEKey, long, CalculationStrategy)}.
     * Blocks until the plan is ready. Use the async variant on the server thread.
     */
    public static ICraftingPlan calculateSync(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy) throws Exception {
        return calculateAsync(grid, requester, what, amount, strategy).get();
    }

    private static CompletableFuture<ICraftingPlan> calculateAsync(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            CalculationStrategy strategy) {
        CraftingService service = (CraftingService) grid.getCraftingService();
        if (service == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No crafting service on grid"));
        }

        Collection<IPatternDetails> patterns = service.getCraftingFor(what);
        if (patterns.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("No pattern for " + what));
        }

        IPatternDetails topPattern = patterns.iterator().next();

        // Compile the top-level pattern to bytecode (per-network cache)
        PatternCompiler.compileIfAbsent(grid, topPattern);
        CraftingBytecode patternBytecode = PatternCompiler.getCompiled(grid, topPattern);
        if (patternBytecode == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pattern not compilable: " + what));
        }

        CraftingBytecode requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);

        // Per-request resolver cache
        Map<AEKey, IPatternDetails> resolverCache = new java.util.concurrent.ConcurrentHashMap<>();

        CraftingVM vm = new CraftingVM(grid, key -> resolve(grid, service, resolverCache, key));

        // Create simulation inventory.
        // IMPORTANT: always snapshot the LIVE network inventory (getAvailableStacks),
        // never the cached inventory. AE2's NetworkCraftingSimulationState falls back to
        // getCachedInventory() for non-player requesters (ECO pattern buses, interfaces,
        // requester lambdas, ...). That cached snapshot can be stale — the plan would then
        // claim more items than the CPU can actually extract at submit time, and AE2 refuses
        // the job with CraftErrorMissingIngredient ("无法从网络中取出某些材料").
        var storage = grid.getStorageService();
        var networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
        var craftingInventory = new ChildCraftingSimulationState(networkInv);
        craftingInventory.ignore(what);

        return CompletableFuture.supplyAsync(() -> {
            try {
                ICraftingPlan rawPlan = vm.execute(requestBytecode, craftingInventory);
                // Fix: ignore(what) hides the requested item from simulation.
                // Recursive sub-patterns needing the same item type trigger cycle
                // detection → false "missing". Check real network stock and correct.
                if (rawPlan.simulation() && !rawPlan.missingItems().isEmpty()) {
                    var realStock = storage.getInventory().getAvailableStacks();
                    long avail = realStock.get(what);
                    long missingCount = rawPlan.missingItems().get(what);
                    if (avail > 0 && missingCount > 0) {
                        long usable = Math.min(avail, missingCount);
                        AE2VMAddon.LOGGER.info("[AE2-VM] ignore-fix: {} x {} in real network, correcting plan",
                            usable, what);
                        KeyCounter fixedUsed = new KeyCounter();
                        for (var e : rawPlan.usedItems()) fixedUsed.add(e.getKey(), e.getLongValue());
                        fixedUsed.add(what, usable);
                        KeyCounter fixedMissing = new KeyCounter();
                        for (var e : rawPlan.missingItems()) {
                            if (!e.getKey().equals(what)) {
                                fixedMissing.add(e.getKey(), e.getLongValue());
                            } else if (e.getLongValue() > usable) {
                                fixedMissing.add(e.getKey(), e.getLongValue() - usable);
                            }
                        }
                        rawPlan = new CraftingPlan(rawPlan.finalOutput(), rawPlan.bytes(),
                            !fixedMissing.isEmpty(), false,
                            fixedUsed, rawPlan.emittedItems(), fixedMissing,
                            new HashMap<>(rawPlan.patternTimes()));
                    }
                }
                return rawPlan;
            } catch (Exception e) {
                AE2VMAddon.LOGGER.warn("[AE2-VM] Calculation failed for {}: {}", what, e.toString());
                AE2VMAddon.LOGGER.warn("[AE2-VM] Calculation stack:", e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * T1-T3 resolver: exact match → drop secondary → registry item.
     * Compiles resolved sub-patterns into the SAME network's cache as the task.
     */
    private static IPatternDetails resolve(Object network,
                                           CraftingService service,
                                           Map<AEKey, IPatternDetails> cache,
                                           AEKey key) {
        // ConcurrentHashMap forbids null keys — guard against a null constant-pool entry.
        if (key == null) return null;
        var cached = cache.get(key);
        if (cached != null) return cached;
        if (cached != null) return cached;

        // Try 1: exact match
        var subs = service.getCraftingFor(key);
        if (!subs.isEmpty()) {
            var sub = subs.iterator().next();
            PatternCompiler.compileIfAbsent(network, sub);
            cache.put(key, sub);
            return sub;
        }
        // Try 2: drop secondary — only if the resolved pattern has fuzzy enabled
        var clean = key.dropSecondary();
        if (!clean.equals(key)) {
            subs = service.getCraftingFor(clean);
            if (!subs.isEmpty()) {
                var sub = subs.iterator().next();
                if (isFuzzyPattern(sub)) {
                    PatternCompiler.compileIfAbsent(network, sub);
                    cache.put(key, sub);
                    return sub;
                }
            }
        }
        // Try 3: registry item — only if the resolved pattern has fuzzy enabled
        var id = key.getId();
        if (id != null) {
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            if (item != null) {
                var pureKey = appeng.api.stacks.AEItemKey.of(item);
                if (pureKey != null) {
                    subs = service.getCraftingFor(pureKey);
                    if (!subs.isEmpty()) {
                        var sub = subs.iterator().next();
                        if (isFuzzyPattern(sub)) {
                            PatternCompiler.compileIfAbsent(network, sub);
                            cache.put(key, sub);
                            return sub;
                        }
                    }
                }
            }
        }
        // Try 4: a pattern was compiled for THIS network but the service did not index it
        // (e.g. molecular-assembler patterns). It exists, so it is craftable — never missing.
        var compiled = PatternCompiler.findCompiledByOutput(network, key);
        if (compiled != null) {
            cache.put(key, compiled);
            return compiled;
        }
        // NOT FOUND. ConcurrentHashMap forbids null values, so we cannot cache a null
        // here — just return null; the caller records it as missing.
        return null;
    }

    /** A pattern supports fuzzy matching if any of its inputs has multiple possible variants. */
    private static boolean isFuzzyPattern(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null) return false;
        for (var input : inputs) {
            var possible = input.getPossibleInputs();
            if (possible != null && possible.length > 1) return true;
        }
        return false;
    }
}

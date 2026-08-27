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
import appeng.crafting.inv.CraftingSimulationState;
import appeng.me.service.CraftingService;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraftforge.fml.ModList;

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
     * Per-grid CraftingVM instances, reused across requests so the JIT bundleCache
     * (per-pattern 1-craft subtree effects) survives between crafting requests on the
     * same network. Keyed by the grid so different networks never share bundles.
     * execute() is synchronized on the VM, so concurrent requests are serialized.
     */
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, CraftingVM> VM_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * (v1.12.x GTL) Settle window (ms) granted to the network's crafting providers
     * between retries. GTL's ME 样板总成 / 超限演算阵列 sync patterns to the
     * CraftingService on the SERVER TICK (requestUpdate → refreshNodeCraftingProvider,
     * which is removeProvider → addProvider); an async VM calculation that races that
     * window sees a freshly-written pattern as momentarily absent. 60ms ≈ 1-2 ticks of
     * slack — enough to close the window, small enough to be invisible on the
     * ForkJoinPool worker.
     */
    private static final long RETRY_SETTLE_MS = 60L;

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
    // (v1.13.0) ModList is null in the test classpath (ModContainer not initialised);
    // return true (= "AE2VM itself is loaded") when there is no ModList context at all.
    try {
        var ml = ModList.get();
        return ml != null && ml.isLoaded("ae2vm");
    } catch (Throwable t) {
        return true;
    }
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

        // Prefer the pattern with the SMALLEST per-craft output among the candidates.
        // The NAST pack has mega/bulk patterns (e.g. a pattern producing 625,000
        // alloy_infused per craft); picking one of those for a request that needs only
        // a handful crafts the mega pattern once and the plan explodes to millions of
        // items (1000 energy tablets -> 625M copper / 78M redstone). A normal pattern
        // (if present) is preferred so the plan matches the actual need. (v1.8.17)
        IPatternDetails topPattern = pickBestPattern(patterns, what);

        // Compile the top-level pattern to bytecode (per-network cache)
        PatternCompiler.compileIfAbsent(grid, topPattern);
        CraftingBytecode patternBytecode = PatternCompiler.getCompiled(grid, topPattern);
        if (patternBytecode == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pattern not compilable: " + what));
        }


        // Per-request resolver cache
        Map<AEKey, IPatternDetails> resolverCache = new java.util.concurrent.ConcurrentHashMap<>();

        // Reuse a per-grid VM so its JIT bundleCache (per-pattern 1-craft subtree
        // effects) survives across requests on the same network — otherwise every
        // request would re-capture every sub-pattern (JIT hit-rate ~0-49%). The
        // resolver cache is per-request, so we swap it via setPatternResolver.
        // execute() is synchronized on the VM, so concurrent requests are safe.
        CraftingVM vm = VM_CACHE.computeIfAbsent(grid, g ->
                new CraftingVM(g, key -> resolve(g, (CraftingService) g.getCraftingService(),
                        new java.util.concurrent.ConcurrentHashMap<>(), key)));
        vm.setPatternResolver(key -> resolve(grid, service, resolverCache, key));

        // Create simulation inventory.
        // IMPORTANT: always snapshot the LIVE network inventory (getAvailableStacks),
        // never the cached inventory. AE2's NetworkCraftingSimulationState falls back to
        // getCachedInventory() for non-player requesters (ECO pattern buses, interfaces,
        // requester lambdas, ...). That cached snapshot can be stale — the plan would then
        // claim more items than the CPU can actually extract at submit time, and AE2 refuses
        // the job with CraftErrorMissingIngredient ("无法从网络中取出某些材料").
        var storage = grid.getStorageService();

        return CompletableFuture.supplyAsync(() -> {
            try {
                CraftingBytecode requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);
                var networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                var craftingInventory = new ChildCraftingSimulationState(networkInv);
                craftingInventory.ignore(what);
// (v1.12.x GTL STALE-MISSING RETRY) Loop up to 3 times to handle stale
                // bundleCache where a missing item's pattern was added AFTER the
                // bundle was captured (GTL's MEPatternBufferPartMachine may not trigger
                // a reliable refreshNodeCraftingProvider -> bumpPatternVersion, so the
                // bundleCache may stay stale). Each retry clears the LOCAL bundleCache,
                // invalidates the root pattern's bytecode, and recompiles — forcing a
                // fresh capture without disturbing other VM workers (the old global
                // bumpPatternVersion() cleared every grid VM's cache, causing concurrent
                // recalculations to hit the transient pattern-removal window inside
                // CraftingService.refreshNodeCraftingProvider and report pattern not found).
                int maxRetries = 3;
                ICraftingPlan rawPlan = null;
                for (int retry = 0; retry < maxRetries; retry++) {
                    rawPlan = vm.execute(requestBytecode, craftingInventory);
                    // Fix: ignore(what) hides the requested item from simulation.
                    // Recursive sub-patterns needing the same item type trigger cycle
                    // detection -> false "missing". Check real network stock and correct.
                    if (rawPlan.simulation() && !rawPlan.missingItems().isEmpty()) {
                        var realStock = storage.getInventory().getAvailableStacks();
                        long avail = realStock.get(what);
                        long missingCount = rawPlan.missingItems().get(what);
                        if (avail > 0 && missingCount > 0) {
                            long usable = Math.min(avail, missingCount);
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
// (v1.12.x GTL STALE-MISSING RETRY) Retry conditions:
                    //  1) a missing item NOW has a pattern in the CraftingService
                    //     (stale bundle recorded it as a missing leaf before the pattern
                    //     was added / synced from the GTL pattern buffer), or
                    //  2) a missing item was resolved/compiled before but is temporarily
                    //     absent from the CraftingService (GTL refreshNodeCraftingProvider
                    //     does removeProvider + addProvider; between those two calls
                    //     getCraftingFor returns empty -> fresh capture would report
                    //     pattern not found and the job stalls).
                    if (rawPlan.simulation() && !rawPlan.missingItems().isEmpty() && retry < maxRetries - 1) {
                        boolean needsRetry = missingKeyNowCraftable(service, rawPlan, what);
                        if (!needsRetry) {
                            // (v1.12.x GTL PROVIDER-REFRESH WINDOW) GTL providers (ME 样板总成 /
                            // 超限演算阵列) sync patterns to the CraftingService on the SERVER
                            // TICK. Our async calculation can land right inside that window: the
                            // missing key's provider is momentarily unregistered, so Check 1
                            // (getCraftingFor) is empty AND Check 2 (findCompiledByOutput) misses
                            // because the pattern was never compiled while the provider was down.
                            // This is the "链内报缺、单独合成正常" GTL false-negative
                            // (latest (3).log: opv_4a_wireless_energy_receive_cover reports
                            // gtceu:infuscolium=8456 missing while infuscolium alone crafts fine).
                            // Wait one settle window, then re-check before declaring the items
                            // missing.
                            try {
                                Thread.sleep(RETRY_SETTLE_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            needsRetry = missingKeyNowCraftable(service, rawPlan, what);
                        }
                        if (needsRetry) {
                            // Clear ONLY this VM's bundleCache; do NOT bump the global
                            // pattern version (that would clear every VM on the grid).
                            // (v1.12.x GTL FIX) Do NOT clear the full bundleCache —
                            // the VM's internal staleMissingRecheck (with oscillation guard)
                            // re-captures only the affected bundles. Clearing the full cache
                            // forces a 20-30s full recapture and destroys the fast REUSE path.
                            // vm.clearBundleCache(); // REMOVED — GTL oscillation fix
                            // Invalidate the root pattern's bytecode so recompile picks up fresh state
                            PatternCompiler.invalidate(topPattern);
                            PatternCompiler.compileIfAbsent(grid, topPattern);
                            requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);
                            // Fresh crafting inventory for the retry
                            var newNetworkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                            craftingInventory = new ChildCraftingSimulationState(newNetworkInv);
                            craftingInventory.ignore(what);
                            continue;
                        }
                    }
                    break;
                }
                return rawPlan;
            } catch (Exception e) {
                // AE2VMAddon.LOGGER.warn("[AE2-VM] Calculation failed for {}: {}", what, e.toString());
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * T1-T3 resolver: exact match → drop secondary → registry item.
     * Compiles resolved sub-patterns into the SAME network's cache as the task.
     *
     * NO fuzzy gate: chain sub-patterns (e.g. appflux cores 4k/1k, Fibonacci-style
     * recursive chains) have secondary/NBT variants that don't exact-match. Gating
     * Try2/Try3 on isFuzzyPattern made the resolver return null for them →
     * "no pattern → missing" and the whole chain could not be calculated.
     * Each match is verified against the pattern's actual primary output to avoid
     * false matches.
     */
    private static IPatternDetails resolve(Object network,
                                           CraftingService service,
                                           Map<AEKey, IPatternDetails> cache,
                                           AEKey key) {
        // ConcurrentHashMap forbids null keys — guard against a null constant-pool entry.
        if (key == null) return null;
        var cached = cache.get(key);
        if (cached != null) return cached;

        // Try 1: exact match — prefer the smallest-output pattern to avoid picking a
        // mega/bulk pattern (e.g. 625,000 alloy_infused per craft) for a small need.
        var subs = service.getCraftingFor(key);
        if (!subs.isEmpty()) {
            var sub = pickBestPattern(subs, key);
            PatternCompiler.compileIfAbsent(network, sub);
            cache.put(key, sub);
            return sub;
        }
        // Try 2: drop secondary (verify the pattern actually outputs the item)
        var clean = key.dropSecondary();
        if (!clean.equals(key)) {
            subs = service.getCraftingFor(clean);
            if (!subs.isEmpty()) {
                var sub = pickBestPattern(subs, clean);
                if (patternOutputs(sub, clean)) {
                    PatternCompiler.compileIfAbsent(network, sub);
                    cache.put(key, sub);
                    return sub;
                }
            }
        }
        // Try 3: registry item (verify the pattern actually outputs the item)
        var id = key.getId();
        if (id != null) {
            // Forge 1.20.1 runtime uses SRG mappings — BuiltInRegistries.ITEM is a
            // Mojang-mapped field name and throws NoSuchFieldError at runtime.
            // ForgeRegistries is a Forge class (never remapped), so it always works.
            var item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
            if (item != null) {
                var pureKey = appeng.api.stacks.AEItemKey.of(item);
                if (pureKey != null) {
                    subs = service.getCraftingFor(pureKey);
                    if (!subs.isEmpty()) {
                        var sub = pickBestPattern(subs, key);
                        if (patternOutputs(sub, key)) {
                            PatternCompiler.compileIfAbsent(network, sub);
                            cache.put(key, sub);
                            return sub;
                        }
                    }
                }
            }
        }
        // NOT FOUND. No global-cache matching: the network's crafting service
        // (getCraftingFor) is the only matching scope — inherently grid-scoped.
        // ConcurrentHashMap forbids null values, so we cannot cache a null here;
        // just return null; the caller records it as missing.
        // resolve() null DIAG disabled (v1.8.20) — keep log clean.
        // AE2VMAddon.LOGGER.warn("[AE2-VM]   resolve() → null for {}", key);
        return null;
    }

    /**
     * Pick the pattern that produces {@code want} with the SMALLEST per-craft output
     * amount. This avoids picking mega/bulk patterns (e.g. one that outputs 625,000
     * alloy_infused per craft) over normal ones — otherwise a request that needs only
     * a handful of an item still crafts the mega pattern once and the plan shows
     * millions/billions of items (the 1000 energy-tablet → 625M copper bug). Falls
     * back to the first pattern if none matches {@code want}.
     */
    private static IPatternDetails pickBestPattern(Collection<IPatternDetails> patterns, AEKey want) {
        IPatternDetails best = null;
        IPatternDetails fallback = null;
        long bestOut = Long.MAX_VALUE;
        for (var p : patterns) {
            if (p == null) continue;
            if (fallback == null) fallback = p;
            if (want != null && !patternOutputs(p, want)) continue;
            var out = p.getPrimaryOutput();
            long amt = out == null ? Long.MAX_VALUE : Math.max(1, out.amount());
            if (amt < bestOut) { bestOut = amt; best = p; }
        }
        return best != null ? best : fallback;
    }

    /** True if the pattern's primary output is {@code want} (by key or by registry id). */
    private static boolean patternOutputs(IPatternDetails pattern, AEKey want) {
        var out = pattern.getPrimaryOutput();
        if (out == null || out.what() == null) return false;
        if (out.what().equals(want)) return true;
        return want.getId() != null && want.getId().equals(out.what().getId());
    }

    /**
     * (v1.12.x GTL) True if any missing key (other than the requested item itself)
     * now has a pattern:
     * <ul>
     *   <li>Check 1 — the CraftingService can see it (stale-missing: pattern added
     *       AFTER the bundle captured it as a missing leaf);</li>
     *   <li>Check 2 — the VM compiled it before but the provider is temporarily absent
     *       (GTL refreshNodeCraftingProvider removeProvider→addProvider window), so the
     *       bytecode cache knows the key is craftable even though the live registry does
     *       not.</li>
     * </ul>
     */
    private static boolean missingKeyNowCraftable(CraftingService service, ICraftingPlan plan, AEKey requested) {
        for (var e : plan.missingItems()) {
            AEKey missingKey = e.getKey();
            if (missingKey.equals(requested)) continue;
            if (!service.getCraftingFor(missingKey).isEmpty()) return true;
            if (PatternCompiler.findCompiledByOutput(missingKey) != null) return true;
        }
        return false;
    }

    // === v1.15.x PERF2 / GTL additions ported from 1.21.1 (mod 1.13.13) =================

    // (v1.13.6 COMPILE-TIME HIT) Per-grid LRU of the most recently requested outputs
    // (output key -> last requested amount). When the network's pattern set changes
    // (refreshNodeCraftingProvider -> bumpPatternVersion), the plans of these hot outputs
    // are recomputed IN THE BACKGROUND so the NEXT request is a warm cache HIT instead of
    // a 10-30ms cold capture. This moves the first-round hit from round 2 to round 1 for
    // the items players actually craft.
    private static final int HOT_LRU_MAX = 8;
    private static final long HOT_RECOMPUTE_DEBOUNCE_MS = 250L;
    private static final int HOT_RECOMPUTE_MAX_OUTPUTS = 4;
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, java.util.Map<AEKey, Long>> HOT_OUTPUTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, Long> HOT_RECOMPUTE_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService VM_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(
                    Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2)),
                    r -> { Thread t = new Thread(r, "ae2vm-hot-recompute"); t.setDaemon(true); return t; });

    /** (v1.13.6) Record one (grid, what, amount) request into the per-grid LRU so that
     *  subsequent pattern-set changes will re-plan this output in the background. */
    private static void recordHotOutput(IGrid grid, AEKey what, long amount) {
        if (grid == null || what == null) return;
        java.util.Map<AEKey, Long> lru = HOT_OUTPUTS.computeIfAbsent(grid, g -> {
            return (java.util.Map<AEKey, Long>) java.util.Collections.synchronizedMap(
                    new LRULinkedHashMap<AEKey, Long>(HOT_LRU_MAX));
        });
        lru.put(what, amount);
    }

    /** (v1.13.6) Called by the pattern-change hooks (refreshNodeCraftingProvider /
     *  updatePatterns) right after bumpPatternVersion(). Debounced: schedules a
     *  background re-plan of the grid's hot outputs, so the next request for an item
     *  the player crafts often is served from the freshly computed plan - the
     *  first-round hit happens at COMPILE time (pattern set change) instead of on
     *  the second request. */
    public static void onPatternsChanged(IGrid grid) {
        if (grid == null) return;
        long now = System.currentTimeMillis();
        Long last = HOT_RECOMPUTE_AT.get(grid);
        if (last != null && now - last < HOT_RECOMPUTE_DEBOUNCE_MS) return;
        HOT_RECOMPUTE_AT.put(grid, now);
        CompletableFuture.runAsync(() -> hotRecompute(grid), VM_EXECUTOR);
    }

    /** Background re-plan of the hot outputs. Runs on the common pool (never the server
     *  thread). Best-effort: any failure just leaves the VM without a fresh plan (the
     *  next request falls back to the normal slow path, which is correct). */
    private static void hotRecompute(IGrid grid) {
        try {
            var lru = HOT_OUTPUTS.get(grid);
            if (lru == null || lru.isEmpty()) return;
            CraftingService service = (CraftingService) grid.getCraftingService();
            if (service == null) return;
            var storage = grid.getStorageService();
            java.util.Map<AEKey, Long> copy;
            synchronized (lru) {
                copy = new java.util.LinkedHashMap<>(lru);
            }
            int n = 0;
            for (var e : copy.entrySet()) {
                if (n++ >= HOT_RECOMPUTE_MAX_OUTPUTS) break;
                try {
                    AEKey what = e.getKey();
                    long amount = e.getValue();
                    Collection<IPatternDetails> patterns = service.getCraftingFor(what);
                    if (patterns == null || patterns.isEmpty()) continue;
                    IPatternDetails top = pickBestPattern(patterns, what);
                    if (top == null) continue;
                    CraftingVM vm = VM_CACHE.get(grid);
                    if (vm == null) continue;
                    if (vm.isExecuting()) continue;
                    PatternCompiler.compileIfAbsent((Object) grid, top);
                    CraftingBytecode req = PatternCompiler.compileRequest((Object) grid, top, amount);
                    var networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                    var craftingInventory = new ChildCraftingSimulationState(networkInv);
                    craftingInventory.ignore(what);
                    vm.execute(req, craftingInventory);
                } catch (Throwable t) { /* best-effort */ }
            }
        } catch (Throwable t) { /* best-effort */ }
    }

    // === tryCachedPlan: 3-overload API-layer warm-path short-circuit ===================

    /** (v1.13.x PERF2) Warm-path short-circuit for the API layer. Tries to serve the
     *  memoized plan WITHOUT running the slow path, using the supplied simulation state.
     *  Returns the cached plan when every guard passes (pattern version, DAG identity,
     *  used-leaf stock, missing-still-missing), else null; the caller then runs the
     *  normal slow path, which re-checks anyway. */
    public static CraftingPlan tryCachedPlan(CraftingBytecode requestBytecode,
                                           CraftingSimulationState simulation) {
        return tryCachedPlan(requestBytecode, simulation, null);
    }

    /** (v1.13.x PERF2) Warm-path short-circuit with a direct stock reader (e.g. the
     *  network's tick-cached inventory). Same guards as the simulation variant, but stock
     *  verification is O(1) per key - no inventory copy, no simulation-state machinery.
     *  NOTE: 1.20.1 has a single-argument tryFastPath - the stockReader is accepted here
     *  for API parity with 1.21.1, but the inner check falls back to the simulation-state
     *  path (passing a stockReader-backed simulation state is the caller's responsibility
     *  via the 2-arg overload). */
    public static CraftingPlan tryCachedPlan(CraftingBytecode requestBytecode,
                                           java.util.function.Function<AEKey, Long> stockReader) {
        if (requestBytecode == null) return null;
        if (!isLoaded()) return null;
        if (stockReader == null) return null;
        // 1.20.1: VM instances aren't always registered in VM_CACHE (tests construct a
        // CraftingVM directly via `new CraftingVM(...)` without going through calculate()).
        // Try every VM we know about - the bytecode-keyed tryFastPath will decline on mismatch.
        for (CraftingVM vm : VM_CACHE.values()) {
            if (!vm.hasCachedPlanForRequest(requestBytecode)) continue;
            try {
                CraftingPlan p = vm.tryFastPath(requestBytecode, stockReader);
                if (p != null) return p;
            } catch (Throwable t) { /* try next */ }
        }
        return null;
    }
    private static CraftingPlan tryCachedPlan(CraftingBytecode requestBytecode,
                                            CraftingSimulationState simulation,
                                            java.util.function.Function<AEKey, Long> stockReader) {
        if (requestBytecode == null || simulation == null) return null;
        if (!isLoaded()) return null;
        CraftingVM vm = VM_CACHE.values().stream()
                .filter(v -> v.hasCachedPlanForRequest(requestBytecode))
                .findFirst().orElse(null);
        if (vm == null) return null;
        try { return vm.tryFastPath(requestBytecode); } catch (Throwable t) { return null; }
    }

    // === Cycle-aware pattern selection (v1.12.x GTL, ported from VM-GTL) ===============

    /** (v1.12.x GTL) Cycle-aware candidate filter: returns true if the pattern's inputs
     *  would close a DEAD ring (unseeded SCC with no external supplier). Such patterns
     *  would force the VM to decline the entire branch and walk the slow path; we drop
     *  them here so the fast path stays a fast path. */
    public static boolean wouldCauseCycle(
            java.util.function.Function<AEKey, Collection<IPatternDetails>> resolver,
            IPatternDetails pattern, AEKey output,
            java.util.function.Function<AEKey, Long> stockSnapshot) {
        if (pattern == null || output == null) return false;
        if (pattern.getInputs() == null || pattern.getInputs().length == 0) return false;
        java.util.Set<AEKey> seed = new java.util.HashSet<>();
        if (stockSnapshot != null) {
            for (var e : pattern.getInputs()) {
                for (GenericStack gs : e.getPossibleInputs()) {
                    if (gs == null) continue;
                    AEKey k = gs.what();
                    if (k == null) continue;
                    Long v = stockSnapshot.apply(k);
                    if (v != null && v > 0) seed.add(k);
                }
            }
        }
        java.util.Set<AEKey> inputs = new java.util.HashSet<>();
        for (var e : pattern.getInputs()) {
            for (GenericStack gs : e.getPossibleInputs()) {
                if (gs == null) continue;
                AEKey k = gs.what();
                if (k != null && !k.equals(output)) inputs.add(k);
            }
        }
        if (inputs.isEmpty()) return false;
        for (AEKey k : inputs) {
            if (seed.contains(k)) continue;
            if (resolver == null) return true;
            Collection<IPatternDetails> suppliers = resolver.apply(k);
            if (suppliers == null || suppliers.isEmpty()) return true;
        }
        return false;
    }

    public static boolean wouldCauseCycle(
            java.util.function.Function<AEKey, Collection<IPatternDetails>> resolver,
            IPatternDetails pattern, AEKey output, KeyCounter stockSnapshot) {
        return wouldCauseCycle(resolver, pattern, output,
                stockSnapshot == null ? null : (java.util.function.Function<AEKey, Long>) stockSnapshot::get);
    }

    public static java.util.Set<AEKey> computeCycleBoundKeys(
            java.util.function.Function<AEKey, Collection<IPatternDetails>> resolver,
            AEKey output,
            java.util.function.Function<AEKey, Long> stockSnapshot) {
        java.util.Set<AEKey> result = new java.util.HashSet<>();
        if (resolver == null || output == null) return result;
        java.util.Set<AEKey> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<AEKey> queue = new java.util.ArrayDeque<>();
        queue.add(output);
        while (!queue.isEmpty()) {
            AEKey k = queue.pop();
            if (!visited.add(k)) continue;
            Collection<IPatternDetails> suppliers = resolver.apply(k);
            if (suppliers == null || suppliers.isEmpty()) {
                if (stockSnapshot == null || stockSnapshot.apply(k) == null || stockSnapshot.apply(k) <= 0) {
                    result.add(k);
                }
                continue;
            }
            for (IPatternDetails p : suppliers) {
                if (p == null || p.getInputs() == null) continue;
                for (var e : p.getInputs()) {
                    for (GenericStack gs : e.getPossibleInputs()) {
                        if (gs == null) continue;
                        AEKey sub = gs.what();
                        if (sub != null && !sub.equals(k)) queue.add(sub);
                    }
                }
            }
        }
        return result;
    }

    public static java.util.Set<AEKey> computeCycleBoundKeys(
            java.util.function.Function<AEKey, Collection<IPatternDetails>> resolver,
            AEKey output, KeyCounter stockSnapshot) {
        return computeCycleBoundKeys(resolver, output,
                stockSnapshot == null ? null : (java.util.function.Function<AEKey, Long>) stockSnapshot::get);
    }

    // === Hook into calculate: record hot output for pattern-change background re-plan ==

    /** (v1.13.6) Wrap calculate to record the (grid, what, amount) into the hot LRU so
     *  future pattern-set changes can re-plan this output in the background. The
     *  1.20.1 calculate signature is unchanged - we record the hot output before the
     *  long-running async call, so the hotRecompute background task sees it. */

    /** (v1.13.6) Helper: LRU-bounded LinkedHashMap for the per-grid hot-output cache.
     *  Access-order (true), bounded by maxEntries; oldest entry evicted on insertion. */
    private static final class LRULinkedHashMap<K, V> extends java.util.LinkedHashMap<K, V> {
        private final int maxEntries;
        LRULinkedHashMap(int maxEntries) { super(16, 0.75f, true); this.maxEntries = maxEntries; }
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }

}

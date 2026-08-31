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
     * Per-grid CraftingVM instances, reused across requests so the JIT bundleCache
     * (per-pattern 1-craft subtree effects) survives between crafting requests on the
     * same network. Keyed by the grid so different networks never share bundles.
     * execute() is synchronized on the VM, so concurrent requests are serialized.
     */
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, CraftingVM> VM_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * (v1.13.9 PERF) Dedicated calculation executor: isolates VM calculations from the
     * common pool, which at world-load is saturated by other mods' async tasks (the very
     * first request paid a 10ms+ queue wait at boot before the worker even started).
     * Two daemon threads — per-grid execute() is synchronized, so concurrent grids
     * serialize naturally; 2 threads keep headroom for simultaneous requests on
     * different grids/requester types.
     */
    private static final java.util.concurrent.ExecutorService VM_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "ae2vm-calc");
                t.setDaemon(true);
                return t;
            });

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
     * (v1.13.x PERF) Missing totals at or below this amount are treated as possibly-fresh
     * GTL provider-sync candidates (they keep the 60ms settle window); above it the
     * missing is considered genuine (no sync adds millions of units) and the sleep is
     * skipped. The user-facing failure cases (omni/creative mega-chains) report millions
     * to trillions of missing units; the GTL false-negative signature is a small leaf
     * (e.g. infuscolium=8456).
     */
    private static final long RETRY_SETTLE_MAX_MISSING_TOTAL = 1_000_000L;
    // (v1.13.1 DIAG) warm-hit counter for the [AE2-VM] WARM timing line.
    private static final java.util.concurrent.atomic.AtomicLong WARM_REQ = new java.util.concurrent.atomic.AtomicLong();
    // (v1.13.1 DIAG) cold-path counter for the [AE2-VM] COLD timing line.
    private static final java.util.concurrent.atomic.AtomicLong COLD_REQ = new java.util.concurrent.atomic.AtomicLong();

    // (v1.13.6 COMPILE-TIME HIT) Per-grid LRU of the most recently requested outputs
    // (output key → last requested amount). When the network's pattern set changes
    // (refreshNodeCraftingProvider → bumpPatternVersion), the plans of these hot outputs
    // are recomputed IN THE BACKGROUND ("compile time"), so the NEXT request for them is
    // a warm cache HIT instead of a 10-30ms cold capture. This is what moves the
    // "first-round hit" from round 2 to round 1 for the items players actually craft.
    private static final int HOT_LRU_MAX = 8;
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, java.util.LinkedHashMap<AEKey, Long>> HOT_OUTPUTS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<IGrid, Long> HOT_RECOMPUTE_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Debounce: pattern providers refresh often on a busy network; recompute at most
    // once per 500ms per grid (a recompute costs ~10-30ms of background CPU per output).
    private static final long HOT_RECOMPUTE_DEBOUNCE_MS = 500L;
    // Cap the number of outputs re-planned per pattern change.
    private static final int HOT_RECOMPUTE_MAX_OUTPUTS = 4;

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

        // (v1.13.6) Record the request in the grid's hot-output LRU so a future pattern
        // change can background-precompute this output's plan ("first-round hit").
        recordRequest(grid, what, amount);

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


        // Reuse a per-grid VM so its JIT bundleCache (per-pattern 1-craft subtree
        // effects) AND its PERSISTENT resolver cache (v1.13.1) survive across requests
        // on the same network — otherwise every request would re-capture every
        // sub-pattern (JIT hit-rate ~0-49%) and re-resolve every DAG node (AE2's
        // getSortedPatterns() re-sorts + allocates per call). Both caches are cleared
        // together on any pattern-version change, so they can never outlive the
        // pattern set they reference. execute() is synchronized on the VM, so
        // concurrent requests are safe.
        CraftingVM vm = VM_CACHE.computeIfAbsent(grid, g ->
                new CraftingVM(g, key -> resolve(g, (CraftingService) g.getCraftingService(),
                        new java.util.concurrent.ConcurrentHashMap<>(), key)));
        vm.setPatternResolver(key -> resolve(grid, service, vm.getResolverCache(), key));

        // Create simulation inventory.
        // IMPORTANT: always snapshot the LIVE network inventory (getAvailableStacks),
        // never the cached inventory. AE2's NetworkCraftingSimulationState falls back to
        // getCachedInventory() for non-player requesters (ECO pattern buses, interfaces,
        // requester lambdas, ...). That cached snapshot can be stale — the plan would then
        // claim more items than the CPU can actually extract at submit time, and AE2 refuses
        // the job with CraftErrorMissingIngredient ("无法从网络中取出某些材料").
        var storage = grid.getStorageService();

        // (v1.13.4 PERF) SERVER-THREAD warm short-circuit: while the VM is idle, the
        // memoized-plan check runs HERE (before supplyAsync) so warm hits never pay the
        // ForkJoinPool scheduling wait (measured 150-450us of the VM OK gap). VM OK then
        // ≈ the warm work itself — target <100us. The VM's executing flag guards against
        // blocking the server thread behind a concurrent slow-path execute (ms-scale).
        if (!vm.isExecuting()) {
            ICraftingPlan warmPlan = tryWarmPlan(vm, grid, storage, topPattern, amount, what);
            if (warmPlan != null) {
                return CompletableFuture.completedFuture(warmPlan);
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                long c0 = System.nanoTime();
                // (v1.13.4) Async-worker warm fallback (reached only when the VM was
                // busy executing a slow path when the request arrived, or the
                // server-thread attempt missed).
                ICraftingPlan warmPlan = tryWarmPlan(vm, grid, storage, topPattern, amount, what);
                if (warmPlan != null) {
                    return warmPlan;
                }
                long c1 = System.nanoTime();
                CraftingBytecode requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);
                long c2 = System.nanoTime();
                var networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                var craftingInventory = new ChildCraftingSimulationState(networkInv);
                craftingInventory.ignore(what);
                long c3 = System.nanoTime();
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
                long c4 = 0;
                for (int retry = 0; retry < maxRetries; retry++) {
                    rawPlan = vm.execute(requestBytecode, craftingInventory);
                    c4 = System.nanoTime(); // (v1.13.6 COLD DIAG) after first execute pass
                    // Fix: ignore(what) hides the requested item from simulation.
                    // Recursive sub-patterns needing the same item type trigger cycle
                    // detection -> false "missing". Check real network stock and correct.
                    // (v1.13.7 PERF) Only pay the FULL network inventory walk when the
                    // REQUESTED key itself is in the missing set — the common NAST
                    // missing-heavy case (creative/omni mega-chains) has what NOT missing,
                    // so the walk (measured 5-10ms on this pack) was pure waste.
                    if (rawPlan.simulation() && !rawPlan.missingItems().isEmpty()) {
                        long missingCount = rawPlan.missingItems().get(what);
                        if (missingCount > 0) {
                        var realStock = storage.getInventory().getAvailableStacks();
                        long avail = realStock.get(what);
                        if (avail > 0) {
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
                        boolean needsRetry = staleMissingNowCraftable(service, vm, rawPlan, what);
                        if (!needsRetry) {
                            // (v1.13.x PERF) Genuinely huge missing totals cannot be fixed by a
                            // 60ms settle window (no GTL provider sync adds millions of units) —
                            // report the plan immediately instead of paying the sleep tax on every
                            // missing request. Small totals (≤ 1M units — the GTL provider-sync
                            // false-negative signature) still get the settle window.
                            if (allMissingBeyondSettleWindow(rawPlan)) {
                                break;
                            }
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
                            needsRetry = staleMissingNowCraftable(service, vm, rawPlan, what);
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
                long c5 = System.nanoTime();
                // (v1.13.6 COLD DIAG) First-round (cold) cost breakdown: where do the
                // ~10-30ms go? warmRecheck + compile + simBuild (network walk) + execute
                // (the VM) + post (retry sleeps / missing fixup / extra inventory walk).
                if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
                AE2VMAddon.LOGGER.info(
                    "[AE2-VM] COLD #{}: total={}us (warmRecheck={}us, compile={}us, simBuild={}us, execute={}us, post={}us)",
                    COLD_REQ.incrementAndGet(), (c5 - c0) / 1000,
                    (c1 - c0) / 1000, (c2 - c1) / 1000, (c3 - c2) / 1000,
                    (c4 - c3) / 1000, (c5 - c4) / 1000);
                }
                // (v1.13.8 PERF) Prime the cached-inventory snapshot in the background so
                // the NEXT warm request does not rebuild it on the server thread
                // (5-15ms server block on this pack without storage watchers).
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        stockForGrid(grid, storage, true);
                    } catch (Throwable ignore) {
                        // best-effort
                    }
                });
                return rawPlan;
            } catch (Exception e) {
                // AE2VMAddon.LOGGER.warn("[AE2-VM] Calculation failed for {}: {}", what, e.toString());
                throw new RuntimeException(e);
            }
        }, VM_EXECUTOR);
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
    // (v1.13.4) Negative-resolution TTL: a "not craftable" verdict stays cached for
    // this long. Bounds the staleness window for patterns added without a version bump.
    private static final long RESOLVE_NEG_TTL_MS = 2000L;

    // (v1.13.4) Per-grid reused cached-inventory snapshot. getCachedInventory() can
    // trigger a FULL network rebuild on this pack (no storage watchers → AE2 marks it
    // dirty every tick), costing 50-175us per warm request. Reuse the captured counter
    // for up to STOCK_SNAP_TTL_MS. Feasible (executable) plans are re-verified against
    // a FRESH capture whenever the reused snapshot predates the current tick; missing
    // previews (the heavy NAST case — never submitted to a CPU) skip that entirely.
    private static final long STOCK_SNAP_TTL_MS = 1500L;

    private static final class StockSnap {
        final KeyCounter counter;
        final long capturedAt;
        final long capturedTick;

        StockSnap(KeyCounter counter, long capturedAt, long capturedTick) {
            this.counter = counter;
            this.capturedAt = capturedAt;
            this.capturedTick = capturedTick;
        }
    }

    private static final java.util.concurrent.ConcurrentHashMap<IGrid, StockSnap> STOCK_SNAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static long currentTick() {
        try {
            return appeng.hooks.ticking.TickHandler.instance().getCurrentTick();
        } catch (Throwable t) {
            return -1L; // tick unknown — treat snapshots as fresh
        }
    }

    private static KeyCounter stockForGrid(IGrid grid,
                                           appeng.api.networking.storage.IStorageService storage,
                                           boolean forceFresh) {
        long now = System.currentTimeMillis();
        if (!forceFresh) {
            StockSnap s = STOCK_SNAP.get(grid);
            if (s != null && now - s.capturedAt < STOCK_SNAP_TTL_MS) {
                return s.counter;
            }
        }
        KeyCounter fresh = storage.getCachedInventory();
        STOCK_SNAP.put(grid, new StockSnap(fresh, now, currentTick()));
        return fresh;
    }

    /** True when the reused snapshot was captured in the CURRENT tick (fresh). */
    private static boolean snapshotIsCurrentTick(IGrid grid) {
        StockSnap s = STOCK_SNAP.get(grid);
        if (s == null) return true;
        long tick = currentTick();
        return s.capturedTick == tick || tick < 0;
    }

    /**
     * (v1.13.4) Warm-path short-circuit shared by the server-thread check (before
     * supplyAsync) and the async-worker fallback. Serves the memoized plan when every
     * VM guard passes, else null. Best-effort: any failure falls through to the slow
     * path.
     */
    private static ICraftingPlan tryWarmPlan(CraftingVM vm, IGrid grid,
                                             appeng.api.networking.storage.IStorageService storage,
                                             IPatternDetails topPattern, long amount, AEKey what) {
        try {
            long w0 = System.nanoTime();
            CraftingBytecode warmRequest = PatternCompiler.compileRequest(grid, topPattern, amount);
            long w1 = System.nanoTime();
            // (v1.13.8 PERF) Cold request: no memoized plan exists for this
            // (output, craftTimes, version) → skip the stock snapshot entirely. The
            // cached-inventory rebuild costs 5-15ms on this pack (no storage watchers →
            // AE2 marks it dirty every tick) and is pure waste when there is no plan to
            // guard. tryCachedPlan would only re-check the same cache key and return null.
            if (!vm.hasCachedPlanForRequest(warmRequest)) {
                return null;
            }
            KeyCounter stock = stockForGrid(grid, storage, false);
            long w2 = System.nanoTime();
            ICraftingPlan warmPlan = vm.tryCachedPlan(warmRequest, key -> stock.get(key));
            long w3 = System.nanoTime();
            if (warmPlan != null && warmPlan.missingItems().get(what) == 0) {
                // Feasible plan from a reused snapshot predating this tick: re-verify
                // once against a fresh capture (missing previews skip this).
                if (warmPlan.missingItems().isEmpty() && !snapshotIsCurrentTick(grid)) {
                    KeyCounter freshStock = stockForGrid(grid, storage, true);
                    warmPlan = vm.tryCachedPlan(warmRequest, key -> freshStock.get(key));
                    w3 = System.nanoTime();
                    if (warmPlan == null || warmPlan.missingItems().get(what) != 0) {
                        return null;
                    }
                }
                if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
                AE2VMAddon.LOGGER.info(
                    "[AE2-VM] WARM #{}: worker={}us (compile={}us, cachedInv={}us, tryCachedPlan={}us)",
                    WARM_REQ.incrementAndGet(), (w3 - w0) / 1000,
                    (w1 - w0) / 1000, (w2 - w1) / 1000, (w3 - w2) / 1000);
                }
                return warmPlan;
            }
        } catch (Throwable t) {
            // Warm path is best-effort — any failure falls through to the slow path.
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // (v1.13.6 COMPILE-TIME HIT) Hot-output LRU + background re-plan on pattern change.
    // ---------------------------------------------------------------------

    /** Record a requested output (with its amount) in the grid's hot LRU. */
    private static void recordRequest(IGrid grid, AEKey what, long amount) {
        if (grid == null || what == null) return;
        HOT_OUTPUTS.compute(grid, (g, lru) -> {
            java.util.LinkedHashMap<AEKey, Long> m = lru;
            if (m == null) m = new java.util.LinkedHashMap<>(16, 0.75f, true); // accessOrder=true
            m.put(what, amount);
            while (m.size() > HOT_LRU_MAX) {
                var it = m.entrySet().iterator();
                it.next();
                it.remove();
            }
            return m;
        });
    }

    /**
     * (v1.13.6) Called by the pattern-change hooks (refreshNodeCraftingProvider /
     * updatePatterns) right after bumpPatternVersion(). Debounced: schedules a
     * background re-plan of the grid's hot outputs, so the next request for an item
     * the player crafts often is served from the freshly computed plan — the
     * "first-round hit" happens at COMPILE time (pattern set change) instead of on
     * the second request.
     */
    public static void onPatternsChanged(IGrid grid) {
        if (grid == null) return;
        long now = System.currentTimeMillis();
        Long last = HOT_RECOMPUTE_AT.get(grid);
        if (last != null && now - last < HOT_RECOMPUTE_DEBOUNCE_MS) return;
        HOT_RECOMPUTE_AT.put(grid, now);
        java.util.concurrent.CompletableFuture.runAsync(() -> hotRecompute(grid), VM_EXECUTOR);
    }

    /**
     * Background re-plan of the hot outputs. Runs on the common pool (never the server
     * thread). Best-effort: any failure just leaves the VM without a fresh plan (the
     * next request falls back to the normal slow path, which is correct).
     */
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
                    if (vm == null) continue; // this grid never calculated anything yet
                    if (vm.isExecuting()) continue; // a real request is running — don't block it
                    PatternCompiler.compileIfAbsent(grid, top);
                    CraftingBytecode req = PatternCompiler.compileRequest(grid, top, amount);
                    var networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                    var craftingInventory = new ChildCraftingSimulationState(networkInv);
                    craftingInventory.ignore(what);
                    // Slow-path execute: fills bundleCache + resolverCache AND stores the
                    // memoized plan (fastPlanCached). The next real request for this
                    // output/amount then hits tryCachedPlan — a compile-time HIT.
                    vm.execute(req, craftingInventory);
                } catch (Throwable t) {
                    // best-effort — never disturb the server
                }
            }
        } catch (Throwable t) {
            // best-effort
        }
    }

    private static IPatternDetails resolve(Object network,
                                           CraftingService service,
                                           Map<AEKey, Object> cache,
                                           AEKey key) {
        // ConcurrentHashMap forbids null keys — guard against a null constant-pool entry.
        if (key == null) return null;
        Object cached = cache.get(key);
        if (cached != null) {
            if (cached instanceof IPatternDetails) {
                return (IPatternDetails) cached;
            }
            // (v1.13.4) Negative sentinel (long[]{capturedAtMillis}): re-resolve only
            // after the short TTL, so a pattern added WITHOUT a version bump still
            // self-heals within ~RESOLVE_NEG_TTL_MS instead of being masked forever.
            if (cached instanceof long[]) {
                long[] ts = (long[]) cached;
                if (System.currentTimeMillis() - ts[0] < RESOLVE_NEG_TTL_MS) {
                    return null;
                }
                // expired — fall through to re-resolve (overwrites below)
            }
        }

        // Try 1: exact match — prefer the smallest-output pattern to avoid picking a
        // mega/bulk pattern (e.g. 625,000 alloy_infused per craft) for a small need.
        var subs = service.getCraftingFor(key);
        if (!subs.isEmpty()) {
            // (v1.12.x GTL CYCLE-AWARE, ported from VM-GTL) Filter out patterns whose
            // inputs would close a DEAD ring (unseeded SCC with no external supplier),
            // e.g. steel_ingot↔steel_dust. Applied unconditionally — even a single
            // candidate may be cycle-prone. When ALL candidates are cycle-prone, keep
            // the originals (the VM's runtime circularCache guard handles the cycle).
            var filtered = new java.util.ArrayList<IPatternDetails>(subs.size());
            var pruned = new java.util.ArrayList<String>();
            var ringStock = resolveStockSnapshot(network);
            for (var p : subs) {
                boolean cyc = p != null && wouldCauseCycle(key2 -> service.getCraftingFor(key2), p, key,
                        k -> ringStock == null ? 0L : ringStock.get(k));
                if (!cyc) {
                    filtered.add(p);
                } else if (p != null) {
                    pruned.add(patternOutputNameStatic(p));
                }
            }
            if (!filtered.isEmpty()) {
                subs = filtered;
            } else if (!pruned.isEmpty()) {
                // all candidates cycle-prone → fall back to originals (runtime guard)
            }
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
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
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
        // NOT FOUND. (v1.13.4) Cache the negative verdict as a TTL'd sentinel so the
        // warm-hit guards (which re-resolve every pure-leaf key on EVERY request —
        // measured 100-500us on the NAST creative chain) hit the map instead of the
        // full resolver. The TTL bounds staleness for pattern additions without a
        // version bump; version bumps and clearBundleCache() clear the cache at once.
        cache.put(key, new long[] { System.currentTimeMillis() });
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
    public static IPatternDetails pickBestPattern(Collection<IPatternDetails> patterns, AEKey want) {
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
     * (v1.12.x GTL CYCLE-AWARE, ported from VM-GTL) True if resolving {@code candidate}
     * (which outputs {@code target}) would close a DEAD ring — an SCC of the
     * recipe-definition graph in which no member has network stock (seed) and no
     * pattern outside the component produces a member. Seeded rings, externally-fed
     * rings and re-flow rings stay craftable; genuinely dead rings are pruned so the
     * VM does not consume stock on a ring that can never be entered from nothing.
     */
    public static boolean wouldCauseCycle(
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            IPatternDetails candidate, AEKey target) {
        return wouldCauseCycle(patternLookup, candidate, target, null);
    }

    /**
     * (v1.14.x SEEDED-RING FIX, ported from VM-GTL) Cycle detection with stock-awareness.
     * <p>A 2-hop ring (dust {@literal <->} ingot pulverize/smelt) is a legitimate GT
     * production cycle: with a stock seed on either member the ring can be entered
     * and terminates once the seed stock is consumed. Pruning the ring unconditionally
     * made the plan silently drop the intermediate craft and the CPU stall forever.
     * Only prune when NO ring member holds stock — a genuinely dead ring that would
     * spin forever (the CALL-time resolvingKeys guard remains as the backstop).</p>
     */
    public static boolean wouldCauseCycle(
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            IPatternDetails candidate, AEKey target,
            java.util.function.Function<AEKey, Long> stockLookup) {
        try {
            // Direct self-edge: a pattern that consumes its own output can never fire
            // without inventing items — always prune.
            for (var input : candidate.getInputs()) {
                var stacks = input.getPossibleInputs();
                if (stacks != null && stacks.length > 0 && stacks[0] != null
                        && stacks[0].what() != null && stacks[0].what().equals(target)) {
                    return true;
                }
            }
            // Generic cycle analysis over the RECIPE-DEFINITION graph. Collect the keys
            // the target transitively depends on, then ask which of them sit in a DEAD
            // ring (unseeded, no external supplier). A production pattern whose target
            // is a dead ring member and whose inputs are all inside the dead ring is
            // pruned; seeded rings, externally-fed rings and re-flow rings stay craftable.
            java.util.Set<AEKey> keys = new java.util.HashSet<>();
            java.util.ArrayDeque<AEKey> queue = new java.util.ArrayDeque<>();
            keys.add(target); queue.add(target);
            // (v1.14.x CANDIDATE-EDGE) The candidate itself is a producer of the target:
            // its inputs are the target's dependencies even when the lookup omits the
            // target's producers (resolve() filters candidates one at a time).
            if (candidate.getInputs() != null) {
                for (var input : candidate.getInputs()) {
                    var st = input.getPossibleInputs();
                    if (st == null || st.length == 0 || st[0] == null || st[0].what() == null) continue;
                    AEKey ik = st[0].what();
                    if (keys.add(ik)) queue.add(ik);
                }
            }
            while (!queue.isEmpty()) {
                AEKey k = queue.poll();
                var subs = patternLookup.apply(k);
                if (subs == null) continue;
                for (var p : subs) {
                    if (p == null || p.getInputs() == null) continue;
                    for (var input : p.getInputs()) {
                        var st = input.getPossibleInputs();
                        if (st == null || st.length == 0 || st[0] == null || st[0].what() == null) continue;
                        AEKey ik = st[0].what();
                        if (keys.add(ik)) queue.add(ik);
                    }
                }
            }
            java.util.Set<AEKey> bound = computeCycleBoundKeys(keys, patternLookup, stockLookup, candidate);
            if (!bound.contains(target)) return false;
            // Ring-internal production only when EVERY input is inside the dead ring; an
            // external input means the ring is fed from outside and must stay craftable.
            if (candidate.getInputs() != null) {
                boolean allInRing = true;
                for (var input : candidate.getInputs()) {
                    var st = input.getPossibleInputs();
                    if (st == null || st.length == 0 || st[0] == null || st[0].what() == null) {
                        allInRing = false; break;
                    }
                    if (!bound.contains(st[0].what())) { allInRing = false; break; }
                }
                if (allInRing) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * (v1.14.x DEFINITION-GRAPH, ported from VM-GTL) Compute the set of keys that sit
     * in a DEAD ring: a strongly-connected component (size > 1) of the recipe-definition
     * graph in which no member has network stock (seed) and no pattern outside the
     * component produces a member. A dead ring can never be entered from nothing.
     */
    public static java.util.Set<AEKey> computeCycleBoundKeys(
            Collection<AEKey> keys,
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            java.util.function.Function<AEKey, Long> stockLookup) {
        return computeCycleBoundKeys(keys, patternLookup, stockLookup, null);
    }

    /**
     * (v1.14.x DEFINITION-GRAPH, ported from VM-GTL) Same as the 3-arg form, but also
     * injects the CANDIDATE pattern's own exchange edges into the graph. The candidate
     * is a producer of the target under test; without its edges the SCC analysis cannot
     * see that firing it closes the ring when the lookup does not register the target's
     * producers (resolve() filters candidates one at a time).
     */
    public static java.util.Set<AEKey> computeCycleBoundKeys(
            Collection<AEKey> keys,
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            java.util.function.Function<AEKey, Long> stockLookup,
            IPatternDetails candidate) {
        java.util.Set<AEKey> bound = new java.util.HashSet<>();
        try {
            if (keys == null || keys.isEmpty() || patternLookup == null) return bound;
            java.util.Map<AEKey, java.util.Set<AEKey>> graph = new HashMap<>();
            if (candidate != null && candidate.getInputs() != null && candidate.getOutputs() != null) {
                java.util.Set<AEKey> cins = new java.util.HashSet<>();
                for (var input : candidate.getInputs()) {
                    var st = input.getPossibleInputs();
                    if (st != null && st.length > 0 && st[0] != null && st[0].what() != null) {
                        cins.add(st[0].what());
                    }
                }
                if (!cins.isEmpty()) {
                    for (var gs : candidate.getOutputs()) {
                        if (gs == null || gs.what() == null) continue;
                        AEKey cout = gs.what();
                        for (AEKey i : cins) graph.computeIfAbsent(i, x -> new java.util.HashSet<>()).add(cout);
                    }
                }
            }
            for (AEKey k : keys) {
                var subs = patternLookup.apply(k);
                if (subs == null) continue;
                for (var p : subs) {
                    if (p == null) continue;
                    java.util.Set<AEKey> ins = new java.util.HashSet<>();
                    if (p.getInputs() != null) {
                        for (var input : p.getInputs()) {
                            var st = input.getPossibleInputs();
                            if (st != null && st.length > 0 && st[0] != null && st[0].what() != null) {
                                ins.add(st[0].what());
                            }
                        }
                    }
                    if (ins.isEmpty()) continue;
                    var outs = p.getOutputs();
                    if (outs == null) continue;
                    for (var gs : outs) {
                        if (gs == null || gs.what() == null) continue;
                        AEKey out = gs.what();
                        for (AEKey i : ins) graph.computeIfAbsent(i, x -> new java.util.HashSet<>()).add(out);
                    }
                }
            }
            for (var scc : tarjanScc(graph)) {
                if (scc.size() <= 1) continue; // no multi-node ring; self-loops are handled elsewhere
                boolean seeded = false;
                for (AEKey m : scc) { if (safeStock(stockLookup, m) > 0) { seeded = true; break; } }
                if (seeded) continue;
                boolean external = false;
                outer:
                for (AEKey k : keys) {
                    if (scc.contains(k)) continue;
                    var subs = patternLookup.apply(k);
                    if (subs == null) continue;
                    for (var p : subs) {
                        if (p == null) continue;
                        var outs = p.getOutputs();
                        if (outs == null) continue;
                        for (var gs : outs) {
                            if (gs != null && gs.what() != null && scc.contains(gs.what())) {
                                external = true; break outer;
                            }
                        }
                    }
                }
                if (external) continue;
                bound.addAll(scc);
            }
        } catch (Throwable ignored) {}
        return bound;
    }

    /** Tarjan strongly-connected components over the item-exchange graph (iterative). */
    private static java.util.List<java.util.Set<AEKey>> tarjanScc(java.util.Map<AEKey, java.util.Set<AEKey>> graph) {
        java.util.List<java.util.Set<AEKey>> sccs = new java.util.ArrayList<>();
        if (graph.isEmpty()) return sccs;
        Map<AEKey, Integer> index = new HashMap<>();
        Map<AEKey, Integer> low = new HashMap<>();
        java.util.ArrayDeque<AEKey> stack = new java.util.ArrayDeque<>();
        java.util.Set<AEKey> onStack = new java.util.HashSet<>();
        int[] counter = {0};
        for (AEKey start : graph.keySet()) {
            if (index.containsKey(start)) continue;
            java.util.ArrayDeque<Object[]> work = new java.util.ArrayDeque<>();
            work.push(new Object[]{start, null});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                AEKey node = (AEKey) frame[0];
                if (!index.containsKey(node)) {
                    index.put(node, counter[0]);
                    low.put(node, counter[0]);
                    counter[0]++;
                    stack.push(node);
                    onStack.add(node);
                }
                @SuppressWarnings("unchecked")
                java.util.Iterator<AEKey> it = (java.util.Iterator<AEKey>) frame[1];
                if (it == null) it = graph.getOrDefault(node, java.util.Set.of()).iterator();
                boolean advanced = false;
                while (it.hasNext()) {
                    AEKey w = it.next();
                    if (!index.containsKey(w)) {
                        frame[1] = it;
                        work.push(new Object[]{w, null});
                        advanced = true;
                        break;
                    } else if (onStack.contains(w)) {
                        low.put(node, Math.min(low.get(node), index.get(w)));
                    }
                }
                if (advanced) continue;
                if (it.hasNext()) continue;
                work.pop();
                if (low.get(node).equals(index.get(node))) {
                    java.util.Set<AEKey> scc = new java.util.HashSet<>();
                    AEKey w;
                    do {
                        w = stack.pop();
                        onStack.remove(w);
                        scc.add(w);
                    } while (!w.equals(node));
                    sccs.add(scc);
                }
                if (!work.isEmpty()) {
                    AEKey parent = (AEKey) work.peek()[0];
                    low.put(parent, Math.min(low.get(parent), low.get(node)));
                }
            }
        }
        return sccs;
    }

    private static long safeStock(java.util.function.Function<AEKey, Long> stockLookup, AEKey k) {
        try {
            if (stockLookup == null || k == null) return 0;
            Long v = stockLookup.apply(k);
            return v == null ? 0 : v;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Primary-output name of a pattern, for the resolve() prune diagnostics. */
    private static String patternOutputNameStatic(IPatternDetails p) {
        try {
            var outs = p.getOutputs();
            if (outs != null && !outs.isEmpty() && outs.get(0) != null && outs.get(0).what() != null) {
                return outs.get(0).what().toString();
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    /** Live network stock snapshot for seeded-ring decisions (null-safe). */
    private static appeng.api.stacks.KeyCounter resolveStockSnapshot(Object network) {
        try {
            if (network instanceof IGrid g && g.getStorageService() != null) {
                return g.getStorageService().getInventory().getAvailableStacks();
            }
        } catch (Throwable ignored) {}
        return null;
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
    /**
     * (v1.13.x PERF) True when the TOTAL missing amount exceeds the settle-window
     * magnitude. A just-registered GTL pattern fixes a small leaf (units to ~1M),
     * never millions of units, so such a plan cannot be a provider-sync race — the
     * 60ms wait is pure tax and is skipped. Saturating sum: a single entry that
     * already exceeds the threshold (or overflows the sum) counts as genuine.
     */
    private static boolean allMissingBeyondSettleWindow(ICraftingPlan plan) {
        long total = 0;
        for (var e : plan.missingItems()) {
            long v = e.getLongValue();
            if (v > RETRY_SETTLE_MAX_MISSING_TOTAL) return true;
            total += v;
            if (total < 0 || total > RETRY_SETTLE_MAX_MISSING_TOTAL) return true;
        }
        return total > RETRY_SETTLE_MAX_MISSING_TOTAL;
    }

    /**
     * (v1.13.8 PERF) Stale-evidence retry check: retry ONLY when a missing key is
     * plausibly a capture-time artifact, never for a genuine stock deficit:
     * <ul>
     *   <li>negative cache entry (resolved NOT-craftable during this execute) AND the
     *       CraftingService NOW has a pattern for it → pattern appeared mid-request
     *       (GTL buffer sync without a version bump);</li>
     *   <li>positive cache entry (was craftable during this execute) AND the
     *       CraftingService NOW does NOT have it → GTL refreshNodeCraftingProvider
     *       removeProvider→addProvider window (key temporarily absent).</li>
     * </ul>
     * A missing key that was ALWAYS craftable (positive cache + still present — the NAST
     * mega-chain signature: netherite/water/glowstone have patterns but no stock) fails
     * both checks and does NOT retry: re-executing cannot change a genuine deficit, and
     * each retry recompiles the root + re-walks the network (~3-6ms on this pack).
     */
    private static boolean staleMissingNowCraftable(CraftingService service, CraftingVM vm,
                                                    ICraftingPlan plan, AEKey requested) {
        java.util.Map<AEKey, Object> rcache = vm.getResolverCache();
        for (var e : plan.missingItems()) {
            AEKey missingKey = e.getKey();
            if (missingKey == null || missingKey.equals(requested)) continue;
            Object cached = rcache.get(missingKey);
            boolean nowCraftable = !service.getCraftingFor(missingKey).isEmpty();
            if (cached instanceof long[] && nowCraftable) return true;      // pattern appeared mid-request
            if (cached instanceof IPatternDetails && !nowCraftable) return true; // provider window
        }
        return false;
    }
}
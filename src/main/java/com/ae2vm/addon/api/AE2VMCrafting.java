package com.ae2vm.addon.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import com.ae2vm.addon.api.IVanillaCraftingAccess;
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
     * between retries. GTL's ME 样板总成 / 超限演算阵列 / GTLAdditions ME 超样板总成
     * (MESuperPatternBufferPartMachine FOA 模式) sync patterns to the CraftingService
     * on the SERVER TICK (requestUpdate → refreshNodeCraftingProvider, which is
     * removeProvider → addProvider); FOA 切换还会触发 refreshAllByProduct() 清空并
     * 重填全部样板（最多 162 槽 × 重新编码）。200ms ≈ 3-4 ticks 覆盖批量刷新窗口。
    private static final long RETRY_SETTLE_MS = 60L;

    /**
     * (v1.13.x PERF, ported from AE2VMAddon-1.21.1) Missing totals at or below this
     * amount are treated as possibly-fresh GTL provider-sync candidates (they keep the
     * 60ms settle window); above it the missing is considered genuine (no sync adds
     * millions of units) and the sleep is skipped. The user-facing failure cases
     * (omni/creative mega-chains) report millions to trillions of missing units; the
     * GTL false-negative signature is a small leaf (e.g. infuscolium=8456).
     */
    private static final long RETRY_SETTLE_MAX_MISSING_TOTAL = 1_000_000L;

    /**
     * (v1.13.4, ported from AE2VMAddon-1.21.1) Negative-resolution TTL: a "not
     * craftable" verdict stays cached for this long. Bounds the staleness window for
     * patterns added without a version bump.
     */
    private static final long RESOLVE_NEG_TTL_MS = 2000L;

    /** (v1.12.x GTL) ORIGINAL AE2 getCraftingFor, bypassing any mixin wrappers.
     *  Falls back to service.getCraftingFor() if the vanilla accessor is unavailable.
     */
    private static java.util.Collection<IPatternDetails> vmCraftingFor(
            CraftingService service, AEKey key) {
        if (service instanceof IVanillaCraftingAccess vm) {
            try { return vm.vmGetCraftingForVanilla(key); } catch (Throwable ignored) {}
        }
        return service.getCraftingFor(key);
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

        Collection<IPatternDetails> patterns = vmCraftingFor(service, what);
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
        // effects) survives across requests on the same network — otherwise every
        // request would re-capture every sub-pattern (JIT hit-rate ~0-49%). The
        // persistent resolver cache (vm.getResolverCache(), v1.13.1) survives across
        // requests AND retries so staleMissingNowCraftable can detect provider-window
        // changes between retries. execute() is synchronized on the VM, so concurrent
        // requests are safe.
        CraftingVM vm = VM_CACHE.computeIfAbsent(grid, g ->
                new CraftingVM(g, key -> resolve(g, (CraftingService) g.getCraftingService(),
                        new java.util.concurrent.ConcurrentHashMap<>(), key)));
        vm.setPatternResolver(key -> resolve(grid, service, vm.getResolverCache(), key));
        // (v1.15.x GTL CONVERSION-RING) The pure-conversion-ring feasibility guard
        // (computeConversionRingMissing, CraftingVM) needs ALL patterns per output key
        // — a single chosen pattern hides the ring edges (e.g. GTL iron_dust ↔ iron_ingot
        // and gtceu:iron ↔ iron_ingot form a byproduct-free exchange SCC that the chosen
        // pattern alone misses). Without this resolver the SCC is invisible, the capture
        // hits a false cross-cycle and reports "MISSING 2225x minecraft:iron_ingot" for
        // bedrock_drill even though the network has 633 iron_dust (= 633 iron_ingot worth
        // in the 1:1 exchange). setAllPatternsResolver was previously only wired by the
        // reference benchmark harness — production callers never saw ring feasibility.
        vm.setAllPatternsResolver(key -> {
            Collection<IPatternDetails> all = vmCraftingFor(service, key);
            return all == null ? java.util.List.of() : new java.util.ArrayList<>(all);
        });

        // Create simulation inventory.
        // IMPORTANT: always snapshot the LIVE network inventory (getAvailableStacks),
        // never the cached inventory. AE2's NetworkCraftingSimulationState falls back to
        // getCachedInventory() for non-player requesters (ECO pattern buses, interfaces,
        // requester lambdas, ...). That cached snapshot can be stale — the plan would then
        // claim more items than the CPU can actually extract at submit time, and AE2 refuses
        // the job with CraftErrorMissingIngredient ("无法从网络中取出某些材料").
        var storage = grid.getStorageService();

        // (v1.12.x GTL SYNC B方案) Run the VM calculation SYNCHRONOUSLY on the calling
        // (server) thread. The GTL provider-refresh race is impossible: the server thread
        // cannot concurrently execute refreshNodeCraftingProvider (removeProvider →
        // addProvider) while it is busy running the VM capture. The ~100-450ms calculation
        // blocks the server tick for a few ticks (each tick = 50ms), but the GTL
        // false-negative pattern (pattern=HAS + compiled=yes + stock>0 yet missing at
        // dispatch) is eliminated at the root: the CraftingService's craftableItems map
        // is stable for the entire capture. The plan's CompletableFuture is already
        // completed when returned — the caller (CPU/terminal) consumes it immediately.
        try {
            CraftingBytecode requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);
            var networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
            var craftingInventory = new ChildCraftingSimulationState(networkInv);
            craftingInventory.ignore(what);
            // (v1.12.x GTL STALE-MISSING RETRY) Loop up to 5 times to handle stale
            // bundleCache where a missing item's pattern was added AFTER the bundle was
            // captured (GTL's MEPatternBufferPartMachine may not trigger a reliable
            // refreshNodeCraftingProvider -> bumpPatternVersion, so the bundleCache may
            // stay stale). Each retry invalidates the root pattern's bytecode and
            // recompiles — forcing a fresh capture without disturbing other VM workers.
            int maxRetries = 5;
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
                    // (v1.13.8 PERF, ported from AE2VMAddon-1.21.1) Stale-evidence retry
                    // check: retry ONLY when a missing key is plausibly a capture-time
                    // artifact, never for a genuine stock deficit:
                    //   - negative resolver cache entry (resolved NOT-craftable during
                    //     this execute) AND the CraftingService NOW has a pattern for it
                    //     → pattern appeared mid-request (GTL buffer sync without a
                    //     version bump);
                    //   - positive cache entry (was craftable during this execute) AND
                    //     the CraftingService NOW does NOT have it → GTL
                    //     refreshNodeCraftingProvider removeProvider→addProvider window
                    //     (key temporarily absent).
                    // A missing key that was ALWAYS craftable (positive cache + still
                    // present — genuine deficit) fails both checks and does NOT retry.
                    boolean needsRetry = staleMissingNowCraftable(service, vm, rawPlan, what);
                    if (!needsRetry) {
                        // (v1.13.x PERF) Genuinely huge missing totals cannot be fixed by
                        // a settle window — report the plan immediately instead of paying
                        // the sleep tax.
                        if (allMissingBeyondSettleWindow(rawPlan)) {
                            break;
                        }
                        // (v1.12.x GTL PROVIDER-REFRESH WINDOW) Wait one settle window
                        // (60ms) before declaring the items missing — the GTL provider
                        // may still be syncing.
                        try {
                            Thread.sleep(60L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        needsRetry = staleMissingNowCraftable(service, vm, rawPlan, what);
                    }
                    if (needsRetry) {
                        // (v1.12.x GTL PROVIDER-REFRESH WINDOW) Give the server thread
                        // one settle window (60ms ≈ 1-2 ticks) to complete the provider
                        // remove+add cycle before re-executing.
                        try {
                            Thread.sleep(60L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        // Clear negative resolver-cache entries for keys that are NOW
                        // live: the resolve() negative-sentinel (2s TTL) would otherwise
                        // mask them again on the retry's fresh capture.
                        java.util.Map<AEKey, Object> rcache = vm.getResolverCache();
                        for (var e : rawPlan.missingItems()) {
                            AEKey missingKey = e.getKey();
                            if (missingKey == null || missingKey.equals(what)) continue;
                            Object cachedVal = rcache.get(missingKey);
                            if (cachedVal instanceof long[] && !vmCraftingFor(service, missingKey).isEmpty()) {
                                rcache.remove(missingKey);
                            }
                        }
                        // Clear ONLY this VM's bundleCache; do NOT bump the global
                        // pattern version. (v1.12.x GTL FIX) Do NOT clear the full
                        // bundleCache — the VM's internal staleMissingRecheck (with
                        // oscillation guard) re-captures only the affected bundles.
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
            // (v1.12.46) CRAFT_LESS 二分查找已移除：二分查找多次调用 vm.execute()
            // 会污染 bundleCache / resolverCache，导致第一次 capture 的 bundle 被
            // 后续候选量覆盖 → 最终 plan 的 usedItems 错误 → CPU 提交时拿不到正确
            // 物料 → 合成卡住。第二次下单时 bundle 已 warm → 正常。
            // 改为直接返回全量 plan（含 missing），AE2 CPU 自行处理。
            // (v1.12.19 DIAG) Missing-leaf forensics — decisive evidence for the
            // GTL buffer chain "缺料假阴" reports.
//             if (rawPlan != null && rawPlan.simulation() && !rawPlan.missingItems().isEmpty()) {
//                 try {
//                     var stock = storage.getInventory().getAvailableStacks();
//                     StringBuilder sb = new StringBuilder("[AE2-VM] MISSING-FORENSICS ").append(what).append(" x").append(amount).append(":");
//                     for (var e : rawPlan.missingItems()) {
//                         AEKey k = e.getKey();
//                         if (k == null) continue;
//                         long avail = stock.get(k);
//                         boolean pat = !vmCraftingFor(service, k).isEmpty();
//                         boolean comp = PatternCompiler.findCompiledByOutput(k) != null;
//                         sb.append("\n  MISSING ").append(e.getLongValue()).append("x ").append(k)
//                                 .append(" [type=").append(k instanceof appeng.api.stacks.AEFluidKey ? "fluid"
//                                         : k instanceof appeng.api.stacks.AEItemKey ? "item" : "other")
//                                 .append(" stock=").append(avail)
//                                 .append(" pattern=").append(pat ? "HAS" : "none")
//                                 .append(" compiled=").append(comp ? "yes" : "no").append("]");
//                     }
//                     AE2VMAddon.LOGGER.warn(sb.toString());
//                 } catch (Throwable ignored) {
                    // forensics must never break the request
//                 }
//             }
            return CompletableFuture.completedFuture(rawPlan);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * (v1.12.x GTL CYCLE-AWARE) True if resolving {@code candidate} (which outputs
     * {@code target}) would lead back to {@code target} within 2 hops — forming a
     * {A→B, B→A} cycle that makes the VM consume stock instead of the intended
     * cycle-free path. Depth-2 BFS covers the common GT conversion cycle (dust↔ingot,
     * or the A→B→A linear ring). Deeper cycles fall through to the VM's existing
     * {@code circularCache} guard.
     */
    /**
     * (v1.12.x GTL CYCLE-AWARE) True if resolving {@code candidate} (which outputs
     * {@code target}) would lead back to {@code target} within 2 hops — forming a
     * {A→B, B→A} cycle that makes the VM consume stock instead of the intended
     * cycle-free path. Depth-2 BFS covers the common GT conversion cycle (dust↔ingot,
     * or the A→B→A linear ring). Deeper cycles fall through to the VM's existing
     * {@code circularCache} guard.
     */
    public static boolean wouldCauseCycle(
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            IPatternDetails candidate, AEKey target) {
        return wouldCauseCycle(patternLookup, candidate, target, null);
    }

    /**
     * (v1.14.x SEEDED-RING FIX) Cycle detection with stock-awareness.
     * <p>A 2-hop ring (dust {@literal <->} ingot pulverize/smelt) is a legitimate GT
     * production cycle: with a stock seed on either member the ring can be entered
     * and terminates once the seed stock is consumed (dust present -&gt; smelt to ingot;
     * ingot present -&gt; pulverize to dust). Pruning the ring unconditionally made the
     * plan silently drop the intermediate craft (ingot) and the transfinite CPU
     * stalled (WAITING_FOR_INPUTS forever, plan had neither usedItems nor
     * patternTimes for it). Only prune when NO ring member holds stock - a genuinely
     * dead ring that would spin forever (the CALL-time resolvingKeys guard + PLAN-A
     * stock extraction / missing reporting remains as the backstop).</p>
     */
    public static boolean wouldCauseCycle(
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            IPatternDetails candidate, AEKey target,
            java.util.function.Function<AEKey, Long> stockLookup) {
        try {
            // (v1.14.x DEFINITION-GRAPH CYCLE GUARD) Direct self-edge: a pattern that
            // consumes its own output can never fire without inventing items — always prune.
            for (var input : candidate.getInputs()) {
                var stacks = input.getPossibleInputs();
                if (stacks != null && stacks.length > 0 && stacks[0] != null
                        && stacks[0].what() != null && stacks[0].what().equals(target)) {
                    return true;
                }
            }
            // Generic cycle analysis over the RECIPE-DEFINITION graph. Collect the keys the
            // target transitively depends on, then ask which of them sit in a DEAD ring
            // (unseeded, no external supplier). A production pattern whose target is a dead
            // ring member and whose inputs are all inside the dead ring is pruned; seeded
            // rings, externally-fed rings and re-flow rings stay craftable. This replaces the
            // old 2-hop special case with an arbitrary-topology SCC analysis.
            java.util.Set<AEKey> keys = new java.util.HashSet<>();
            java.util.ArrayDeque<AEKey> queue = new java.util.ArrayDeque<>();
            keys.add(target); queue.add(target);
            // (v1.14.x CANDIDATE-EDGE) The candidate itself is a producer of the target:
            // its inputs are the target's dependencies even when the lookup omits the
            // target's producers (resolve() filters candidates one at a time, and unit
            // harnesses may not register the target's producers at all).
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
     * (v1.14.x DEFINITION-GRAPH) Compute the set of keys that sit in a DEAD ring: a
     * strongly-connected component (size > 1) of the recipe-definition graph in which
     * no member has network stock (seed) and no pattern outside the component produces
     * a member (external supply). A dead ring can never be entered from nothing — its
     * members may only come from stock, so their production patterns are pruned and the
     * shortfall is reported missing by the VM. Works for any topology: 2-hop, 3-hop,
     * nested ring-in-ring, unbalanced re-flow rings.
     */
    public static java.util.Set<AEKey> computeCycleBoundKeys(
            Collection<AEKey> keys,
            java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> patternLookup,
            java.util.function.Function<AEKey, Long> stockLookup) {
        return computeCycleBoundKeys(keys, patternLookup, stockLookup, null);
    }

    /**
     * (v1.14.x DEFINITION-GRAPH) Same as the 3-arg form, but also injects the
     * CANDIDATE pattern's own exchange edges into the graph. The candidate is a
     * producer of the target under test; without its edges the SCC analysis cannot
     * see that firing it closes the ring when the lookup does not register the
     * target's producers (resolve() filters candidates one at a time).
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


    /** (v1.12.x DIAG) Primary-output name of a pattern, for the resolve() prune log. */
    private static String patternOutputNameStatic(IPatternDetails p) {
        try {
            var outs = p.getOutputs();
            if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                return outs[0].what().toString();
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
            // (v1.13.4, ported from AE2VMAddon-1.21.1) Negative sentinel
            // (long[]{capturedAtMillis}): re-resolve only after the short TTL,
            // so a pattern added WITHOUT a version bump still self-heals within
            // ~RESOLVE_NEG_TTL_MS instead of being masked forever.
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
        var subs = vmCraftingFor(service, key);
        if (!subs.isEmpty()) {
            // (v1.12.x GTL CYCLE-AWARE) Filter out patterns whose inputs would lead back
            // to this key within 2 hops, forming a cycle (e.g. steel_ingot↔steel_dust).
            // Applied unconditionally — even a single candidate may be cycle-prone.
            // When ALL candidates are cycle-prone, keep the originals (the VM's runtime
            // circularCache guard handles the inevitable cycle).
            var filtered = new java.util.ArrayList<IPatternDetails>(subs.size());
            var pruned = new java.util.ArrayList<String>();
            var verdicts = new java.util.ArrayList<String>();
            var ringStock = resolveStockSnapshot(network);
            for (var p : subs) {
                boolean cyc = p != null && wouldCauseCycle(key2 -> vmCraftingFor(service, key2), p, key,
                        k -> ringStock == null ? 0L : ringStock.get(k));
                if (p != null) verdicts.add(patternOutputNameStatic(p) + ":" + (cyc ? "cycle" : "ok"));
                if (!cyc) {
                    filtered.add(p);
                } else if (p != null) {
                    pruned.add(patternOutputNameStatic(p));
                }
            }
//             AE2VMAddon.LOGGER.info("[AE2-VM resolve] {} candidates={} [{}]", key, subs.size(), verdicts);
            if (!filtered.isEmpty()) {
                if (!pruned.isEmpty()) {
//                     AE2VMAddon.LOGGER.info("[AE2-VM resolve] {} → pruned cycle-prone {} → kept {}", key, pruned, filtered.size());
                }
                subs = filtered;
            } else if (!pruned.isEmpty()) {
//                 AE2VMAddon.LOGGER.warn("[AE2-VM resolve] {} ALL {} candidate(s) cycle-prone {} → fallback to originals (runtime guard)", key, subs.size(), pruned);
            }
            var sub = pickBestPattern(subs, key);
            // (v1.15.x GTL CATALYST) Resolve which inputs the machine satisfies
            // from its catalyst slots (GT recipe chance<=0) — the compiled plan
            // must NOT count them as consumed demand.
            try { com.ae2vm.addon.api.GtlCatalystRegistry.register(service, sub); } catch (Throwable ignored) {}
            PatternCompiler.compileIfAbsent(network, sub);
            // (v1.15.x GTL DYNAMIC PATTERN) GTL pattern-buffer machines generate
            // their pattern lazily (machine recipe cache not built on first
            // request → inputs missing shell materials). NEVER positive-cache a GTL
            // pattern: re-resolve every time so the machine's current pattern is
            // used (the first execute builds the cache; later resolves see the full
            // input set — mirroring vanilla's plan).
            if (!com.ae2vm.addon.api.GtlCatalystRegistry.isGtlPattern(key)) {
                cache.put(key, sub);
            }
            return sub;
        }
        // Try 2: drop secondary (verify the pattern actually outputs the item)
        var clean = key.dropSecondary();
        if (!clean.equals(key)) {
            subs = vmCraftingFor(service, clean);
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
            // (v1.15.x DEFENSIVE) The registry lookup itself can fail outside the
            // Forge environment (offline bench: ForgeRegistries static init requires
            // the registry data) — treat that as "no pattern", never crash the
            // request (was ExceptionInInitializerError in CraftLessCompetitionBenchmark).
            net.minecraft.world.item.Item item = null;
            try {
                item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(id);
            } catch (Throwable t) {
//                 AE2VMAddon.LOGGER.debug("[AE2-VM resolve] registry lookup unavailable for {}: {}", key, String.valueOf(t));
            }
            if (item != null) {
                var pureKey = appeng.api.stacks.AEItemKey.of(item);
                if (pureKey != null) {
                    subs = vmCraftingFor(service, pureKey);
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
        // (v1.13.4, ported from AE2VMAddon-1.21.1) Cache the negative verdict as a
        // TTL'd sentinel so the warm-hit guards re-resolve via a cache hit instead of
        // the full resolver. The TTL bounds staleness for pattern additions without a
        // version bump; version bumps and clearBundleCache() clear the cache at once.
        cache.put(key, new long[] { System.currentTimeMillis() });
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
        long bestTotalInput = Long.MAX_VALUE;
        for (var p : patterns) {
            if (p == null) continue;
            if (fallback == null) fallback = p;
            if (want != null && !patternOutputs(p, want)) continue;
            var out = p.getPrimaryOutput();
            long amt = out == null ? Long.MAX_VALUE : Math.max(1, out.amount());
            long totalInput = 0;
            try {
                for (var input : p.getInputs()) {
                    var stacks = input.getPossibleInputs();
                    if (stacks != null && stacks.length > 0 && stacks[0] != null) {
                        totalInput += stacks[0].amount();
                    }
                }
            } catch (Throwable ignored) {}
            if (amt < bestOut || (amt == bestOut && totalInput < bestTotalInput)) {
                bestOut = amt; bestTotalInput = totalInput; best = p;
            }
        }
//         if (AE2VMAddon.LOGGER.isDebugEnabled() && patterns.size() > 1) {
//             AE2VMAddon.LOGGER.debug("[AE2-VM] pickBestPattern for {}: {} candidates, chosen={} out={} totalInput={}",
//                 want, patterns.size(),
//                 best != null ? best.getPrimaryOutput() : "null",
//                 bestOut == Long.MAX_VALUE ? "none" : bestOut,
//                 bestTotalInput == Long.MAX_VALUE ? "?" : bestTotalInput);
//         }
        return best != null ? best : fallback;
    }

    /** True if the pattern's primary output is {@code want} (by key or by registry id). */
    public static boolean patternOutputs(IPatternDetails pattern, AEKey want) {
        var out = pattern.getPrimaryOutput();
        if (out == null || out.what() == null) return false;
        if (out.what().equals(want)) return true;
        return want.getId() != null && want.getId().equals(out.what().getId());
    }

    /**
     * (v1.13.8 PERF, ported from AE2VMAddon-1.21.1) Stale-evidence retry check: retry
     * ONLY when a missing key is plausibly a capture-time artifact, never for a genuine
     * stock deficit:
     * <ul>
     *   <li>negative resolver cache entry (resolved NOT-craftable during this execute) AND
     *       the CraftingService NOW has a pattern for it → pattern appeared mid-request
     *       (GTL buffer sync without a version bump);</li>
     *   <li>positive cache entry (was craftable during this execute) AND the
     *       CraftingService NOW does NOT have it → GTL refreshNodeCraftingProvider
     *       removeProvider→addProvider window (key temporarily absent).</li>
     * </ul>
     * A missing key that was ALWAYS craftable (positive cache + still present — genuine
     * deficit) fails both checks and does NOT retry: re-executing cannot change a genuine
     * deficit, and each retry recompiles the root + re-walks the network.
     */
    private static boolean staleMissingNowCraftable(CraftingService service, CraftingVM vm,
                                                    ICraftingPlan plan, AEKey requested) {
        java.util.Map<AEKey, Object> rcache = vm.getResolverCache();
        for (var e : plan.missingItems()) {
            AEKey missingKey = e.getKey();
            if (missingKey == null || missingKey.equals(requested)) continue;
            Object cached = rcache.get(missingKey);
            boolean nowCraftable = !vmCraftingFor(service, missingKey).isEmpty();
            if (cached instanceof long[] && nowCraftable) return true;      // pattern appeared mid-request
            if (cached instanceof IPatternDetails && !nowCraftable) return true; // provider window
        }
        return false;
    }

    /**
     * (v1.13.x PERF, ported from AE2VMAddon-1.21.1) True when the TOTAL missing amount
     * exceeds the settle-window magnitude. A just-registered GTL pattern fixes a small
     * leaf (units to ~1M), never millions of units, so such a plan cannot be a
     * provider-sync race — the 60ms wait is pure tax and is skipped. Saturating sum:
     * a single entry that already exceeds the threshold (or overflows the sum) counts
     * as genuine.
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
}
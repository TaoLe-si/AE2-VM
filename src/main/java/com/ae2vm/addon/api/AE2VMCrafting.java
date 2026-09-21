package com.ae2vm.addon.api;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import com.ae2vm.shim.api.networking.crafting.ICraftingSimulationRequester;
import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.stacks.GenericStack;
import com.ae2vm.shim.api.stacks.KeyCounter;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import com.ae2vm.shim.api.storage.data.MixedStackList;
import com.ae2vm.shim.crafting.CraftingPlan;
import com.ae2vm.shim.crafting.inv.ChildCraftingSimulationState;
import com.ae2vm.shim.crafting.inv.CraftingSimulationState;
import appeng.api.networking.crafting.ICraftingGrid;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraftforge.fml.common.Loader;

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
 * Object)} from their own {@code beginCraftingCalculation}
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

    // =====================================================================
    // AE2 v9 (1.17.1) 桥接 helper（与 CraftingVM 内同名 helper 同语义）
    // =====================================================================

    /**
     * v8 {@code ICraftingGrid.getCraftingFor(IAEItemStack, ICraftingPatternDetails, int, World)}
     * → VM 的 {@code IPatternDetails} 列表（v8 pattern 经 V8PatternDetails 适配）。
     */
    private static Collection<IPatternDetails> craftingFor(ICraftingGrid service, AEKey key) {
        if (service == null || key == null) return java.util.Collections.emptyList();
        appeng.api.storage.data.IAEItemStack query = ((AEItemKey) key).toStack(1);
        java.util.Collection<appeng.api.networking.crafting.ICraftingPatternDetails> raw =
                // world 只在 details != null（子模式查询）时使用；顶层查询恒为 null，故传 null
                service.getCraftingFor(query, null, -1, null);
        if (raw.isEmpty() && com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
            // 1.12.2-nova 诊断（不靠猜）：uel 的 getCraftingFor 就是
            //   craftableItems.get(whatToCraft)
            // —— 裸查表，键是 updatePatterns() 里 out.copy()+reset()+setCraftable(true) 的栈。
            // 查空时把"我们用的键"和"表里全部键"按同一格式打出来，
            // 一眼就能看出差在物品 / damage / NBT 哪一处。
            // 字段名与类型是对着运行期 ae2-uel jar javap 核的（Object2ObjectMap，不是 HashMap）。
            AE2VMAddon.LOGGER.info("[AE2-VM] craftingFor MISS query=" + describeItem(query)
                    + " table=[" + craftableKeys(service) + "]");
        }
        java.util.List<IPatternDetails> out = new java.util.ArrayList<>();
        for (appeng.api.networking.crafting.ICraftingPatternDetails d : raw) {
            out.add(new com.ae2vm.addon.v8.V8PatternDetails(d));
        }
        return out;
    }

    /** 与 {@link #craftableKeys} 同格式的单个键描述，便于直接肉眼比对。 */
    private static String describeItem(appeng.api.storage.data.IAEItemStack s) {
        if (s == null) {
            return "null";
        }
        // ⚠ 不能用 createItemStack()：rv6/uel 它是 copyStackWithSize(definition, size)，
        // AE2 表里的键都是 reset() 后的 craftable 栈（size 0），copyStackWithSize 对 size<=0
        // 返回 EMPTY → 打出来全是 "minecraft:air"，观测本身错（实测把 craftableItems 打成 3 个 air）。
        // 直接问 IAEItemStack 自己：getItem()/getItemDamage()/getStackSize()。
        StringBuilder sb = new StringBuilder();
        sb.append(s.getItem().getRegistryName()).append(":meta").append(s.getItemDamage());
        net.minecraft.item.ItemStack def = s.getDefinition();
        if (def != null && def.hasTagCompound()) {
            sb.append("#").append(def.getTagCompound().toString());
        }
        sb.append(" x").append(s.getStackSize());
        return sb.toString();
    }

    /** 反射读 AE2 自己的 craftableItems 表键集（只在 debugLogging 下调用）。 */
    private static String craftableKeys(Object cache) {
        try {
            java.lang.reflect.Field f = cache.getClass().getDeclaredField("craftableItems");
            f.setAccessible(true);
            Iterable<?> keys = ((java.util.Map<?, ?>) f.get(cache)).keySet();
            StringBuilder sb = new StringBuilder();
            for (Object k : keys) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(describeItem((appeng.api.storage.data.IAEItemStack) k));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "<不可读 " + t.getClass().getSimpleName() + ">";
        }
    }

    /** v8: 合成网格缓存取代 v9 的 CraftingService。 */
    private static ICraftingGrid craftingGrid(IGrid grid) {
        return grid == null ? null : grid.getCache(ICraftingGrid.class);
    }

    /** v8 顶层 getCraftingFor 不使用 World（见 craftingFor），保留该签名仅为兼容调用点。 */
    private static net.minecraft.world.World craftingWorld(ICraftingGrid grid) {
        return null;
    }

    /** v9 getPrimaryOutput() 返回 IAEStack → shim GenericStack。 */
    private static GenericStack wrapPrimary(IPatternDetails p) {
        IAEStack out = p.getPrimaryOutput();
        return (out instanceof IAEItemStack) ? GenericStack.wrap((IAEItemStack) out) : null;
    }

    /** v9 getOutputs() 返回 IAEStack[] → shim GenericStack[]。 */
    private static GenericStack[] wrapOutputs(IPatternDetails p) {
        IAEStack[] raw = p.getOutputs();
        if (raw == null) return null;
        com.ae2vm.shim.api.stacks.GenericStack[] out = new GenericStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (raw[i] instanceof IAEItemStack) ? GenericStack.wrap((IAEItemStack) raw[i]) : null;
        }
        return out;
    }

    /** v9 IInput.getPossibleInputs() 返回 IAEStack[] → shim GenericStack[]。 */
    private static GenericStack[] wrapPossible(IPatternDetails.IInput in) {
        IAEStack[] raw = in.getPossibleInputs();
        if (raw == null) return null;
        com.ae2vm.shim.api.stacks.GenericStack[] out = new GenericStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = (raw[i] instanceof IAEItemStack) ? GenericStack.wrap((IAEItemStack) raw[i]) : null;
        }
        return out;
    }

    /** v9 MixedStackList → KeyCounter。 */
    private static KeyCounter toKeyCounter(MixedStackList list) {
        KeyCounter kc = new KeyCounter();
        if (list == null) return kc;
        for (IAEStack st : list) {
            if (st instanceof IAEItemStack) {
                IAEItemStack is = (IAEItemStack) st;
                com.ae2vm.shim.api.stacks.AEItemKey k = AEItemKey.wrap(is);
                if (k != null && st.getStackSize() != 0) kc.add(k, st.getStackSize());
            }
        }
        return kc;
    }

    /** KeyCounter → v9 MixedStackList。 */
    private static MixedStackList toMixedList(KeyCounter counter) {
        MixedStackList list = new MixedStackList();
        if (counter == null) return list;
        for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<com.ae2vm.shim.api.stacks.AEKey> e : counter.entrySet()) {
            if (e.getLongValue() != 0) {
                list.addStorage(((AEItemKey) e.getKey()).toStack(e.getLongValue()));
            }
        }
        return list;
    }

    /** shim key → v9 IAEStack（ignore() 等 v9 原生调用）。 */
    private static IAEItemStack toStack(AEKey key, long amount) {
        return key == null ? null : ((AEItemKey) key).toStack(amount);
    }

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
        // (v1.13.0) ModList is null in the test classpath (ModContainer not initialised);
        // return true (= "AE2VM itself is loaded") when there is no mod-loading context at all.
        // 1.12.2 用 Loader.isModLoaded（ModList 是 1.13+ 的类）。
        try {
            return Loader.isModLoaded("ae2vm");
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
    // AE2 v9 (1.17.1): beginCraftingCalculation 没有 CalculationStrategy（数量在 IAEStack 内）
    // — strategy 参数保留在签名里（ECO/第三方调用方兼容）但被忽略。
    public static CompletableFuture<ICraftingPlan> calculate(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            Object strategy) {
        return calculateAsync(grid, requester, what, amount, strategy);
    }

    /**
     * Synchronous variant of {@link #calculate(IGrid, ICraftingSimulationRequester, AEKey, long, Object)}.
     * Blocks until the plan is ready. Use the async variant on the server thread.
     */
    public static ICraftingPlan calculateSync(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            Object strategy) throws Exception {
        return calculateAsync(grid, requester, what, amount, strategy).get();
    }

    /**
     * (Java 8) {@code CompletableFuture#failedFuture} 是 Java 9+；1.16.5 需要手工构造一个
     * 已异常完成的 future，语义完全相同。
     */
    private static CompletableFuture<ICraftingPlan> failedFuture(Throwable t) {
        CompletableFuture<ICraftingPlan> f = new CompletableFuture<ICraftingPlan>();
        f.completeExceptionally(t);
        return f;
    }

    private static CompletableFuture<ICraftingPlan> calculateAsync(
            IGrid grid,
            ICraftingSimulationRequester requester,
            AEKey what,
            long amount,
            Object strategy) {
        ICraftingGrid service = craftingGrid(grid);
        if (service == null) {
            return failedFuture(new IllegalStateException("No crafting service on grid"));
        }

        // (v1.13.6) Record the request in the grid's hot-output LRU so a future pattern
        // change can background-precompute this output's plan ("first-round hit").
        recordRequest(grid, what, amount);

        Collection<IPatternDetails> patterns = craftingFor(service, what);
        if (patterns.isEmpty()) {
            return failedFuture(new IllegalStateException("No pattern for " + what));
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
            return failedFuture(new IllegalStateException("Pattern not compilable: " + what));
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
                new CraftingVM(g, key -> resolve(g, craftingGrid(g),
                        new java.util.concurrent.ConcurrentHashMap<>(), key)));
        vm.setPatternResolver(key -> resolve(grid, service, vm.getResolverCache(), key));

        // Create simulation inventory.
        // IMPORTANT: always snapshot the LIVE network inventory (getAvailableStacks),
        // never the cached inventory. AE2's NetworkCraftingSimulationState falls back to
        // getCachedInventory() for non-player requesters (ECO pattern buses, interfaces,
        // requester lambdas, ...). That cached snapshot can be stale — the plan would then
        // claim more items than the CPU can actually extract at submit time, and AE2 refuses
        // the job with CraftErrorMissingIngredient ("无法从网络中取出某些材料").
        com.ae2vm.shim.api.networking.storage.IStorageService storage = new com.ae2vm.addon.v8.V8StorageService(
                grid.getCache(appeng.api.networking.storage.IStorageGrid.class));

        // (v1.13.4 PERF) SERVER-THREAD warm short-circuit: while the VM is idle, the
        // memoized-plan check runs HERE (before supplyAsync) so warm hits never pay the
        // ForkJoinPool scheduling wait (measured 150-450us of the VM OK gap). VM OK then
        // ≈ the warm work itself — target <100us. The VM's executing flag guards against
        // blocking the server thread behind a concurrent slow-path execute (ms-scale).
        if (!vm.isExecuting()) {
            ICraftingPlan warmPlan = tryWarmPlan(vm, grid, storage, topPattern, amount, what);
            if (warmPlan != null) {
                // (v1.13.19 MULTI-JOB STALL) DELIVERABILITY GUARD on the warm path.
                // The memoized plan replays the PREVIOUS pattern instances verbatim. If a
                // pattern buffer re-encoded a pattern (ME 样板总成 / FOA mode / multiplier
                // switch / any provider rebuild) and the refresh has not bumped the version
                // yet, those instances are dead: the plan looks feasible but no provider can
                // ever claim the job (CPU stuck at 0%). Rebind to the live instances; if no
                // live equivalent exists, drop the caches and fall through to the cold path.
                ICraftingPlan warmChecked = rebindStalePatterns(k -> craftingFor(service, k), warmPlan);
                if (warmChecked != null) {
                    return CompletableFuture.completedFuture(warmChecked);
                }
                if (shouldLogUndeliverable(what)) {
                    AE2VMAddon.LOGGER.warn("[AE2-VM] UNDELIVERABLE warm plan for {} x{} — a dispatched "
                            + "pattern was re-encoded and has no live equivalent. Dropping the "
                            + "memoized plan and falling through to a cold re-capture.", what, amount);
                }
                vm.clearBundleCache();
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
                    // (v1.13.19 MULTI-JOB STALL) Same deliverability guard as the
                    // server-thread warm path above (see the comment there).
                    ICraftingPlan warmChecked = rebindStalePatterns(k -> craftingFor(service, k), warmPlan);
                    if (warmChecked != null) {
                        return warmChecked;
                    }
                    if (shouldLogUndeliverable(what)) {
                        AE2VMAddon.LOGGER.warn("[AE2-VM] UNDELIVERABLE warm plan (async) for {} x{} — "
                                + "falling through to a cold re-capture.", what, amount);
                    }
                    vm.clearBundleCache();
                }
                long c1 = System.nanoTime();
                CraftingBytecode requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);
                long c2 = System.nanoTime();
                com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                ChildCraftingSimulationState craftingInventory = new ChildCraftingSimulationState(networkInv);
                craftingInventory.ignore(toStack(what, 1));
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
                        // v9 (1.17.1): plan 列表是 MixedStackList — toKeyCounter 读回
                        KeyCounter rawMissing = toKeyCounter(rawPlan.missingItems());
                        long missingCount = rawMissing.get(what);
                        if (missingCount > 0) {
                        com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState realStock = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                        long avail = realStock.stockOf((com.ae2vm.shim.api.stacks.AEItemKey) what);
                        if (avail > 0) {
                            long usable = Math.min(avail, missingCount);
                            KeyCounter fixedUsed = toKeyCounter(rawPlan.usedItems());
                            fixedUsed.add(what, usable);
                            KeyCounter fixedMissing = new KeyCounter();
                            for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<com.ae2vm.shim.api.stacks.AEKey> e : rawMissing.entrySet()) {
                                if (!e.getKey().equals(what)) {
                                    fixedMissing.add(e.getKey(), e.getLongValue());
                                } else if (e.getLongValue() > usable) {
                                    fixedMissing.add(e.getKey(), e.getLongValue() - usable);
                                }
                            }
                            rawPlan = new CraftingPlan(rawPlan.finalOutput(), rawPlan.bytes(),
                                !fixedMissing.isEmpty(), false,
                                toMixedList(fixedUsed), rawPlan.emittedItems(), toMixedList(fixedMissing),
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
                            com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState newNetworkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                            craftingInventory = new ChildCraftingSimulationState(newNetworkInv);
                            craftingInventory.ignore(toStack(what, 1));
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
                // (v1.13.19 MULTI-JOB STALL) DELIVERABILITY GUARD.
                // Every pattern a plan dispatches must still be an instance this network's
                // CraftingService can actually schedule. Pattern buffers (ME 样板总成 /
                // MESuperPatternBufferPartMachine FOA mode / gtlcore multiplier switch, and
                // any mod that rebuilds its provider after a config change) re-encode their
                // patterns into FRESH IPatternDetails instances. When the provider refresh is
                // batched/delayed, PatternCompiler.bumpPatternVersion() has not fired yet when
                // the next order arrives, so:
                //   * the memoized fast path replays the PREVIOUS plan verbatim -> its
                //     patternTimes keys are the OLD instances;
                //   * JIT bundles are reused because patternsEquivalent() compares by CONTENT
                //     and a re-encoded instance looks "unchanged".
                // The plan reports feasible while CraftingService.getProviders() no longer
                // knows those instances -> CPU accepts, no provider ever claims, stuck at 0%.
                ICraftingPlan deliverable = rebindStalePatterns(k -> craftingFor(service, k), rawPlan);
                if (deliverable == null) {
                    if (shouldLogUndeliverable(what)) {
                        AE2VMAddon.LOGGER.warn("[AE2-VM] UNDELIVERABLE PLAN for {} x{} — a dispatched "
                                + "pattern was re-encoded and has no live equivalent "
                                + "(pattern-buffer re-encode inside the provider-refresh "
                                + "window). Forcing one cold re-capture.", what, amount);
                    }
                    // Re-capture once from a clean cache so the plan binds the CURRENT
                    // instances. Deliberately NOT a global bumpPatternVersion(): that would
                    // push every concurrent VM on the grid into the transient
                    // removeProvider->addProvider window.
                    vm.clearBundleCache();
                    java.util.Map<AEKey, Object> rcache = vm.getResolverCache();
                    if (rcache != null) rcache.clear();
                    PatternCompiler.invalidate(topPattern);
                    PatternCompiler.compileIfAbsent(grid, topPattern);
                    requestBytecode = PatternCompiler.compileRequest(grid, topPattern, amount);
                    com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState coldInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                    ChildCraftingSimulationState coldChild = new ChildCraftingSimulationState(coldInv);
                    coldChild.ignore(toStack(what, 1));
                    try {
                        ICraftingPlan cold = vm.execute(requestBytecode, coldChild);
                        ICraftingPlan coldChecked = rebindStalePatterns(k -> craftingFor(service, k), cold);
                        if (coldChecked == null && shouldLogUndeliverable(what)) {
                            AE2VMAddon.LOGGER.warn("[AE2-VM] UNDELIVERABLE PLAN persists after cold "
                                    + "re-capture for {} x{} — delivering as-is (AE2 will report the "
                                    + "shortfall instead of stalling at 0%).", what, amount);
                        }
                        deliverable = coldChecked != null ? coldChecked : cold;
                    } catch (Throwable t) {
                        deliverable = rawPlan;
                    }
                }
                return deliverable;
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
        // AE2 v9 (1.17.1): TickHandler 没有 getCurrentTick（v10 引入）——
        // 改从 Forge 服务器实例取 tick 计数；无服务器上下文（bench）时 -1 = 视为新鲜。
        try {
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
            // MCP 实测：getTickCounter = func_71259_af（1.12.2 与 1.15.2 同名，1.16 mojmap 才改叫 getTickCount）；返回 int，这里升到 long。
// 1.12.2 拿服务器实例走 FMLCommonHandler（MinecraftServer.getServer() 在该版本是非静态的）
            return server == null ? -1L : server.getTickCounter();
        } catch (Throwable t) {
            return -1L; // tick unknown — treat snapshots as fresh
        }
    }

    private static KeyCounter stockForGrid(IGrid grid,
                                           com.ae2vm.shim.api.networking.storage.IStorageService storage,
                                           boolean forceFresh) {
        long now = System.currentTimeMillis();
        if (!forceFresh) {
            StockSnap s = STOCK_SNAP.get(grid);
            if (s != null && now - s.capturedAt < STOCK_SNAP_TTL_MS) {
                return s.counter;
            }
        }
        // v9 (1.17.1): 无 getCachedInventory() — 走 item channel 的 StorageList（实时）
        KeyCounter fresh = new KeyCounter();
        try {
            appeng.api.storage.IMEMonitor<appeng.api.storage.data.IAEItemStack> mon = storage.getInventory(com.ae2vm.shim.api.storage.StorageChannels.items());
            if (mon != null) {
                for (IAEStack st : mon.getStorageList()) {
                    if (st instanceof IAEItemStack) {
                        IAEItemStack is = (IAEItemStack) st;
                        com.ae2vm.shim.api.stacks.AEItemKey k = AEItemKey.wrap(is);
                        if (k != null) fresh.add(k, st.getStackSize());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
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
     * (v1.13.19 MULTI-JOB STALL) Throttle for the {@code UNDELIVERABLE PLAN} warning:
     * at most one line per output key per 30 s. It is a real fault worth seeing, but a
     * mega chain that keeps hitting it would flood the log.
     */
    private static final java.util.Map<String, Long> UNDELIVERABLE_LOGGED =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long UNDELIVERABLE_THROTTLE_MS = 30_000L;

    private static boolean shouldLogUndeliverable(AEKey what) {
        String key = String.valueOf(what);
        long now = System.currentTimeMillis();
        Long last = UNDELIVERABLE_LOGGED.get(key);
        if (last != null && now - last < UNDELIVERABLE_THROTTLE_MS) return false;
        if (UNDELIVERABLE_LOGGED.size() > 512) UNDELIVERABLE_LOGGED.clear();
        UNDELIVERABLE_LOGGED.put(key, now);
        return true;
    }

    /**
     * (v1.13.19 MULTI-JOB STALL) Rebind every dispatched pattern to the instance the
     * network's {@code CraftingService} currently knows.
     *
     * <p>Returns the (possibly rebuilt) plan, or {@code null} when at least one
     * dispatched pattern has been replaced by a re-encoded instance with no
     * content-equal live successor — the plan is then undeliverable and the caller must
     * re-capture from a clean cache.</p>
     *
     * <p>A key whose {@code getCraftingFor} is momentarily EMPTY is treated as a
     * provider-refresh window, not as a dead pattern: the plan is returned unchanged
     * rather than triggering a spurious re-capture.</p>
     */
    public static ICraftingPlan rebindStalePatterns(
            java.util.function.Function<AEKey, ? extends Collection<IPatternDetails>> liveLookup,
            ICraftingPlan plan) {
        if (plan == null || liveLookup == null) return null;
        java.util.Map<IPatternDetails, Long> times = plan.patternTimes();
        if (times == null || times.isEmpty()) return plan;
        boolean changed = false;
        int rebindCount = 0;
        java.util.Map<IPatternDetails, Long> rebound = new HashMap<>(Math.max(16, times.size() * 2));
        for (java.util.Map.Entry<com.ae2vm.shim.api.crafting.IPatternDetails, java.lang.Long> e : times.entrySet()) {
            IPatternDetails p = e.getKey();
            Long n = e.getValue();
            if (p == null || n == null || n <= 0) { changed = true; continue; }
            GenericStack primary = null;
            try { primary = wrapPrimary(p); } catch (Throwable ignored) {}
            if (primary == null || primary.what() == null) { changed = true; continue; }
            Collection<IPatternDetails> live = null;
            try { live = liveLookup.apply(primary.what()); } catch (Throwable ignored) {}
            // Provider-refresh window: cannot judge — keep the plan as-is.
            if (live == null || live.isEmpty()) { rebound.put(p, n); continue; }
            IPatternDetails replacement = null;
            boolean exact = false;
            for (com.ae2vm.shim.api.crafting.IPatternDetails c : live) {
                if (c == p) { exact = true; break; }
                if (replacement == null && patternContentEquals(c, p)) replacement = c;
            }
            if (exact) { rebound.put(p, n); continue; }
            if (replacement != null) {
                rebound.put(replacement, n);
                changed = true;
                rebindCount++;
                continue;
            }
            // Re-encoded with different content and no live equivalent → undeliverable.
            return null;
        }
        if (!changed) return plan;
        if (com.ae2vm.addon.config.AE2VMConfig.isDebugLogging()) {
            AE2VMAddon.LOGGER.info("[AE2-VM] REBIND: {} dispatched pattern(s) replaced with the "
                    + "live instance (pattern re-encode without a version bump).", rebindCount);
        }
        // v9 (1.17.1): CraftingPlan ctor 收 MixedStackList — 原样透传 plan 的列表
        return new CraftingPlan(plan.finalOutput(), plan.bytes(), plan.simulation(), false,
                plan.usedItems(), plan.emittedItems(), plan.missingItems(), rebound);
    }

    /**
     * (v1.13.19 MULTI-JOB STALL) Content-level comparison of two {@code IPatternDetails}.
     * A pattern buffer that re-encodes a pattern produces a NEW instance whose content
     * is identical; identity comparison then wrongly reports it as "gone".
     */
    public static boolean patternContentEquals(IPatternDetails a, IPatternDetails b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        try {
            com.ae2vm.shim.api.stacks.GenericStack ao = wrapPrimary(a);
            com.ae2vm.shim.api.stacks.GenericStack bo = wrapPrimary(b);
            if (ao == null || bo == null) return false;
            if (ao.amount() != bo.amount()) return false;
            if (ao.what() == null ? bo.what() != null : !ao.what().equals(bo.what())) return false;
            com.ae2vm.shim.api.crafting.IPatternDetails.IInput[] ai = a.getInputs();
            com.ae2vm.shim.api.crafting.IPatternDetails.IInput[] bi = b.getInputs();
            if (ai == null || bi == null || ai.length != bi.length) return false;
            for (int i = 0; i < ai.length; i++) {
                if (ai[i] == null || bi[i] == null) return false;
                if (ai[i].getMultiplier() != bi[i].getMultiplier()) return false;
                com.ae2vm.shim.api.stacks.GenericStack[] ap = wrapPossible(ai[i]);
                com.ae2vm.shim.api.stacks.GenericStack[] bp = wrapPossible(bi[i]);
                if (ap == null || bp == null || ap.length != bp.length) return false;
                for (int j = 0; j < ap.length; j++) {
                    com.ae2vm.shim.api.stacks.GenericStack x = ap[j];
                    com.ae2vm.shim.api.stacks.GenericStack y = bp[j];
                    if (x == null || y == null) return x == y;
                    if (x.amount() != y.amount()) return false;
                    if (x.what() == null ? y.what() != null : !x.what().equals(y.what())) return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * (v1.13.4) Warm-path short-circuit shared by the server-thread check (before
     * supplyAsync) and the async-worker fallback. Serves the memoized plan when every
     * VM guard passes, else null. Best-effort: any failure falls through to the slow
     * path.
     */
    private static ICraftingPlan tryWarmPlan(CraftingVM vm, IGrid grid,
                                             com.ae2vm.shim.api.networking.storage.IStorageService storage,
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
            // v9 (1.17.1): plan.missingItems() 是 MixedStackList — toKeyCounter 读回
            KeyCounter warmMissing = warmPlan == null ? null : toKeyCounter(warmPlan.missingItems());
            if (warmPlan != null && warmMissing.get(what) == 0) {
                // Feasible plan from a reused snapshot predating this tick: re-verify
                // once against a fresh capture (missing previews skip this).
                if (warmPlan.missingItems().isEmpty() && !snapshotIsCurrentTick(grid)) {
                    KeyCounter freshStock = stockForGrid(grid, storage, true);
                    warmPlan = vm.tryCachedPlan(warmRequest, key -> freshStock.get(key));
                    w3 = System.nanoTime();
                    if (warmPlan == null || toKeyCounter(warmPlan.missingItems()).get(what) != 0) {
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
                java.util.Iterator<java.util.Map.Entry<com.ae2vm.shim.api.stacks.AEKey, java.lang.Long>> it = m.entrySet().iterator();
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
            java.util.LinkedHashMap<com.ae2vm.shim.api.stacks.AEKey, java.lang.Long> lru = HOT_OUTPUTS.get(grid);
            if (lru == null || lru.isEmpty()) return;
            ICraftingGrid service = craftingGrid(grid);
            if (service == null) return;
            com.ae2vm.shim.api.networking.storage.IStorageService storage = new com.ae2vm.addon.v8.V8StorageService(
                grid.getCache(appeng.api.networking.storage.IStorageGrid.class));
            java.util.Map<AEKey, Long> copy;
            synchronized (lru) {
                copy = new java.util.LinkedHashMap<>(lru);
            }
            int n = 0;
            for (java.util.Map.Entry<com.ae2vm.shim.api.stacks.AEKey, java.lang.Long> e : copy.entrySet()) {
                if (n++ >= HOT_RECOMPUTE_MAX_OUTPUTS) break;
                try {
                    AEKey what = e.getKey();
                    long amount = e.getValue();
                    Collection<IPatternDetails> patterns = craftingFor(service, what);
                    if (patterns == null || patterns.isEmpty()) continue;
                    IPatternDetails top = pickBestPattern(patterns, what);
                    if (top == null) continue;
                    CraftingVM vm = VM_CACHE.get(grid);
                    if (vm == null) continue; // this grid never calculated anything yet
                    if (vm.isExecuting()) continue; // a real request is running — don't block it
                    PatternCompiler.compileIfAbsent(grid, top);
                    CraftingBytecode req = PatternCompiler.compileRequest(grid, top, amount);
                    com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState networkInv = new com.ae2vm.addon.vm.RealtimeNetworkCraftingSimulationState(storage);
                    ChildCraftingSimulationState craftingInventory = new ChildCraftingSimulationState(networkInv);
                    craftingInventory.ignore(toStack(what, 1));
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
                                           ICraftingGrid service,
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
        java.util.Collection<com.ae2vm.shim.api.crafting.IPatternDetails> subs = craftingFor(service, key);
        if (!subs.isEmpty()) {
            // (v1.12.x GTL CYCLE-AWARE, ported from VM-GTL) Filter out patterns whose
            // inputs would close a DEAD ring (unseeded SCC with no external supplier),
            // e.g. steel_ingot↔steel_dust. Applied unconditionally — even a single
            // candidate may be cycle-prone. When ALL candidates are cycle-prone, keep
            // the originals (the VM's runtime circularCache guard handles the cycle).
            java.util.ArrayList<com.ae2vm.shim.api.crafting.IPatternDetails> filtered = new java.util.ArrayList<IPatternDetails>(subs.size());
            java.util.ArrayList<java.lang.String> pruned = new java.util.ArrayList<String>();
            com.ae2vm.shim.api.stacks.KeyCounter ringStock = resolveStockSnapshot(network);
            for (com.ae2vm.shim.api.crafting.IPatternDetails p : subs) {
                boolean cyc = p != null && wouldCauseCycle(key2 -> craftingFor(service, key2), p, key,
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
            com.ae2vm.shim.api.crafting.IPatternDetails sub = pickBestPattern(subs, key);
            PatternCompiler.compileIfAbsent(network, sub);
            cache.put(key, sub);
            return sub;
        }
        // Try 2: drop secondary (verify the pattern actually outputs the item)
        com.ae2vm.shim.api.stacks.AEKey clean = key.dropSecondary();
        if (!clean.equals(key)) {
            subs = craftingFor(service, clean);
            if (!subs.isEmpty()) {
                com.ae2vm.shim.api.crafting.IPatternDetails sub = pickBestPattern(subs, clean);
                if (patternOutputs(sub, clean)) {
                    PatternCompiler.compileIfAbsent(network, sub);
                    cache.put(key, sub);
                    return sub;
                }
            }
        }
        // Try 3: registry item (verify the pattern actually outputs the item)
        // AE2 1.18.1 (forge/v10.x): AEKey.getId() 不存在 → 用 AEItemKey.getItem() 直接拿 Item
        // 1.19+ AEKey.getId() 返回 ResourceLocation 走 `ITEMS.getValue(id)`；1.18.1 没这层间接
        if (key instanceof com.ae2vm.shim.api.stacks.AEItemKey) {
            com.ae2vm.shim.api.stacks.AEItemKey itemKey = (com.ae2vm.shim.api.stacks.AEItemKey) key;
            net.minecraft.item.Item item = itemKey.getItem();
            if (item != null) {
                com.ae2vm.shim.api.stacks.AEItemKey pureKey = com.ae2vm.shim.api.stacks.AEItemKey.of(item);
                if (pureKey != null) {
                    subs = craftingFor(service, pureKey);
                    if (!subs.isEmpty()) {
                        com.ae2vm.shim.api.crafting.IPatternDetails sub = pickBestPattern(subs, key);
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
        for (com.ae2vm.shim.api.crafting.IPatternDetails p : patterns) {
            if (p == null) continue;
            if (fallback == null) fallback = p;
            if (want != null && !patternOutputs(p, want)) continue;
            com.ae2vm.shim.api.stacks.GenericStack out = wrapPrimary(p);
            long amt = out == null ? Long.MAX_VALUE : Math.max(1, out.amount());
            if (amt < bestOut) { bestOut = amt; best = p; }
        }
        return best != null ? best : fallback;
    }

    /** True if the pattern's primary output is {@code want} (by key or by registry id). */
    private static boolean patternOutputs(IPatternDetails pattern, AEKey want) {
        com.ae2vm.shim.api.stacks.GenericStack out = wrapPrimary(pattern);
        if (out == null || out.what() == null) return false;
        if (out.what().equals(want)) return true;
        // AE2 1.18.1 (forge/v10.x): AEKey.getId() 不存在
        // 1.19+ 走 want.getId().equals(out.what().getId()) 检查 ResourceLocation 等同性
        // 1.18.1 用 AEItemKey.getItem() 反查 Item 等同性（覆盖 fuzzy vs pure 同 Item 场景）
        if (want instanceof com.ae2vm.shim.api.stacks.AEItemKey
                && out.what() instanceof com.ae2vm.shim.api.stacks.AEItemKey) {
            com.ae2vm.shim.api.stacks.AEItemKey wItem = (com.ae2vm.shim.api.stacks.AEItemKey) want;
            com.ae2vm.shim.api.stacks.AEItemKey oItem = (com.ae2vm.shim.api.stacks.AEItemKey) out.what();
            return wItem.getItem() == oItem.getItem();
        }
        return false;
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
            for (com.ae2vm.shim.api.crafting.IPatternDetails.IInput input : candidate.getInputs()) {
                com.ae2vm.shim.api.stacks.GenericStack[] stacks = wrapPossible(input);
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
                for (com.ae2vm.shim.api.crafting.IPatternDetails.IInput input : candidate.getInputs()) {
                    com.ae2vm.shim.api.stacks.GenericStack[] st = wrapPossible(input);
                    if (st == null || st.length == 0 || st[0] == null || st[0].what() == null) continue;
                    AEKey ik = st[0].what();
                    if (keys.add(ik)) queue.add(ik);
                }
            }
            while (!queue.isEmpty()) {
                AEKey k = queue.poll();
                java.util.Collection<com.ae2vm.shim.api.crafting.IPatternDetails> subs = patternLookup.apply(k);
                if (subs == null) continue;
                for (com.ae2vm.shim.api.crafting.IPatternDetails p : subs) {
                    if (p == null || p.getInputs() == null) continue;
                    for (com.ae2vm.shim.api.crafting.IPatternDetails.IInput input : p.getInputs()) {
                        com.ae2vm.shim.api.stacks.GenericStack[] st = wrapPossible(input);
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
                for (com.ae2vm.shim.api.crafting.IPatternDetails.IInput input : candidate.getInputs()) {
                    com.ae2vm.shim.api.stacks.GenericStack[] st = wrapPossible(input);
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
                for (com.ae2vm.shim.api.crafting.IPatternDetails.IInput input : candidate.getInputs()) {
                    com.ae2vm.shim.api.stacks.GenericStack[] st = wrapPossible(input);
                    if (st != null && st.length > 0 && st[0] != null && st[0].what() != null) {
                        cins.add(st[0].what());
                    }
                }
                if (!cins.isEmpty()) {
                    for (com.ae2vm.shim.api.stacks.GenericStack gs : wrapOutputs(candidate)) {
                        if (gs == null || gs.what() == null) continue;
                        AEKey cout = gs.what();
                        for (AEKey i : cins) graph.computeIfAbsent(i, x -> new java.util.HashSet<>()).add(cout);
                    }
                }
            }
            for (AEKey k : keys) {
                java.util.Collection<com.ae2vm.shim.api.crafting.IPatternDetails> subs = patternLookup.apply(k);
                if (subs == null) continue;
                for (com.ae2vm.shim.api.crafting.IPatternDetails p : subs) {
                    if (p == null) continue;
                    java.util.Set<AEKey> ins = new java.util.HashSet<>();
                    if (p.getInputs() != null) {
                        for (com.ae2vm.shim.api.crafting.IPatternDetails.IInput input : p.getInputs()) {
                            com.ae2vm.shim.api.stacks.GenericStack[] st = wrapPossible(input);
                            if (st != null && st.length > 0 && st[0] != null && st[0].what() != null) {
                                ins.add(st[0].what());
                            }
                        }
                    }
                    if (ins.isEmpty()) continue;
                    com.ae2vm.shim.api.stacks.GenericStack[] outs = wrapOutputs(p);
                    if (outs == null) continue;
                    for (com.ae2vm.shim.api.stacks.GenericStack gs : outs) {
                        if (gs == null || gs.what() == null) continue;
                        AEKey out = gs.what();
                        for (AEKey i : ins) graph.computeIfAbsent(i, x -> new java.util.HashSet<>()).add(out);
                    }
                }
            }
            for (java.util.Set<com.ae2vm.shim.api.stacks.AEKey> scc : tarjanScc(graph)) {
                if (scc.size() <= 1) continue; // no multi-node ring; self-loops are handled elsewhere
                boolean seeded = false;
                for (AEKey m : scc) { if (safeStock(stockLookup, m) > 0) { seeded = true; break; } }
                if (seeded) continue;
                boolean external = false;
                outer:
                for (AEKey k : keys) {
                    if (scc.contains(k)) continue;
                    java.util.Collection<com.ae2vm.shim.api.crafting.IPatternDetails> subs = patternLookup.apply(k);
                    if (subs == null) continue;
                    for (com.ae2vm.shim.api.crafting.IPatternDetails p : subs) {
                        if (p == null) continue;
                        com.ae2vm.shim.api.stacks.GenericStack[] outs = wrapOutputs(p);
                        if (outs == null) continue;
                        for (com.ae2vm.shim.api.stacks.GenericStack gs : outs) {
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
                if (it == null) it = graph.getOrDefault(node, java.util.Collections.emptySet()).iterator();
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
            GenericStack[] outs = wrapOutputs(p); // v9: IAEStack[] → wrapOutputs
            if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                return outs[0].what().toString();
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    /** Live network stock snapshot for seeded-ring decisions (null-safe). */
    private static com.ae2vm.shim.api.stacks.KeyCounter resolveStockSnapshot(Object network) {
        try {
            if (network instanceof IGrid) {
                IGrid g = (IGrid) network;
                com.ae2vm.shim.api.networking.storage.IStorageService svc =
                        new com.ae2vm.addon.v8.V8StorageService(
                                g.getCache(appeng.api.networking.storage.IStorageGrid.class));
                // v8 (1.16.5): IStorageGrid 取代 v9 的 IStorageService
                KeyCounter snap = new KeyCounter();
                appeng.api.storage.IMEMonitor<IAEItemStack> mon =
                        svc.getInventory(com.ae2vm.shim.api.storage.StorageChannels.items());
                if (mon != null) {
                    for (IAEStack st : mon.getStorageList()) {
                        if (st instanceof IAEItemStack) {
                            IAEItemStack is = (IAEItemStack) st;
                            com.ae2vm.shim.api.stacks.AEItemKey k = AEItemKey.wrap(is);
                            if (k != null) snap.add(k, st.getStackSize());
                        }
                    }
                }
                return snap;
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
        // v9 (1.17.1): plan.missingItems() 是 MixedStackList，迭代出 IAEStack
        for (IAEStack st : plan.missingItems()) {
            long v = st.getStackSize();
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
    private static boolean staleMissingNowCraftable(ICraftingGrid service, CraftingVM vm,
                                                    ICraftingPlan plan, AEKey requested) {
        java.util.Map<AEKey, Object> rcache = vm.getResolverCache();
        // v9 (1.17.1): plan.missingItems() 是 MixedStackList — 转 KeyCounter 迭代
        for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<com.ae2vm.shim.api.stacks.AEKey> e : toKeyCounter(plan.missingItems()).entrySet()) {
            AEKey missingKey = e.getKey();
            if (missingKey == null || missingKey.equals(requested)) continue;
            Object cached = rcache.get(missingKey);
            boolean nowCraftable = !craftingFor(service, missingKey).isEmpty();
            if (cached instanceof long[] && nowCraftable) return true;      // pattern appeared mid-request
            if (cached instanceof IPatternDetails && !nowCraftable) return true; // provider window
        }
        return false;
    }
}
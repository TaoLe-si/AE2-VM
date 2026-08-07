package com.ae2vm.addon.vm;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.CraftingPlan;
import appeng.crafting.inv.CraftingSimulationState;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.compiler.PatternCompiler;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Stack-based VM — BigInteger stack for unlimited precision.
 * Hot path opcodes inlined, intermediate values use primitive long.
 *
 * ============================================================================
 * KEY INVARIANTS — learned the hard way across 1.8.2→1.8.8. DO NOT "simplify"
 * these or you will re-introduce the bugs below. See /memories/repo/
 * ae2vm-chain-crafting-fix.md for the full history.
 * ============================================================================
 *
 * 1) NEVER re-expand a shared recipe DAG once per path.
 *    The NAST pack has Fibonacci-style chains (quantum → complex → omni →
 *    appflux core; each level ≈ ×1.618). A naive per-path recursion visits
 *    ~6.6M+ paths for ONE 64m cell: millions of applyBundle calls, a 792MB
 *    log flood, and EMIT inflated to Long.MAX_VALUE (while MISS stayed
 *    correct, because leaves aggregate per-path too). The capture phase
 *    records each pattern's 1-craft delta once (bundle[0]), and
 *    applyAggregation() computes the total crafts per pattern with a
 *    demand-propagation worklist (O(patterns + edges)), applying each bundle
 *    exactly once. This equals the path-sum AE2 computes. NEVER go back to
 *    per-path re-execution.
 *
 * 2) The recipe DAG is ACYCLIC (quantum_1k → {complex_256m, complex_64m},
 *    NOT back to quantum_64m — verified from captured bundle needs). The
 *    capture's cycle guard (resolvingKeys / circularCache) is kept
 *    defensively, but the exponential blowup here comes from path
 *    re-expansion, not from a recipe cycle. Recipes CAN still form small
 *    cycles (e.g. dust↔ingot smelting/pulverizing); applyAggregation() cuts
 *    cycle back-edges so an already-finalized node is never re-propagated.
 *
 * 3) emittedItems MUST NOT contain crafted intermediates.
 *    AE2's CraftingPlanSummary.fromJob computes the GUI "to craft" column as
 *        craftAmount = Σ emittedItems + Σ patternTimes × outputAmount
 *    Normal AE2 puts ONLY emit-source items (interfaces/level emitters via
 *    emitItems()) into the plan's emittedItems; crafted intermediates are
 *    tracked via patternTimes. If we add intermediates to emittedItems, the
 *    GUI shows 2× (6.6M emitted + 6.6M patternTimes = 13M). This bit us in
 *    1.8.6/1.8.7 — the log/EMIT/MISS were correct but the AE2 GUI doubled.
 *    applyBundleDirect deliberately does NOT add to emittedItems.
 *
 * 4) INSERT_OUTPUT records the output in BOTH emittedItems and simInternal
 *    with a SINGLE simulation.insert, so bundle.emitted == bundle.internal
 *    for non-self-consuming patterns. applyBundleDirect and revertBundle must
 *    NOT process `internal` separately (would double-insert the same output
 *    and drive simInternal negative). Only `emitted` drives the replay;
 *    `internal` is only read by the sat-check/self-sufficient branches, which
 *    are dead in root-capture mode.
 *
 * 5) Root-capture mode: the request pattern is dispatched as a capturing
 *    frame (case 14 uses withBundle), so EVERY CALL_BY_KEY is "capturing" →
 *    ZERO applies happen during bytecode execution; all effects are applied
 *    exactly once by applyAggregation() inside buildPlan(). RETURN must never
 *    rewind/re-apply for the root frame (callStack.isEmpty() branch).
 *
 * 6) applyBundleDirect is deficit-aware for `used` (shortfall → missing), so
 *    no pre-check is needed — stock is consumed in post-order (children before
 *    parents) and any extraction shortfall becomes missing.
 */
public class CraftingVM {
    private static final int MAX_STACK = 512;
    private static final int MAX_CALL_DEPTH = 128;
    
    private static final BigInteger BIG_ZERO = BigInteger.ZERO;
    private static final BigInteger BIG_ONE = BigInteger.ONE;
    private static final BigInteger BIG_MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
    
    // Pre-allocated BigInteger cache for values 0–1023 (hot values in VM)
    private static final BigInteger[] BIG_CACHE = new BigInteger[1024];
    static {
        for (int i = 0; i < 1024; i++) BIG_CACHE[i] = BigInteger.valueOf(i);
    }
    
    private final Object networkKey;
    private Function<AEKey, IPatternDetails> patternResolver;
    
    private BigInteger[] stack;
    private int sp;
    private Deque<CallFrame> callStack;
    private byte[] code;
    private int pc;
    private AEKey[] constantPool;
    private IPatternDetails[] patternPool;
    
    private KeyCounter usedItems;
    private KeyCounter missingItems;
    private KeyCounter ecoExternalItems;
    private KeyCounter emittedItems;
    private KeyCounter simInternal; // tracks items our VM inserted into simulation (not from network)
    private Map<IPatternDetails, Long> patternTimes;
    private CraftingSimulationState simulation;
    private AEKey outputKey;
    private long nodeCount;
    private long rootCraftTimes;
    private BigInteger batchRemainder;
    private boolean aggregated;
    
    private final java.util.Set<AEKey> resolvingKeys = new java.util.HashSet<>();
    private final java.util.Set<AEKey> circularCache = new java.util.HashSet<>(); // keys that formed a cycle this execution
    private final java.util.Set<AEKey> cyclicCraftKeys = new java.util.HashSet<>(); // sub-crafts whose pattern hits a cross-cycle → stock-only, not craftable
    private final java.util.Set<AEKey> jitFailCache = new java.util.HashSet<>(); // patterns whose JIT memo is unsatisfiable → run normal exec
    // O(1): lazily snapshotted real network stock. The live inventory cannot change
    // during a single simulation, so we snapshot it once and reuse instead of calling
    // getAvailableStacks() (an O(storage) walk) for every finalized aggregation key.
    private KeyCounter realStockCache;
    private boolean extractIsClaim; // RETURN sets this; next EXTRACT skips usedItems (sub-craft claim)
    // (v1.9.5) Network stock consumed via the stock-aware sub-craft branch (fromStock),
    // per key. Parent bundles ALSO recorded that stock in their `used` demand during
    // capture (EXTRACT read it from the sandbox), so without subtracting it here the
    // parent would re-extract the network stock AND the crafted deficit → a false
    // shortfall. Storing it per-execute and draining it as parents are applied keeps
    // the total conserved across shared parents.
    private java.util.Map<AEKey, BigInteger> stockFromNetwork;
    
    // JIT: per-pattern power-of-2 bundles. Bundle[0]=1 run, Bundle[k]=2^k runs.
    // Each bundle stores the complete subtree effects (incl. sub-patterns).
    // Bundle[k] = Bundle[k-1].scale(2) — linear effects, no re-execution.
    private static final int MAX_BUNDLE_BITS = 64; // long bits 0–63
    private final Map<AEKey, Bundle[]> bundleCache = new HashMap<>();
    
    private record CallFrame(int returnPc, byte[] code, AEKey[] constantPool, 
                             IPatternDetails[] patternPool, AEKey resolvingKey,
                             AEKey bundleKey, Bundle bundleBefore, long savedReq,
                             java.util.Map<AEKey, Long> subCalls) {
        CallFrame(int returnPc, byte[] code, AEKey[] constantPool, 
                  IPatternDetails[] patternPool, AEKey resolvingKey) {
            this(returnPc, code, constantPool, patternPool, resolvingKey, null, null, 0, null);
        }
        CallFrame withBundle(AEKey key, Bundle before, long req) {
            return new CallFrame(returnPc, code, constantPool, patternPool, resolvingKey, key, before, req, new java.util.HashMap<>());
        }
        // Records a directly-resolved sub-call (key, item-amount) on a dispatch frame.
        CallFrame recordSubCall(AEKey k, long r) {
            if (subCalls != null) subCalls.merge(k, r, Long::sum);
            return this;
        }
    }
    
    private static class Bundle {
        BigInteger bytes = BigInteger.ZERO;
        // Concurrent maps so scaling/diffing/capturing can run in parallel safely
        // (every entry is independent — order never matters for the result).
        final Map<AEKey, BigInteger> used = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<AEKey, BigInteger> emitted = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<AEKey, BigInteger> missing = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<AEKey, BigInteger> internal = new java.util.concurrent.ConcurrentHashMap<>(); // VM-inserted items offset
        final Map<IPatternDetails, BigInteger> patterns = new java.util.concurrent.ConcurrentHashMap<>();
        // DIRECT sub-pattern needs: sub-key → crafts. Sub-tree effects are NOT folded
        // into this bundle; they are applied via these needs (each sub-bundle scaled).
        // This prevents nested applications from being double-counted when the parent
        // bundle is scaled (the ×3-per-level pollution seen in production logs).
        final Map<AEKey, BigInteger> needs = new java.util.concurrent.ConcurrentHashMap<>();
        // DIRECT sub-pattern ITEM needs: sub-key → per-craft ITEM amount demanded by this
        // pattern. applyAggregation converts item demand to craft counts via
        // ceil(itemDemand / outputPerCraft), so a mega pattern (625,000 alloy_infused per
        // craft) is crafted ONCE for the whole request instead of once per parent craft
        // (the 1000 energy-tablet → 625M alloy / 78M redstone bug). (v1.8.18)
        final Map<AEKey, BigInteger> itemNeeds = new java.util.concurrent.ConcurrentHashMap<>();
        
        Bundle scale(long factor) { return scale(BigInteger.valueOf(factor)); }
        Bundle scale(BigInteger factor) {
            Bundle b = new Bundle();
            b.bytes = bytes.multiply(factor);
            used.forEach((k, v) -> b.used.put(k, v.multiply(factor)));
            emitted.forEach((k, v) -> b.emitted.put(k, v.multiply(factor)));
            missing.forEach((k, v) -> b.missing.put(k, v.multiply(factor)));
            internal.forEach((k, v) -> b.internal.put(k, v.multiply(factor)));
            patterns.forEach((k, v) -> b.patterns.put(k, v.multiply(factor)));
            needs.forEach((k, v) -> b.needs.put(k, v.multiply(factor)));
            itemNeeds.forEach((k, v) -> b.itemNeeds.put(k, v.multiply(factor)));
            return b;
        }
        
        boolean isEmpty() {
            return bytes.signum() == 0 && used.isEmpty() && emitted.isEmpty() && missing.isEmpty() 
                && internal.isEmpty() && patterns.isEmpty() && needs.isEmpty() && itemNeeds.isEmpty();
        }
    }
    
    /** Safe BigInteger→long conversion. Caps at Long.MAX_VALUE, logs warning on overflow. */
    private static long toLongSafe(BigInteger v, String ctx) {
        if (v.compareTo(BIG_MAX_LONG) > 0) {
            // LOG disabled: AE2VMAddon.LOGGER.warn("[AE2-VM] Value exceeds Long.MAX_VALUE for {}, capping: {}", ctx, v);
            return Long.MAX_VALUE;
        }
        if (v.signum() < 0) return 0; // shouldn't happen for counts
        return v.longValue();
    }
    
    /** BigInteger→double for byte counts — handles astronomical values that overflow long (up to 1e308). */
    private static double toBytesDouble(BigInteger v) {
        return v.doubleValue();
    }
    
    /**
     * Apply a bundle's DIRECT effects exactly once (deficit-aware). Needs are NOT
     * expanded here — the final applyAggregation() computes every pattern's total
     * craft demand and applies each bundle exactly once, scaled by its total. This
     * guarantees shared DAG nodes are never re-expanded once per path (the
     * exponential blowup / log flood seen in production). See class doc #1/#3/#4.
     *
     * This is the ONLY place that turns a bundle into plan effects: it inserts
     * `emitted` (produced stock, tracked in simInternal for fromInternal), extracts
     * `used` (deficit → missing), adds `missing` (leaves) and `patterns`. It does
     * NOT touch `internal` (it equals emitted; a separate loop would double-insert)
     * and it does NOT add crafted intermediates to emittedItems (AE2 GUI would 2×).
     */
    private void applyBundleDirect(Bundle b) {
        simulation.addBytes(toBytesDouble(b.bytes));
        for (var e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit:" + e.getKey());
            simulation.insert(e.getKey(), val, Actionable.MODULATE);
            // Do NOT add this to emittedItems! AE2 CraftingPlanSummary.fromJob:
            //   craftAmount = Σ emittedItems + Σ patternTimes × outputAmount
            // Normal AE2's emittedItems holds ONLY emit-source items (interfaces /
            // level emitters); crafted intermediates live in patternTimes. Adding them
            // here made the GUI show 2× (6.6M + 6.6M = 13M) in 1.8.6/1.8.7.
            simInternal.add(e.getKey(), val);
        }
        for (var e : b.used.entrySet()) {
            long val = toLongSafe(e.getValue(), "used:" + e.getKey());
            long got = simulation.extract(e.getKey(), val, Actionable.MODULATE);
            long internal = simInternal.get(e.getKey());
            long fromInternal = Math.min(got, internal);
            if (fromInternal > 0) simInternal.add(e.getKey(), -fromInternal);
            long fromNetwork = got - fromInternal;
            if (fromNetwork > 0) usedItems.add(e.getKey(), fromNetwork);
            long shortfall = val - got;
            if (shortfall > 0) missingItems.add(e.getKey(), shortfall);
        }
        for (var e : b.missing.entrySet()) {
            long val = toLongSafe(e.getValue(), "miss:" + e.getKey());
            if (val <= 0) continue;
            // (v1.9.11) Realtime-verify capture-time missing. A bundle's `missing` was
            // snapshotted at capture time when the network was short (leaf ingredients
            // with no stock). If the network NOW holds the item, extract it (record as
            // used) instead of reporting a stale missing. This is what lets us KEEP the
            // bundle cached across requests (high JIT hit-rate) without the cache-hygiene
            // drop that cascaded to the whole chain and forced a full re-capture every
            // request (empty-stock deep Fibonacci). If the item is still absent, the
            // shortfall becomes missing exactly as before.
            long got = simulation.extract(e.getKey(), val, Actionable.MODULATE);
            if (got > 0) {
                long internal = simInternal.get(e.getKey());
                long fromInternal = Math.min(got, internal);
                if (fromInternal > 0) simInternal.add(e.getKey(), -fromInternal);
                long fromNetwork = got - fromInternal;
                if (fromNetwork > 0) usedItems.add(e.getKey(), fromNetwork);
            }
            long shortfall = val - got;
            if (shortfall > 0) missingItems.add(e.getKey(), shortfall);
        }
        for (var e : b.patterns.entrySet()) {
            long val = toLongSafe(e.getValue(), "pat:" + e.getKey());
            if (val != 0) {
                patternTimes.merge(e.getKey(), val, Long::sum);
                simulation.addCrafting(e.getKey(), val);
            }
        }
    }

    // Legacy entry points kept only for the (now unreachable) non-capture apply
    // branches. They never expand needs — the aggregation owns all subtree replay.
    private void applyBundle(Bundle b) { applyBundleDirect(b); }
    private void applyBundleDeficit(Bundle b) { applyBundleDirect(b); }

    /**
     * Final aggregation over the captured bundle DAG (class doc #1).
     *
     * 1) Total craft demand per pattern is computed with a demand-propagation
     *    worklist: total[sub] += total[parent] × needs[parent→sub], propagating
     *    INCREMENTS (not totals) so shared nodes are aggregated, not re-expanded.
     *    O(patterns + needs edges) — this is what collapsed the 6.6M-path
     *    Fibonacci explosion to O(patterns).
     * 2) Each 1-craft bundle is applied exactly once, scaled by its total demand,
     *    in post-order (children before parents) so produced intermediates are
     *    present in the simulation before a parent's used-extraction runs.
     *
     * The DAG is acyclic (quantum_1k → complex, not a cycle) — the worklist relies
     * on that; the cycle guard in the capture (resolvingKeys/circularCache) is only
     * defensive. If a key has demand but no bundle it is a missing leaf (defensive).
     */
    private void applyAggregation() {
        if (aggregated) return;
        aggregated = true;
        Map<AEKey, BigInteger> total = new HashMap<>();
        // Phase 1: walk the bundle DAG from the root to enumerate keys, parent→child
        // edges (from per-craft ITEM needs) and each key's parent count.
        Map<AEKey, java.util.Set<AEKey>> children = new HashMap<>();
        Map<AEKey, Integer> parentCount = new HashMap<>();
        {
            Deque<AEKey> stack = new ArrayDeque<>();
            java.util.Set<AEKey> seen = new java.util.HashSet<>();
            stack.push(outputKey);
            seen.add(outputKey);
            while (!stack.isEmpty()) {
                AEKey k = stack.pop();
                Bundle[] arr = bundleCache.get(k);
                if (arr == null || arr[0] == null) continue;
                var subs = children.computeIfAbsent(k, x -> new java.util.HashSet<>());
                for (var e : arr[0].itemNeeds.entrySet()) {
                    AEKey sub = e.getKey();
                    if (sub.equals(k)) continue; // self-edge (cycle) — see v1.9.x notes
                    if (subs.add(sub)) {
                        parentCount.merge(sub, 1, Integer::sum);
                        if (seen.add(sub)) stack.push(sub);
                    }
                }
            }
        }
        // Phase 2: propagate ITEM demand (total[parent] crafts × per-craft item need),
        // then convert to crafts with ceil(itemDemand / outputPerCraft). A child is only
        // finalized after ALL its parents are processed, so the ceil applies to the full
        // accumulated demand (never per-parent increments). This makes a mega pattern
        // (e.g. 625,000 alloy_infused per craft) craft ONCE for the whole request instead
        // of once per parent craft (1000× → 625M alloy / 78M redstone in v1.8.17). It also
        // removes overproduction for normal patterns (gold: need 3/craft, output 4 →
        // ceil(totalDemand/4) instead of totalDemand crafts), matching AE2's
        // times = ceil(totalRequestedItems / craftedPerPattern).
        Map<AEKey, BigInteger> itemDemand = new HashMap<>();
        Deque<AEKey> queue = new ArrayDeque<>();
        total.put(outputKey, BigInteger.valueOf(rootCraftTimes));
        queue.add(outputKey);
        while (!queue.isEmpty()) {
            AEKey p = queue.poll();
            BigInteger pCrafts = total.getOrDefault(p, BigInteger.ZERO);
            Bundle[] pArr = bundleCache.get(p);
            if (pArr == null || pArr[0] == null) continue;
            for (var e : pArr[0].itemNeeds.entrySet()) {
                AEKey c = e.getKey();
                if (c.equals(p)) continue;
                // DIVERGENT 2-CYCLE FIX (dust_steel <-> ingot_steel smelting/pulverizing):
                // If c has ALREADY been finalized (it is the root, or a node that was
                // already propagated), the edge p -> c is a CYCLE BACK-EDGE, not a DAG
                // edge. In a proper DAG a node is finalized exactly once, after ALL its
                // parents are processed (parentCount), so reaching an already-finalized
                // node can only happen around a cycle — e.g. the "dust -> ingot" furnace
                // pattern and the "ingot -> dust" pulverizer pattern reference each other.
                // Re-propagating demand into c here would overwrite its correct total
                // (the root 175K ingot_steel was clobbered to 2735 -> false 175K missing)
                // and let the cyclic demand diverge. A cyclic need is only satisfiable
                // from STOCK: the bundle's used-extraction consumes it and records the
                // shortfall as missing, so the edge is simply cut from the propagation.
                if (total.containsKey(c)) continue;
                BigInteger add = pCrafts.multiply(e.getValue());
                if (add.signum() != 0) itemDemand.merge(c, add, BigInteger::add);
                int rem = parentCount.merge(c, 0, Integer::sum) - 1;
                parentCount.put(c, rem);
                if (rem == 0) {
                    BigInteger demand = itemDemand.getOrDefault(c, BigInteger.ZERO);
                    Bundle[] cArr = bundleCache.get(c);
                    if (cArr == null || cArr[0] == null) {
                        missingItems.add(c, toLongSafe(demand, "agg-miss:" + c));
                    } else {
                        long opc = outputPerCraftOf(c, cArr[0]);
                        // STOCK-AWARE SUB-CRAFT (v1.9.x): AE2 native extracts the available
                        // network stock of a sub-item BEFORE crafting it, and crafts only the
                        // deficit. The VM used to craft the FULL demand, so a sub-item already
                        // sitting in stock (e.g. 14974 pellet_polonium) was still crafted → its
                        // whole recipe chain got pulled in → spurious missing (polonium gas:
                        // no pattern, no stock) that native AE2 never shows.
                        // (v1.9.13) FUZZY GROUP STOCK (the gray-wool bug): a pattern input with
                        // item/fluid replacement encodes MULTIPLE acceptable variants
                        // (getPossibleInputs() returns [gray_wool, white_wool]). The PRIMARY c
                        // may have NO stock while a substitute (white) sits in the network.
                        // AE2's missing-check must treat the WHOLE group's stock as satisfying
                        // the slot, and craft the primary only for the deficit. Previously
                        // realStockOf(c) looked at ONLY the primary's own stock, so a stocked
                        // substitute was ignored → the primary got crafted in full (pulling its
                        // recipe chain) AND the parent's per-craft EXTRACT of the substitute was
                        // scaled up → false "missing white_wool" even though the pattern exists
                        // ("有样板却报缺失" / "1x vs 2x" quantity boundary).
                        java.util.Set<AEKey> fuzzyGroup = PatternCompiler.getFuzzyGroup(c);
                        long stock;
                        if (fuzzyGroup.size() > 1) {
                            long totalStock = 0;
                            for (AEKey v : fuzzyGroup) totalStock += realStockOf(v);
                            stock = totalStock;
                        } else {
                            stock = realStockOf(c);
                        }
                        BigInteger netDemand = demand;
                        long fromStock = 0;
                        if (stock > 0 && demand.signum() > 0) {
                            BigInteger bs = BigInteger.valueOf(stock);
                            if (demand.compareTo(bs) <= 0) {
                                fromStock = demand.longValue();
                                netDemand = BigInteger.ZERO;
                            } else {
                                fromStock = stock;
                                netDemand = demand.subtract(bs);
                            }
                        }
                        if (fromStock > 0) {
                            // Consume the real network stock, distributing across the fuzzy
                            // group's variants (primary first, then substitutes) so the plan's
                            // usedItems names the ACTUAL variant the network holds (e.g.
                            // white_wool) instead of the empty primary (gray_wool).
                            long remaining = fromStock;
                            for (AEKey v : fuzzyGroup) {
                                long s = realStockOf(v);
                                if (s <= 0) continue;
                                long take = Math.min(remaining, s);
                                if (take <= 0) continue;
                                // STOCK-AWARE SUB-CRAFT FIX (v1.8.23): record the real network
                                // stock directly as network-used so the CPU extracts it at
                                // submit time. Still consume what the sandbox sim actually holds
                                // so later used-extraction stays consistent.
                                usedItems.add(v, take);
                                simulation.extract(v, take, Actionable.MODULATE);
                                // Parent bundles captured this network stock into their own
                                // `used` demand (the parent's EXTRACT read it from the sandbox
                                // during capture). Accumulate it and subtract it from the
                                // parents' used demand when they are applied.
                                // For a SUBSTITUTE variant (v != c) the parent's captured used[v]
                                // is per-craft and scales to the FULL slot demand, but the
                                // variant is a finite one-time pool → zero the parent's used[v]
                                // entirely (the deficit is crafted as the primary c). The shared
                                // pool drains across sibling parents, so each parent subtracts
                                // only what it recorded. For the PRIMARY (v == c) the existing
                                // fromStock semantics apply (parent extracts the crafted-deficit
                                // part, which carries the primary key).
                                long poolAdd = (fuzzyGroup.size() > 1 && !v.equals(c))
                                        ? Math.min(demand.longValue(), Long.MAX_VALUE)
                                        : take;
                                stockFromNetwork.merge(v, BigInteger.valueOf(poolAdd), BigInteger::add);
                                remaining -= take;
                            }
                        }
                        BigInteger crafts = netDemand.add(BigInteger.valueOf(opc - 1)).divide(BigInteger.valueOf(opc));
                        total.put(c, crafts);
                        queue.add(c);
                    }
                }
            }
        }
        // Phase 3: apply each bundle exactly once, children before parents.
        // NOTE: kept sequential — parallelizing the per-key bundle scaling (parallelStream
        // over ~dozens of tiny BigInteger multiplies) added more fork/join overhead than it
        // saved on the large order (v1.9.x measurement: 1-billion request got slower).
        java.util.Set<AEKey> applied = new java.util.HashSet<>();
        for (AEKey k : total.keySet()) applyOrdered(k, applied, total);
        // AGG DIAG disabled (v1.8.20) — keep log clean, only total calc time.
        // BigInteger rootTotal = total.getOrDefault(outputKey, BigInteger.ZERO);
        // Bundle[] rootArr = bundleCache.get(outputKey);
        // AE2VMAddon.LOGGER.info("[AE2-VM] AGG root={} rootCraftTimes={} rootBundle={} itemNeeds={}",
        //     rootTotal, rootCraftTimes,
        //     (rootArr != null && rootArr[0] != null) ? rootArr[0].itemNeeds.keySet().size() : -1,
        //     (rootArr != null && rootArr[0] != null) ? rootArr[0].itemNeeds : "null");
    }

    /**
     * Output amount of {@code key} produced per craft, read from the 1-craft bundle's
     * own emitted map (falls back to 1). Used by the aggregation to convert the total
     * ITEM demand into a craft count via ceil(itemDemand / outputPerCraft).
     */
    private static long outputPerCraftOf(AEKey key, Bundle b) {
        BigInteger out = b.emitted.get(key);
        if (out != null && out.signum() > 0) {
            long v = out.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : out.longValue();
            return v > 0 ? v : 1;
        }
        return 1;
    }

    /** Real network stock of a key (live inventory, incl. fluids/gases), O(1) cached. */
    private long realStockOf(AEKey key) {
        if (realStockCache == null) {
            appeng.api.stacks.KeyCounter snap = new appeng.api.stacks.KeyCounter();
            try {
                if (networkKey instanceof appeng.api.networking.IGrid g) {
                    var st = g.getStorageService();
                    if (st != null) snap = st.getInventory().getAvailableStacks();
                }
            } catch (Throwable ignored) {
            }
            realStockCache = snap;
        }
        return realStockCache.get(key);
    }

    /**
     * True if {@code pattern} is an <em>unseeded self-growth loop</em>: its primary
     * output key is the key of EVERY one of its own inputs (e.g. {@code A -> 2A}).
     * Firing such a pattern would duplicate the item from nothing (an AE2
     * "self-growth" exploit — craft 1 A to get 2 A back, indefinitely). The VM must
     * therefore NEVER fire it: its output demand can only be satisfied from stock,
     * and any shortfall is missing (matches the Thunderbolt reference
     * {@code cycle/self-growth-cut} semantics). A pattern with an external input
     * (e.g. {@code A + B -> 2A}) is NOT cut — it is seeded by {@code B} and is a
     * legitimate amplifier recipe.
     */
    private static boolean isUnseededSelfLoop(IPatternDetails pattern) {
        if (pattern == null) return false;
        var primary = pattern.getPrimaryOutput();
        if (primary == null || primary.what() == null) return false;
        AEKey out = primary.what();
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length == 0) return false;
        for (var input : inputs) {
            var possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0) return false;
            boolean anySelf = false;
            for (var gs : possible) {
                if (gs != null && gs.what() != null && gs.what().equals(out)) {
                    anySelf = true;
                    break;
                }
            }
            if (!anySelf) return false; // this input slot is an external seed → not cut
        }
        return true;
    }

    /** Read-only DFS that applies each bundle exactly once, children before parents. */
    private void applyOrdered(AEKey k, java.util.Set<AEKey> applied, Map<AEKey, BigInteger> total) {
        if (!applied.add(k)) return;
        Bundle[] arr = bundleCache.get(k);
        if (arr != null && arr[0] != null) {
            for (var e : arr[0].itemNeeds.entrySet()) applyOrdered(e.getKey(), applied, total);
        }
        BigInteger t = total.getOrDefault(k, BigInteger.ZERO);
        if (t.signum() == 0) return;
        if (arr == null || arr[0] == null) {
            missingItems.add(k, toLongSafe(t, "agg-miss:" + k));
            return;
        }
        Bundle scaled = arr[0].scale(t);
        subtractStockFromNetwork(scaled);
        applyBundleDirect(scaled);
    }

    /**
     * (v1.9.8) Remove the network-stock portion of a key (already consumed by the
     * stock-aware sub-craft branch as {@code fromStock}) from a parent bundle's
     * {@code used} demand. During capture the parent's EXTRACT read that stock from
     * the sandbox and recorded it in {@code used}; the stock-aware branch separately
     * recorded it in {@code usedItems} and drained it from the sandbox. Subtracting it
     * here (from the scaled copy, never the cached bundle) makes the parent extract
     * only the crafted-deficit part, so the network stock is not double-counted as a
     * false shortfall. The shared pool is drained as parents are applied so sibling
     * parents don't each subtract the whole stock.
     */
    private void subtractStockFromNetwork(Bundle b) {
        if (stockFromNetwork == null || stockFromNetwork.isEmpty()) return;
        var it = b.used.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BigInteger pool = stockFromNetwork.get(e.getKey());
            if (pool == null || pool.signum() <= 0) continue;
            BigInteger used = e.getValue();
            BigInteger sub = used.min(pool);
            BigInteger rem = used.subtract(sub);
            if (rem.signum() <= 0) it.remove();
            else e.setValue(rem);
            BigInteger poolRem = pool.subtract(sub);
            if (poolRem.signum() <= 0) stockFromNetwork.remove(e.getKey());
            else stockFromNetwork.put(e.getKey(), poolRem);
        }
    }

    /**
     * (v1.9.8) True if every direct sub-craft referenced by {@code b0.itemNeeds} has a
     * cached 1-craft bundle. Cache hygiene drops bundles whose missing is non-empty
     * (e.g. under EMPTY network stock the low-level craftable nodes are dropped every
     * request). Reusing a parent bundle whose sub-bundles were dropped would silently
     * skip re-capturing them (the parent's bytecode never re-runs) → the aggregation
     * sees them without a bundle and reports the whole demand missing — the recipe
     * chain "lost" between requests. Callers re-capture the bundle in that case.
     */
    private boolean subBundlesComplete(Bundle b0) {
        if (b0.itemNeeds.isEmpty()) return true;
        for (var e : b0.itemNeeds.entrySet()) {
            Bundle[] sub = bundleCache.get(e.getKey());
            if (sub == null || sub[0] == null) return false;
        }
        return true;
    }

    /**
     * Undo a bundle's effects — reverse order of apply. */
    private void revertBundle(Bundle b) {
        simulation.addBytes(-toBytesDouble(b.bytes));
        // Reverse patterns first (no sim state dependency)
        for (var e : b.patterns.entrySet()) {
            long val = toLongSafe(e.getValue(), "pat-revert:" + e.getKey());
            long newVal = patternTimes.merge(e.getKey(), -val, Long::sum);
            if (newVal == 0) patternTimes.remove(e.getKey());
            simulation.addCrafting(e.getKey(), -val);
        }
        // Reverse missing
        for (var e : b.missing.entrySet()) {
            long val = toLongSafe(e.getValue(), "miss-revert:" + e.getKey());
            missingItems.add(e.getKey(), -val);
            if (missingItems.get(e.getKey()) == 0) missingItems.remove(e.getKey());
        }
        // Reverse used (undo extraction → re-insert to sim, undo usedItems).
        // MUST come BEFORE internal-revert: apply inserts internal first, so simInternal
        // still holds the produced amount here and fromInternal is computed correctly.
        // (applyBundle only runs after a satisfiability check guarantees the simulation
        // holds the full nominal amount, so restoring `val` is correct.)
        for (var e : b.used.entrySet()) {
            long val = toLongSafe(e.getValue(), "used-revert:" + e.getKey());
            simulation.insert(e.getKey(), val, Actionable.MODULATE);
            // Undo the net-used calculation: add back fromNetwork portion
            long internal = simInternal.get(e.getKey());
            long fromInternal = Math.min(val, internal);
            long fromNetwork = val - fromInternal;
            if (fromNetwork > 0) usedItems.add(e.getKey(), -fromNetwork);
            if (usedItems.get(e.getKey()) == 0) usedItems.remove(e.getKey());
        }
        // Reverse emitted (undo insert → extract from sim, undo emittedItems and simInternal).
        // NOTE: there is no separate `internal` revert — INSERT_OUTPUT recorded the output in
        // both emittedItems and simInternal with a SINGLE simulation.insert, so the emitted
        // revert below already undoes the insert and the simInternal delta. Reverting internal
        // separately would double-extract and leave simInternal negative.
        for (var e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit-revert:" + e.getKey());
            simulation.extract(e.getKey(), val, Actionable.MODULATE);
            emittedItems.add(e.getKey(), -val);
            if (emittedItems.get(e.getKey()) == 0) emittedItems.remove(e.getKey());
            simInternal.add(e.getKey(), -val);
            if (simInternal.get(e.getKey()) == 0) simInternal.remove(e.getKey());
        }
        // NOTE: needs are NOT reverted here — sub-tree effects are never applied during
        // capture (capture context), so there is nothing to undo for them.
    }
    
    private Bundle captureDelta() {
        Bundle b = new Bundle();
        b.bytes = BigInteger.valueOf((long)((com.ae2vm.addon.mixin.CraftingSimulationStateAccessor)simulation).getBytes());
        // Snapshot key sets then read values serially — single-threaded: no writers
        // during captureDelta (applyBundle/revertBundle run serially on the VM thread).
        if (!usedItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(usedItems.keySet()); for (AEKey k : ks) { long v = usedItems.get(k); if (v != 0) b.used.put(k, BigInteger.valueOf(v)); } }
        if (!emittedItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(emittedItems.keySet()); for (AEKey k : ks) { long v = emittedItems.get(k); if (v != 0) b.emitted.put(k, BigInteger.valueOf(v)); } }
        if (!missingItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(missingItems.keySet()); for (AEKey k : ks) { long v = missingItems.get(k); if (v != 0) b.missing.put(k, BigInteger.valueOf(v)); } }
        if (!simInternal.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(simInternal.keySet()); for (AEKey k : ks) { long v = simInternal.get(k); if (v != 0) b.internal.put(k, BigInteger.valueOf(v)); } }
        if (!patternTimes.isEmpty()) { var ks = new java.util.ArrayList<IPatternDetails>(patternTimes.keySet()); for (IPatternDetails k : ks) { long v = patternTimes.get(k); if (v != 0) b.patterns.put(k, BigInteger.valueOf(v)); } }
        return b;
    }
    
    private Bundle diffBundle(Bundle after, Bundle before) {
        Bundle b = new Bundle();
        b.bytes = after.bytes.subtract(before.bytes);
        for (var e : after.used.entrySet()) {
            BigInteger bv = before.used.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.used.put(e.getKey(), d);
        }
        for (var e : after.emitted.entrySet()) {
            BigInteger bv = before.emitted.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.emitted.put(e.getKey(), d);
        }
        for (var e : after.missing.entrySet()) {
            BigInteger bv = before.missing.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.missing.put(e.getKey(), d);
        }
        for (var e : after.internal.entrySet()) {
            BigInteger bv = before.internal.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.internal.put(e.getKey(), d);
        }
        for (var e : after.patterns.entrySet()) {
            BigInteger bv = before.patterns.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.patterns.put(e.getKey(), d);
        }
        return b;
    }
    
    /** Subtract one map from another, removing non-positive entries. */
    private static void subtractMap(Map<AEKey, BigInteger> target, Map<AEKey, BigInteger> o) {
        for (var e : o.entrySet()) {
            BigInteger t = target.getOrDefault(e.getKey(), BigInteger.ZERO).subtract(e.getValue());
            if (t.signum() <= 0) target.remove(e.getKey()); else target.put(e.getKey(), t);
        }
    }
    
    /** Subtract o's FULL subtree effect (recursively via its needs) from target. */
    private void subtractBundle(Bundle target, Bundle o) {
        target.bytes = target.bytes.subtract(o.bytes);
        subtractMap(target.used, o.used);
        subtractMap(target.emitted, o.emitted);
        subtractMap(target.missing, o.missing);
        subtractMap(target.internal, o.internal);
        for (var e : o.patterns.entrySet()) {
            BigInteger t = target.patterns.getOrDefault(e.getKey(), BigInteger.ZERO).subtract(e.getValue());
            if (t.signum() <= 0) target.patterns.remove(e.getKey()); else target.patterns.put(e.getKey(), t);
        }
        for (var e : o.needs.entrySet()) {
            Bundle[] sb = bundleCache.get(e.getKey());
            if (sb != null && sb[0] != null) subtractBundle(target, sb[0].scale(e.getValue()));
        }
    }
    
    public CraftingVM(Object networkKey, Function<AEKey, IPatternDetails> patternResolver) {
        this.networkKey = networkKey;
        this.patternResolver = patternResolver;
    }

    /**
     * Allows a cached/reused VM to swap its resolver (e.g. per-request resolver cache)
     * without rebuilding the instance — the {@link #bundleCache} persists across calls.
     */
    public void setPatternResolver(Function<AEKey, IPatternDetails> patternResolver) {
        this.patternResolver = patternResolver;
    }
    
    public ICraftingPlan execute(CraftingBytecode requestBytecode, CraftingSimulationState simulation) {
        // VM instances are cached and reused across requests (the bundleCache survives
        // between calls — see the cache-hygiene pass at the top of the 3-arg execute).
        // Synchronize so concurrent requests on a reused VM never interleave their
        // per-request execution state.
        synchronized (this) {
            return execute(requestBytecode, simulation,
                BigInteger.valueOf(requestBytecode.getOutputAmountPerCraft()));
        }
    }
    
    private ICraftingPlan execute(CraftingBytecode requestBytecode, CraftingSimulationState simulation, 
                                   BigInteger requestedAmount) {
        this.stack = new BigInteger[MAX_STACK];
        this.sp = 0;
        this.callStack = new ArrayDeque<>(MAX_CALL_DEPTH);
        resolvingKeys.clear();
        this.usedItems = new KeyCounter();
        this.missingItems = new KeyCounter();
        this.ecoExternalItems = new KeyCounter();
        this.emittedItems = new KeyCounter();
        this.simInternal = new KeyCounter();
        this.patternTimes = new HashMap<>();
        this.simulation = simulation;
        this.nodeCount = 1;
        this.rootCraftTimes = 0;
        this.batchRemainder = null;
        this.aggregated = false;
        this.outputKey = requestBytecode.getOutput();
        this.extractIsClaim = false;
        // CRITICAL (v1.9.5): realStockCache is a lazily-snapshotted network inventory
        // cache. It MUST be reset on every execute() — the VM instance is now reused
        // across requests (JIT bundleCache persistence), so a stale snapshot from an
        // earlier request would make realStockOf() return outdated stock and the
        // stock-aware aggregation would compute wrong quantities.
        this.realStockCache = null;
        this.stockFromNetwork = new HashMap<>();
        resolvingKeys.clear();
        circularCache.clear();
        cyclicCraftKeys.clear();
        jitFailCache.clear();
        // (v1.9.11) Cache hygiene no longer DROPS bundles whose missing is non-empty.
        // Their `missing` is a capture-time snapshot; applyBundleDirect now re-verifies
        // it against the live sandbox (extract if stock now exists, else missing), so a
        // stale snapshot can no longer leak into a new plan. Dropping was catastrophic
        // for the JIT: under empty stock every low-level bundle had non-empty missing,
        // so the drop cascaded to the entire chain (v1.9.10) and every request fully
        // re-captured. Keeping every bundle cached preserves the structure (sub-bundles
        // stay present → subBundlesComplete passes → high reuse) while missing is
        // always recomputed live.
        
        long vmStartNs = System.nanoTime(); // total calc time (capture + aggregation + buildPlan)
        
        loadBytecode(requestBytecode);
        
        // Opcodes (hardcoded to match Opcode.java): 0=PUSH_ITEM,1=PUSH_LONG,2=ADD,3=SUB,4=MUL,5=DIV_ROUNDUP,
        // 6=EXTRACT,7=RECORD_OUTPUT,8=RECORD_INGREDIENT,9=RECORD_MISSING,
        // 10=DUP,11=POP,12=SWAP,13=RECORD_PATTERN,14=CALL,15=RETURN,16=CALL_BY_KEY,17=INSERT_OUTPUT,255=HALT
        while (pc < code.length) {
            int op = code[pc++] & 0xFF;
            switch (op) {
                case 0 -> { int idx=readShort(); long cnt=readLong(); pushL(popL()*cnt); } // PUSH_ITEM
                case 1 -> pushL(readLong()); // PUSH_LONG
                case 2 -> { // ADD with overflow detection
                    long b=popL(), a=popL(), r=a+b;
                    if (((a^r)&(b^r)) < 0) { push(BigInteger.valueOf(a).add(BigInteger.valueOf(b))); }
                    else pushL(r);
                }
                case 3 -> { // SUB with overflow detection
                    long b=popL(), a=popL(), r=a-b;
                    if (((a^b)&(a^r)) < 0) { push(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b))); }
                    else pushL(r);
                }
                case 4 -> { // MUL with overflow detection
                    long b=popL(), a=popL();
                    if ((b&(b-1))==0) { pushL(a << Long.numberOfTrailingZeros(b)); break; } // power-of-2 fast path
                    long r=a*b;
                    if (b!=0 && r/b!=a) { push(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))); }
                    else pushL(r);
                }
                case 5 -> { // DIV_ROUNDUP — bitwise fast path for powers of 2
                    long pc2=popL(), rq=popL();
                    if (pc2 <= 0) { pushL(0); break; }
                    if ((pc2 & (pc2 - 1)) == 0) {
                        pushL((rq + pc2 - 1) >>> Long.numberOfTrailingZeros(pc2));
                    } else {
                        pushL((rq + pc2 - 1) / pc2);
                    }
                }
                case 6 -> { // EXTRACT_INGREDIENT
                    int idx = readShort(); AEKey key = constantPool[idx]; long needed = popL();
                    if (needed <= 0) { pushL(0); break; }
                    simulation.addStackBytes(key, 1, needed); nodeCount++;
                    long got = simulation.extract(key, needed, Actionable.MODULATE);
                    if (got > 0) {
                        long internal = simInternal.get(key);
                        long fromInternal = Math.min(got, internal);
                        if (fromInternal > 0) simInternal.add(key, -fromInternal);
                        long fromNetwork = got - fromInternal;
                        if (!extractIsClaim && fromNetwork > 0) {
                            usedItems.add(key, fromNetwork);
                        }
                    }
                    extractIsClaim = false;
                    pushL(Math.max(0, needed - got));
                }
                case 7 -> { readShort(); popL(); } // RECORD_OUTPUT
                case 8 -> { readShort(); popL(); } // RECORD_INGREDIENT (legacy)
                case 9 -> { int idx=readShort(); long cnt=popL(); if(cnt>0) missingItems.add(constantPool[idx], cnt); } // RECORD_MISSING
                case 10 -> push(peek()); // DUP
                case 11 -> popL(); // POP
                case 12 -> { long b=popL(),a=popL(); pushL(b); pushL(a); } // SWAP
                case 13 -> { // RECORD_PATTERN
                    int idx = readShort(); IPatternDetails pat = patternPool[idx]; long times = popL();
                    if (times > 0) {
                        patternTimes.merge(pat, times, Long::sum);
                        simulation.addCrafting(pat, times);
                        simulation.addBytes((double)times);
                    }
                }
                case 14 -> { // CALL
                    int pidx = readShort(); IPatternDetails pat = patternPool[pidx]; long ct = popL();
                    if (ct <= 0) break;
                    boolean isRoot = callStack.isEmpty();
                    if (isRoot) rootCraftTimes = ct;
                    // SELF-GROWTH LOOP CUT (v1.8.26): a pattern whose primary output is
                    // also the key of EVERY one of its own inputs (A -> 2A) can never be
                    // fired — firing it duplicates items from nothing (an AE2 exploit).
                    // Its demand is satisfied ONLY from stock; the shortfall is missing.
                    // This matches Thunderbolt's cycle/self-growth-cut semantics: an
                    // unseeded A->2A loop must never invent its first A. We intercept at
                    // the ROOT request so the loop never runs at all.
                    if (isRoot && isUnseededSelfLoop(pat)) {
                        rootCraftTimes = 0; // aggregation must not double-report below
                        long itemReq = toLongSafe(requestedAmount, "selfloop");
                        simulation.addStackBytes(outputKey, 1, itemReq); nodeCount++;
                        long got = simulation.extract(outputKey, itemReq, Actionable.MODULATE);
                        long internal = simInternal.get(outputKey);
                        long fromInternal = Math.min(got, internal);
                        if (fromInternal > 0) simInternal.add(outputKey, -fromInternal);
                        long fromNetwork = got - fromInternal;
                        if (fromNetwork > 0) usedItems.add(outputKey, fromNetwork);
                        long shortfall = itemReq - got;
                        if (shortfall > 0) missingItems.add(outputKey, shortfall);
                        break; // never dispatch the self-loop pattern
                    }
                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, pat);
                    if (sbc == null) { PatternCompiler.compileIfAbsent(networkKey, pat); sbc = PatternCompiler.getCompiled(networkKey, pat); }
                    if (sbc == null || callStack.size() >= MAX_CALL_DEPTH) break;
                    if (isRoot) {
                        // Root request frame: capture its bundle (direct effects + direct needs)
                        // so applyAggregation() can compute the full plan in O(patterns) instead
                        // of re-expanding the shared DAG once per path.
                        //
                        // KEY INVARIANT (fix for the ×ct² squaring bug, v1.9.0):
                        // The root pattern MUST run with a per-1-craft stack (pushL(1)), NOT
                        // pushL(ct). Why: the pattern's input EXTRACTs multiply the stack craft
                        // count by each input multiplier, so with pushL(ct) the root bundle's
                        // `needs` are scaled by the REQUEST quantity ct (e.g. ct=1000 -> needs
                        // = 1000 per craft). applyAggregation() then multiplies those needs by
                        // rootCraftTimes=ct again, producing total[sub] = ct² x per-craft
                        // (SQUARED output). plan1 (ct=1) was correct only because 1²=1, hiding
                        // the bug. With pushL(1) the root bundle is a true per-1-craft delta
                        // (needs = 1, emitted = 1 output), and the aggregation's seed
                        // total[outputKey] = rootCraftTimes=ct scales every sub linearly.
                        Bundle snap = captureDelta();
                        // RECURSION FIX (v1.9.1): mark the root's output key as "in progress"
                        // so ANY self-reference in its own recipe (or a cycle through another
                        // pattern back to it) is detected IMMEDIATELY as a cycle (consume
                        // available stock / mark missing) instead of being dispatched as a
                        // fresh depth-1 sub-capture. Without this, a recursive pattern's
                        // self-reference ran with rkContains=false (outputKey was NOT in
                        // resolvingKeys), which (a) created a self-needs edge in the bundle
                        // graph and (b) could overwrite bundleCache[outputKey][0] with the
                        // depth-1 sub-bundle — producing order/stock-dependent wrong counts
                        // (creative_ae_cell_long = 17 instead of 1e9) and +50 bogus patterns.
                        // The root's RETURN removes the key again (resolvingKeys.remove).
                        resolvingKeys.add(outputKey);
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, outputKey)
                            .withBundle(outputKey, snap, ct));
                        loadBytecode(sbc); pushL(1);
                    } else {
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                        loadBytecode(sbc); pushL(ct);
                    }
                }
                case 15 -> { if(callStack.isEmpty()){pc=code.length;break;} // RETURN
                    CallFrame f=callStack.pop(); code=f.code; constantPool=f.constantPool;
                    patternPool=f.patternPool; pc=f.returnPc;
                    // Sub-pattern has completed: its outputs are in simInternal.
                    // The following claim EXTRACT must not re-add them to usedItems.
                    extractIsClaim = true;
                    if(f.resolvingKey!=null) {
                        // Bundle creation: compute delta from sandbox execution
                        if(f.bundleKey!=null && f.bundleKey.equals(f.resolvingKey)) {
                            Bundle after = captureDelta();
                            Bundle delta = diffBundle(after, f.bundleBefore);
                            // Record direct sub-call needs. Sub-tree effects are applied via
                            // these sub-bundles on replay — they are NEVER folded into this
                            // bundle, so scaling cannot double-count (the ×3-per-level bug).
                            if (f.subCalls != null && !f.subCalls.isEmpty()) {
                                for (var sc : f.subCalls.entrySet()) {
                                    AEKey sk = sc.getKey();
                                    long sreq = sc.getValue();
                                    // DIVERGENT 2-CYCLE FIX: a sub-craft that hit a cross-cycle
                                    // during its own capture is NOT craftable this execution — it
                                    // can only come from stock. Skipping it from itemNeeds prevents
                                    // the aggregation from giving it a craft demand and scaling its
                                    // `used` (the dust_steel ↔ ingot_steel case: pulverizing dust
                                    // needs ingot → its used{ingot}×crafts produced a false 175K
                                    // ingot missing). The parent's used-extraction still consumes
                                    // whatever stock exists and marks the shortfall missing.
                                    if (cyclicCraftKeys.contains(sk)) continue;
                                    long sopc = 1;
                                    var sbc = PatternCompiler.getCompiled(networkKey, patternResolver.apply(sk));
                                    if (sbc != null) sopc = sbc.getOutputAmountPerCraft();
                                    // Record BOTH the per-craft ITEM need (drives the
                                    // aggregation's item-demand → ceil(itemDemand/opc)
                                    // conversion) and the rounded per-craft craft count
                                    // (kept for diagnostics / backward compatibility).
                                    if (sreq > 0) {
                                        delta.itemNeeds.merge(sk, BigInteger.valueOf(sreq), BigInteger::add);
                                        if (sopc > 0) {
                                            long scts = (sreq + sopc - 1) / sopc;
                                            if (scts > 0) delta.needs.merge(sk, BigInteger.valueOf(scts), BigInteger::add);
                                        }
                                    }
                                }
                            }
                            Bundle[] bundles = bundleCache.computeIfAbsent(f.resolvingKey, k -> new Bundle[MAX_BUNDLE_BITS]);
                            bundles[0] = delta;
                            resolvingKeys.remove(f.resolvingKey);
                            boolean enclosingCapture = !callStack.isEmpty() && callStack.peek().bundleKey() != null;
                            if (callStack.isEmpty()) {
                                // Root request frame: its bundle (direct effects + direct needs)
                                // is stored for the final aggregation. Undo this 1-craft's direct
                                // effects on the simulation; applyAggregation() replays everything
                                // exactly once. Never rewind/apply here.
                                revertBundle(delta);
                                extractIsClaim = true;
                            } else if (enclosingCapture) {
                                // Capture context: a parent is building its bundle. Undo this
                                // 1-craft's applied DIRECT effects; the parent references us via
                                // needs and will apply our bundle on replay.
                                revertBundle(delta);
                                extractIsClaim = true;
                            } else if (f.savedReq > 1) {
                                // Apply context, cts>1: undo the 1-craft, rewind and re-execute
                                // so the CALL_BY_KEY applies the scaled bundle (direct + needs).
                                // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] revert+rewind {} savedReq={}", f.resolvingKey, f.savedReq);
                                revertBundle(delta);
                                pushL(f.savedReq);
                                pc = f.returnPc - 3;
                            } else {
                                // Apply context, cts==1: undo the applied direct effects then
                                // re-apply direct + needs so the full single-craft effect stands.
                                revertBundle(delta);
                                applyBundle(delta);
                                extractIsClaim = true;
                            }
                        } else {
                            resolvingKeys.remove(f.resolvingKey);
                            extractIsClaim = true;
                        }
                    }
                }
                case 16 -> { // CALL_BY_KEY with JIT for cts>1
                    int kidx = readShort(); AEKey tk = constantPool[kidx]; long req = popL();
                    if (req <= 0) break;
                    if (tk == null) { // corrupt/edge-case constant-pool entry — cannot craft
                        // LOG disabled: AE2VMAddon.LOGGER.warn("[AE2-VM] CALL_BY_KEY with null constant entry (kidx={})", kidx);
                        break;
                    }
                    // TEMP DIAG removed (v1.9.1): was logging every call to find the
                    // leaf-drop branch. No longer needed.
                    // AE2VMAddon.LOGGER.info("[AE2-VM DIAG] CALL {} req={} depth={} rkContains={}", tk, req,
                    //     callStack.size(), resolvingKeys.contains(tk));
                    IPatternDetails sub = patternResolver.apply(tk);
                    if (sub == null) {
                        AEKey ck = tk.dropSecondary();
                        if (!ck.equals(tk)) sub = patternResolver.apply(ck);
                    }
                    if (sub == null) {
                        // No sub-pattern: the following EXTRACT opcode consumes the item
                        // from stock (and records used). We only PRE-MARK the residual
                        // shortfall as missing via SIMULATE — NOT a MODULATE extract.
                        // The old code extracted here AND the EXTRACT below consumed the
                        // same items a second time, doubling every leaf input's used count
                        // (gold_essence 16k per craft instead of 8k in the v1.8.16 logs).
                        // (FIX for no-pattern false-missing + double-extraction, v1.8.17)
                        // (v1.9.13) FUZZY / FLUID substitution: when the encoded pattern
                        // enables item/fluid replacement (getPossibleInputs() returns
                        // multiple variants, registered as a fuzzy group), the primary
                        // variant may be absent from stock while another variant (e.g.
                        // white wool for a gray-wool template) satisfies the need. Only
                        // report missing if NO variant's stock covers the requirement.
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long availSim = 0;
                        for (AEKey variant : PatternCompiler.getFuzzyGroup(tk)) {
                            availSim += simulation.extract(variant, req, Actionable.SIMULATE);
                        }
                        long shortfall = req - availSim;
                        if (shortfall > 0) missingItems.add(tk, shortfall);
                        break;
                    }
                    // SELF-GROWTH LOOP CUT (v1.8.26): the resolved sub-pattern is an
                    // unseeded A->2A loop — firing it would duplicate items from nothing.
                    // Treat it exactly like a cycle: consume whatever stock exists and
                    // mark the shortfall missing, never dispatch the pattern.
                    if (isUnseededSelfLoop(sub)) {
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long gotx = simulation.extract(tk, req, Actionable.MODULATE);
                        if (gotx > 0) {
                            long internal = simInternal.get(tk);
                            long fromInternal = Math.min(gotx, internal);
                            if (fromInternal > 0) simInternal.add(tk, -fromInternal);
                            long fromNetwork = gotx - fromInternal;
                            if (fromNetwork > 0) usedItems.add(tk, fromNetwork);
                        } else {
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    PatternCompiler.compileIfAbsent(networkKey, sub);
                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, sub);
                    if (sbc == null) { missingItems.add(tk, req); break; }
                    if (callStack.size() >= MAX_CALL_DEPTH) {
                        // LOG disabled: AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → MAX_CALL_DEPTH {} DROP", tk, req, callStack.size());
                        missingItems.add(tk, req); break;
                    }
                    if (circularCache.contains(tk)) {
                        // LOG disabled: AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → circular (cached) → missing", tk, req);
                        missingItems.add(tk, req); break;
                    }
                    if (!resolvingKeys.add(tk)) {
                        // Cycle: the pattern needs its own output. Consume whatever the network
                        // actually holds instead of marking the whole request missing.
                        // LOG disabled: AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → cycle, consuming available stock", tk, req);
                        circularCache.add(tk);
                        // DIVERGENT 2-CYCLE FIX (dust_steel ↔ ingot_steel smelting/pulverizing):
                        // If this cyclic call happens while CAPTURING another key (the pattern
                        // being built needs an ancestor → a cross-cycle), that capturing key
                        // cannot be satisfied by crafting — it can only come from stock. Mark it
                        // so the parent's RETURN skips it from itemNeeds (stock-only leaf) and
                        // the aggregation never gives it a craft demand. A pure self-loop (the
                        // capturing key == the cyclic call target) is left as-is.
                        CallFrame capFrame = callStack.peek();
                        if (capFrame != null && capFrame.bundleKey() != null && !capFrame.bundleKey().equals(tk)) {
                            cyclicCraftKeys.add(capFrame.bundleKey());
                        }
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long gotx = simulation.extract(tk, req, Actionable.MODULATE);
                        if (gotx > 0) {
                            long internal = simInternal.get(tk);
                            long fromInternal = Math.min(gotx, internal);
                            if (fromInternal > 0) simInternal.add(tk, -fromInternal);
                            long fromNetwork = gotx - fromInternal;
                            if (fromNetwork > 0) usedItems.add(tk, fromNetwork);
                        } else {
                            // Nothing consumable from the cycle → the demand is genuinely missing.
                            // (Do NOT silently drop it — that caused non-deterministic under-counting.)
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    long opc = sbc.getOutputAmountPerCraft();
                    long cts = opc <= 0 ? 0 : (req + opc - 1) / opc;
                    if (cts <= 0) { resolvingKeys.remove(tk); break; }
                    
                    // Record this direct sub-call on the enclosing dispatch frame, so the
                    // parent bundle captures only DIRECT effects (sub-tree via needs).
                    boolean capturing = !callStack.isEmpty() && callStack.peek().bundleKey() != null;
                    if (!callStack.isEmpty()) callStack.peek().recordSubCall(tk, req);
                    
                    Bundle[] bundles = bundleCache.computeIfAbsent(tk, k -> new Bundle[MAX_BUNDLE_BITS]);
                    
                    if (capturing) {
                        // Building a parent's bundle: do NOT apply this sub-call now. It is
                        // referenced via the parent's needs and applied on replay. Only make
                        // sure the sub-bundle exists (dispatch a 1-craft to build it).
                        if (bundles[0] == null) {
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                            // resolvingKeys stays set until RETURN captures the bundle.
                        } else if (!subBundlesComplete(bundles[0])) {
                            // REUSE-DEPENDENCY CHECK (v1.9.8): the cached bundle's itemNeeds
                            // reference sub-bundles that are no longer cached — cache hygiene
                            // dropped them because their missing was non-empty (e.g. EMPTY
                            // network stock, where every low-level craftable node is missing).
                            // Reusing this bundle skips re-dispatching its bytecode, so those
                            // sub-crafts are never re-captured and the aggregation sees them
                            // bundle-less → the entire recipe chain is lost between requests
                            // ("缓存配方丢失", 926K→364K). Re-capture this bundle so its
                            // bytecode re-dispatches the missing sub-chain.
                            resolvingKeys.remove(tk);
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                        } else {
                            resolvingKeys.remove(tk);
                        }
                        break;
                    }
                    
                    // cts==1: check JIT memoization cache first
                    if (cts == 1) {
                        if (bundles[0] == null) {
                            // First call: execute normally, capture bundle[0] on RETURN
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, 1));
                            loadBytecode(sbc); pushL(cts);
                            break;
                        }
                        if (jitFailCache.contains(tk)) {
                            // Known-unsatisfiable memo: skip re-check, run normal exec
                            resolvingKeys.remove(tk);
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                            loadBytecode(sbc); pushL(1);
                            break;
                        }
                        // Re-check satisfiability: memo assumes stock unchanged since capture,
                        // but the shared network may be exhausted by earlier work.
                        Bundle b0 = bundles[0];
                        boolean sat1ok = true;
                        for (var e : b0.used.entrySet()) {
                            long usedPerCall = toLongSafe(e.getValue(), "sat:" + e.getKey());
                            long internalPerCall = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            long netDrain = Math.max(0, usedPerCall - internalPerCall);
                            if (netDrain == 0) continue;
                            long totalAvail = simulation.extract(e.getKey(), netDrain, Actionable.SIMULATE);
                            long vmInternal = simInternal.get(e.getKey());
                            long realAvail = Math.max(0, totalAvail - vmInternal);
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM]   JIT cts=1 check {} used/call={} int/call={} netDrain={} totalAvail={} vmInternal={} realAvail={}",
                            //     e.getKey(), usedPerCall, internalPerCall, netDrain, totalAvail, vmInternal, realAvail);
                            if (realAvail < netDrain) { sat1ok = false; break; }
                        }
                        if (sat1ok) {
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] cts=1 hit {} → apply memo", tk);
                            applyBundle(b0);
                            extractIsClaim = true;
                            resolvingKeys.remove(tk);
                            break;
                        }
                        // Stale memo: mark fail + run normal exec
                        jitFailCache.add(tk);
                        // LOG disabled: AE2VMAddon.LOGGER.warn("[AE2-VM JIT] cts=1 memo {} unsatisfiable → normal exec", tk);
                        resolvingKeys.remove(tk);
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                        loadBytecode(sbc); pushL(1);
                        break;
                    }
                    
                    // cts>1: if bundle[0] exists, apply it O(1) — self-sufficient → exact
                    // scale-apply; otherwise deficit-apply (records extraction shortfalls as
                    // missing, never re-executes, so shared DAG nodes are not re-expanded
                    // once per path and counts stay correct). If bundle[0] is missing,
                    // DISPATCH a single 1-craft to build it (recursive dispatch — nested
                    // calls also capture their own bundles), then RETURN's revert+rewind
                    // replays the batch through the bundle in O(1).
                    if (bundles[0] != null) {
                        Bundle b0 = bundles[0];
                        
                        // Fast path: self-sufficient (internal >= used for all items).
                        boolean selfSufficient = true;
                        for (var e : b0.used.entrySet()) {
                            long internal = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            if (toLongSafe(e.getValue(), "jit") > internal) { selfSufficient = false; break; }
                        }
                        if (selfSufficient) {
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] self-sufficient {} cts={}", tk, cts);
                            // O(1) apply of the scaled bundle instead of O(cts) sequential applies
                            // (Bundle effects are linear in craft count — identical result).
                            applyBundle(b0.scale(cts));
                            resolvingKeys.remove(tk);
                            break;
                        }
                        
                        // Non-self-sufficient: O(1) deficit-apply of the FULL scaled bundle.
                        // b0 is the true per-craft subtree effect; scaling by cts aggregates
                        // all cts crafts. Extraction shortfalls become missing items. We do
                        // NOT fall back to re-running the pattern bytecode here: that would
                        // re-expand shared DAG subtrees once per path and inflate counts.
                        // AE2VMAddon.LOGGER.info("[AE2-VM JIT] cts>1 deficit-apply {} cts={}", tk, cts);
                        applyBundleDeficit(b0.scale(cts));
                        resolvingKeys.remove(tk);
                        break;
                    }
                    
                    // No bundle yet: dispatch a single 1-craft to capture bundle[0].
                    // Recursive dispatch — nested cts>1 calls inside this 1-craft also
                    // capture their own bundles (via the cts==1 path), so sibling reuse
                    // is memoized and the Fibonacci chain collapses to O(patterns).
                    // RETURN (savedReq>1) reverts this 1-craft and rewinds to `req`
                    // (the original item amount), so the re-executed CALL_BY_KEY
                    // recomputes cts = ceil(req/opc) correctly for opc>1 patterns.
                    Bundle snap = captureDelta();
                    callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                        .withBundle(tk, snap, req));
                    loadBytecode(sbc); pushL(1);
                }
                case 17 -> { int idx=readShort(); long amt=popL(); // INSERT_OUTPUT
                    if(amt>0){
                        simulation.insert(constantPool[idx],amt,Actionable.MODULATE);
                        simInternal.add(constantPool[idx], amt);
                        // Always record the crafted output in emittedItems (matches AE2's
                        // CraftingTreeProcess.emitItems). The final requested item is
                        // removed again in buildPlan, so intermediates show up correctly
                        // instead of the plan always reporting emit=0.
                        emittedItems.add(constantPool[idx], amt);
                    }
                }
                case 255 -> { // HALT
                    simulation.addBytes(nodeCount*8.0);
                    if(rootCraftTimes>0&&outputKey!=null) simulation.addStackBytes(outputKey,1,rootCraftTimes);
                    ICraftingPlan plan = buildPlan(requestedAmount);
                    logPerfLine(vmStartNs);
                    return plan; }
                default -> {} // unknown opcode, skip
            }
        }
        ICraftingPlan plan = buildPlan(requestedAmount);
        logPerfLine(vmStartNs);
        return plan;
    }

    /**
     * Performance log: total calc time for this request (microsecond precision — the
     * VM is often sub-millisecond, so a whole-ms value would show 0ms).
     */
    private void logPerfLine(long vmStartNs) {
        long calcUs = (System.nanoTime() - vmStartNs) / 1_000;
        AE2VMAddon.LOGGER.info("[AE2-VM] calc time: {} us ({} ms)", calcUs, String.format("%.2f", calcUs / 1000.0D));
    }
    
    private CraftingPlan buildPlan(BigInteger requestedAmount) {
        // Replay every captured bundle exactly once (aggregated totals).
        applyAggregation();
        // Extension-provided items: produced externally → treat as emitted (will be crafted)
        if (!ecoExternalItems.isEmpty())
            for (AEKey k : ecoExternalItems.keySet()) {
                usedItems.remove(k);
                emittedItems.add(k, ecoExternalItems.get(k));
            }
        
        // finalOutput already separate in CraftingPlan — must not duplicate in emittedItems
        emittedItems.remove(outputKey);
        
        // PLAN/USED/CRAFT/MISS logging disabled (v1.8.20) — keep log clean, only total time.
        // (v1.9.13-DIAG) TEMPORARY: log every missing with hasPattern + used, for the
        // "1x / 1b missing but 2x / 100b OK" server report (NAST). Remove after diagnosis.
        if (!missingItems.isEmpty()) {
            StringBuilder sb = new StringBuilder("[AE2-VM DIAG-MISS] root=").append(outputKey)
                    .append(" rootCraftTimes=").append(rootCraftTimes).append(" missing:");
            for (var e : missingItems) {
                boolean hasPattern = patternResolver != null && patternResolver.apply(e.getKey()) != null;
                sb.append(" ").append(e.getLongValue()).append("x").append(e.getKey())
                        .append(hasPattern ? "(PATTERN)" : "(leaf)");
            }
            AE2VMAddon.LOGGER.info(sb.toString());
        }
        // DIAGNOSTIC disabled (v1.9.1): usedItems vs real network stock comparison.
        // try {
        //     if (networkKey instanceof appeng.api.networking.IGrid grid) {
        //         var storage = grid.getStorageService();
        //         if (storage != null) {
        //             var realStock = storage.getInventory().getAvailableStacks();
        //             for (var e : usedItems) {
        //                 long avail = realStock.get(e.getKey());
        //                 if (avail < e.getLongValue()) {
        //                     AE2VMAddon.LOGGER.warn("[AE2-VM]   USED-SHORTFALL {}: need={} network={}", e.getKey(), e.getLongValue(), avail);
        //                 }
        //             }
        //         }
        //     }
        // } catch (Throwable t) {
        //     AE2VMAddon.LOGGER.warn("[AE2-VM] usedItems-vs-network diagnostic failed: {}", t.toString());
        // }
        
        long bytes = (long)Math.ceil(((com.ae2vm.addon.mixin.CraftingSimulationStateAccessor)simulation).getBytes());
        long deliver;
        if (requestedAmount.compareTo(BIG_MAX_LONG) > 0) {
            deliver = Long.MAX_VALUE; batchRemainder = requestedAmount.subtract(BIG_MAX_LONG);
        } else { deliver = requestedAmount.longValue(); batchRemainder = null; }
        // CRITICAL: a complete plan (no missing items) must be simulation=false, otherwise
        // AE2's submitJob() rejects it (INCOMPLETE_PLAN) and the craft never starts.
        // Only plans that are missing ingredients are simulation=true (preview-only).
        boolean simulation = !missingItems.isEmpty();
        return new CraftingPlan(new GenericStack(outputKey, deliver), bytes, simulation, false,
            usedItems, emittedItems, missingItems, new HashMap<>(patternTimes));
    }
    
    public BigInteger getBatchRemainder() { return batchRemainder; }
    
    // Stack ops (BigInteger for unlimited precision, guardless for speed)
    private void push(BigInteger v) { stack[sp++] = v; }
    private void pushL(long v) {
        stack[sp++] = v >= 0 && v < 1024 ? BIG_CACHE[(int)v] : BigInteger.valueOf(v);
    }
    private BigInteger pop() { return stack[--sp]; }
    private long popL() { return stack[--sp].longValue(); }
    private BigInteger peek() { return stack[sp - 1]; }
    
    private void loadBytecode(CraftingBytecode bc) {
        code = bc.getCode(); constantPool = bc.getConstantPool();
        patternPool = bc.getPatternPool(); pc = 0;
    }
    private int readShort() { return ((code[pc++]&0xFF)<<8)|(code[pc++]&0xFF); }
    private long readLong() {
        return ((long)(code[pc++]&0xFF)<<56)|((long)(code[pc++]&0xFF)<<48)
              |((long)(code[pc++]&0xFF)<<40)|((long)(code[pc++]&0xFF)<<32)
              |((long)(code[pc++]&0xFF)<<24)|((long)(code[pc++]&0xFF)<<16)
              |((long)(code[pc++]&0xFF)<<8) |(code[pc++]&0xFF);
    }
}

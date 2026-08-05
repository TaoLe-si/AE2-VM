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
    private final Function<AEKey, IPatternDetails> patternResolver;
    
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
    private final java.util.Set<AEKey> jitFailCache = new java.util.HashSet<>(); // patterns whose JIT memo is unsatisfiable → run normal exec
    private boolean extractIsClaim; // RETURN sets this; next EXTRACT skips usedItems (sub-craft claim)
    
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
            missingItems.add(e.getKey(), toLongSafe(e.getValue(), "miss:" + e.getKey()));
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
                        BigInteger crafts = demand.add(BigInteger.valueOf(opc - 1)).divide(BigInteger.valueOf(opc));
                        total.put(c, crafts);
                        queue.add(c);
                    }
                }
            }
        }
        // Phase 3: apply each bundle exactly once, children before parents.
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
        applyBundleDirect(arr[0].scale(t));
    }
    
    /** Undo a bundle's effects — reverse order of apply. */
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
    
    public ICraftingPlan execute(CraftingBytecode requestBytecode, CraftingSimulationState simulation) {
        return execute(requestBytecode, simulation, 
            BigInteger.valueOf(requestBytecode.getOutputAmountPerCraft()));
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
        resolvingKeys.clear();
        circularCache.clear();
        jitFailCache.clear();
        
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
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long availSim = simulation.extract(tk, req, Actionable.SIMULATE);
                        long shortfall = req - availSim;
                        if (shortfall > 0) missingItems.add(tk, shortfall);
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
                    AE2VMAddon.LOGGER.info("[AE2-VM] calc time: {} ms", (System.nanoTime() - vmStartNs) / 1_000_000);
                    return plan; }
                default -> {} // unknown opcode, skip
            }
        }
        ICraftingPlan plan = buildPlan(requestedAmount);
        AE2VMAddon.LOGGER.info("[AE2-VM] calc time: {} ms", (System.nanoTime() - vmStartNs) / 1_000_000);
        return plan;
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
        // AE2VMAddon.LOGGER.info("[AE2-VM] === PLAN: used={} craft={} miss={}", usedItems.size(), patternTimes.size(), missingItems.size());
        // for (var e : usedItems) AE2VMAddon.LOGGER.info("[AE2-VM]   USED {} x {}", e.getLongValue(), e.getKey());
        // Crafted intermediates are tracked via patternTimes (AE2 convention), which the
        // GUI's "to craft" column reads. emittedItems only holds emit-source items.
        // for (var e : patternTimes.entrySet()) {
        //     var out = e.getKey().getPrimaryOutput();
        //     AE2VMAddon.LOGGER.info("[AE2-VM]   CRAFT {} x {} (pattern={})", e.getValue() * out.amount(), out.what(), e.getKey());
        // }
        // for (var e : missingItems) {
        //     boolean hasPattern = patternResolver != null && patternResolver.apply(e.getKey()) != null;
        //     AE2VMAddon.LOGGER.info("[AE2-VM]   MISS {} x {} (hasPattern={})", e.getLongValue(), e.getKey(), hasPattern);
        // }
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

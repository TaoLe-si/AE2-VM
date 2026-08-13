package com.ae2vm.addon.vm;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import com.ae2vm.addon.AE2VMAddon;
import com.ae2vm.addon.api.AEKey;
import com.ae2vm.addon.api.CraftingPlan;
import com.ae2vm.addon.api.CraftingSimulationState;
import com.ae2vm.addon.api.GenericStack;
import com.ae2vm.addon.api.ICraftingPlan;
import com.ae2vm.addon.api.IPatternDetails;
import com.ae2vm.addon.api.KeyCounter;
import com.ae2vm.addon.compiler.PatternCompiler;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Collections;
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
 * 2) The recipe DAG is ACYCLIC (small smelting/pulverizing cycles still exist;
 *    applyAggregation() cuts cycle back-edges).
 * 3) emittedItems MUST NOT contain crafted intermediates (AE2 GUI 2×).
 * 4) INSERT_OUTPUT records output in BOTH emittedItems and simInternal with a
 *    SINGLE simulation.insert.
 * 5) Root-capture mode: ZERO applies during bytecode execution; effects applied
 *    exactly once by applyAggregation() inside buildPlan().
 * 6) applyBundleDirect is deficit-aware for `used` (shortfall → missing).
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
    // (v1.10.3) Optional resolver returning ALL patterns that produce a key (not just the
    // one chosen by patternResolver). Used ONLY by the pure-conversion-ring feasibility
    // analysis. The benchmark harness supplies it; null falls back to the chosen pattern.
    private Function<AEKey, java.util.List<IPatternDetails>> allPatternsResolver;

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
    // (v1.10.x CATALYST) One-time catalyst/container seed demands (key → total seed amount).
    private KeyCounter catalystSeedItems;
    // (v1.10.x DURABILITY) Finite-use tool rates (key → [amount, uses]).
    private java.util.Map<AEKey, long[]> durabilityItems;
    private Map<IPatternDetails, Long> patternTimes;
    private CraftingSimulationState simulation;
    private AEKey outputKey;
    private long nodeCount;
    private long rootCraftTimes;
    private BigInteger batchRemainder;
    private boolean aggregated;

    private final java.util.Set<AEKey> resolvingKeys = new java.util.HashSet<>();
    private final java.util.Set<AEKey> circularCache = new java.util.HashSet<>(); // keys that formed a cycle this execution
    private final java.util.Set<AEKey> cyclicCraftKeys = new java.util.HashSet<>(); // sub-crafts whose pattern hits a cross-cycle → stock-only
    private final java.util.Set<AEKey> jitFailCache = new java.util.HashSet<>(); // patterns whose JIT memo is unsatisfiable → run normal exec
    // O(1): lazily snapshotted real network stock.
    private KeyCounter realStockCache;
    // (v1.10.x CATALYST) Initial network stock snapshot taken at the very START of execute().
    private KeyCounter executeStartStock;
    // (v1.10.3 RECURSION) Root request size (BigInteger from execute).
    private BigInteger requestAmount;
    // (v1.10.3 RECURSION) Self-adjacent patterns: patternKey → (selfKey → {in, out} per craft).
    private Map<AEKey, Map<AEKey, long[]>> selfAdjacentKeys;
    private boolean extractIsClaim; // RETURN sets this; next EXTRACT skips usedItems (sub-craft claim)
    // (v1.10.x video fix) Set by FUZZY_SLOT and consumed by the NEXT CALL_BY_KEY.
    private boolean currentSlotFuzzy;
    // (v1.9.5) Network stock consumed via the stock-aware sub-craft branch, per key.
    private java.util.Map<AEKey, BigInteger> stockFromNetwork;

    // JIT: per-pattern power-of-2 bundles. Bundle[0]=1 run, Bundle[k]=2^k runs.
    private static final int MAX_BUNDLE_BITS = 64; // long bits 0–63
    private final Map<AEKey, Bundle[]> bundleCache = new HashMap<>();

    private static final class CallFrame {
        final int returnPc;
        final byte[] code;
        final AEKey[] constantPool;
        final IPatternDetails[] patternPool;
        final AEKey resolvingKey;
        final AEKey bundleKey;
        final Bundle bundleBefore;
        final long savedReq;
        final Map<AEKey, Long> subCalls;
        final Map<AEKey, Long> fuzzySubCalls;

        CallFrame(int returnPc, byte[] code, AEKey[] constantPool,
                  IPatternDetails[] patternPool, AEKey resolvingKey) {
            this(returnPc, code, constantPool, patternPool, resolvingKey, null, null, 0L, null, null);
        }

        CallFrame(int returnPc, byte[] code, AEKey[] constantPool,
                  IPatternDetails[] patternPool, AEKey resolvingKey,
                  AEKey bundleKey, Bundle bundleBefore, long savedReq,
                  Map<AEKey, Long> subCalls, Map<AEKey, Long> fuzzySubCalls) {
            this.returnPc = returnPc;
            this.code = code;
            this.constantPool = constantPool;
            this.patternPool = patternPool;
            this.resolvingKey = resolvingKey;
            this.bundleKey = bundleKey;
            this.bundleBefore = bundleBefore;
            this.savedReq = savedReq;
            this.subCalls = subCalls;
            this.fuzzySubCalls = fuzzySubCalls;
        }

        CallFrame withBundle(AEKey key, Bundle before, long req) {
            return new CallFrame(returnPc, code, constantPool, patternPool, resolvingKey, key, before, req,
                    new HashMap<AEKey, Long>(), new HashMap<AEKey, Long>());
        }

        void recordSubCall(AEKey k, long r) {
            if (subCalls != null) subCalls.merge(k, r, Long::sum);
        }

        void recordFuzzySubCall(AEKey k, long r) {
            if (fuzzySubCalls != null) fuzzySubCalls.merge(k, r, Long::sum);
        }
    }

    private static class Bundle {
        BigInteger bytes = BigInteger.ZERO;
        // Concurrent maps so scaling/diffing/capturing can run in parallel safely.
        final Map<AEKey, BigInteger> used = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<AEKey, BigInteger> emitted = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<AEKey, BigInteger> missing = new java.util.concurrent.ConcurrentHashMap<>();
        final Map<AEKey, BigInteger> internal = new java.util.concurrent.ConcurrentHashMap<>(); // VM-inserted items offset
        final Map<IPatternDetails, BigInteger> patterns = new java.util.concurrent.ConcurrentHashMap<>();
        // DIRECT sub-pattern needs: sub-key → crafts.
        final Map<AEKey, BigInteger> needs = new java.util.concurrent.ConcurrentHashMap<>();
        // DIRECT sub-pattern ITEM needs: sub-key → per-craft ITEM amount demanded.
        final Map<AEKey, BigInteger> itemNeeds = new java.util.concurrent.ConcurrentHashMap<>();
        // (v1.10.x video fix) FUZZY sub-pattern ITEM needs.
        final Map<AEKey, BigInteger> fuzzyItemNeeds = new java.util.concurrent.ConcurrentHashMap<>();
        // (v1.10.x CATALYST) ONE-TIME catalyst/container seed demands (key → seed amount).
        final Map<AEKey, BigInteger> seeds = new java.util.concurrent.ConcurrentHashMap<>();
        // (v1.10.x DURABILITY) FINITE-USE tool demands (key → [amount, uses]).
        final Map<AEKey, long[]> durability = new java.util.concurrent.ConcurrentHashMap<>();

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
            fuzzyItemNeeds.forEach((k, v) -> b.fuzzyItemNeeds.put(k, v.multiply(factor)));
            // Seeds are one-time per batch — do NOT multiply them.
            seeds.forEach((k, v) -> b.seeds.put(k, v));
            // Durability is a rate — do NOT multiply the rate.
            durability.forEach((k, v) -> b.durability.put(k, v));
            return b;
        }

        boolean isEmpty() {
            return bytes.signum() == 0 && used.isEmpty() && emitted.isEmpty() && missing.isEmpty()
                && internal.isEmpty() && patterns.isEmpty() && needs.isEmpty() && itemNeeds.isEmpty()
                && fuzzyItemNeeds.isEmpty() && seeds.isEmpty() && durability.isEmpty();
        }
    }

    /** Safe BigInteger→long conversion. Caps at Long.MAX_VALUE. */
    private static long toLongSafe(BigInteger v, String ctx) {
        if (v.compareTo(BIG_MAX_LONG) > 0) {
            return Long.MAX_VALUE;
        }
        if (v.signum() < 0) return 0;
        return v.longValue();
    }

    /** BigInteger→double for byte counts. */
    private static double toBytesDouble(BigInteger v) {
        return v.doubleValue();
    }

    /**
     * Apply a bundle's DIRECT effects exactly once (deficit-aware). Needs are NOT
     * expanded here — applyAggregation() owns all subtree replay.
     */
    private void applyBundleDirect(Bundle b) {
        simulation.addBytes(toBytesDouble(b.bytes));
        for (Map.Entry<AEKey, BigInteger> e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit:" + e.getKey());
            simulation.insert(e.getKey(), val, Actionable.MODULATE);
            simInternal.add(e.getKey(), val);
        }
        for (Map.Entry<AEKey, BigInteger> e : b.used.entrySet()) {
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
        for (Map.Entry<AEKey, BigInteger> e : b.missing.entrySet()) {
            long val = toLongSafe(e.getValue(), "miss:" + e.getKey());
            if (val <= 0) continue;
            // Realtime-verify capture-time missing against the live sandbox.
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
        // (v1.10.x CATALYST) One-time catalyst/container seed demand.
        for (Map.Entry<AEKey, BigInteger> e : b.seeds.entrySet()) {
            long val = toLongSafe(e.getValue(), "seed:" + e.getKey());
            if (val <= 0) continue;
            simulation.addStackBytes(e.getKey(), 1, val); nodeCount++;
            long got = simulation.extract(e.getKey(), val, Actionable.MODULATE);
            if (got > 0) {
                long internal = simInternal.get(e.getKey());
                long fromInternal = Math.min(got, internal);
                if (fromInternal > 0) simInternal.add(e.getKey(), -fromInternal);
                long fromNetwork = got - fromInternal;
                if (fromNetwork > 0) usedItems.add(e.getKey(), fromNetwork);
            }
            if (got < val) {
                long remaining = val - got;
                for (AEKey variant : fuzzyFamilyOf(e.getKey())) {
                    if (variant.equals(e.getKey())) continue;
                    long vgot = simulation.extract(variant, remaining, Actionable.MODULATE);
                    if (vgot <= 0) continue;
                    long vint = simInternal.get(variant);
                    long vfromInt = Math.min(vgot, vint);
                    if (vfromInt > 0) simInternal.add(variant, -vfromInt);
                    long vfromNet = vgot - vfromInt;
                    if (vfromNet > 0) usedItems.add(variant, vfromNet);
                    got += vgot;
                    remaining -= vgot;
                    if (remaining <= 0) break;
                }
            }
            long shortfall = val - got;
            if (shortfall > 0) missingItems.add(e.getKey(), shortfall);
        }
        for (Map.Entry<IPatternDetails, BigInteger> e : b.patterns.entrySet()) {
            long val = toLongSafe(e.getValue(), "pat:" + e.getKey());
            if (val != 0) {
                patternTimes.merge(e.getKey(), val, Long::sum);
                simulation.addCrafting(e.getKey(), val);
            }
        }
    }

    // Legacy entry points kept only for the (now unreachable) non-capture apply branches.
    private void applyBundle(Bundle b) { applyBundleDirect(b); }
    private void applyBundleDeficit(Bundle b) { applyBundleDirect(b); }

    /**
     * Final aggregation over the captured bundle DAG (class doc #1).
     */
    private void applyAggregation() {
        if (aggregated) return;
        aggregated = true;
        KeyCounter initialStock = executeStartStock;
        Map<AEKey, BigInteger> total = new HashMap<>();
        // Phase 1: walk the bundle DAG from the root to enumerate keys, parent→child edges.
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
                java.util.Set<AEKey> subs = children.computeIfAbsent(k, x -> new java.util.HashSet<AEKey>());
                for (Map.Entry<AEKey, BigInteger> e : arr[0].itemNeeds.entrySet()) {
                    AEKey sub = e.getKey();
                    if (sub.equals(k)) continue; // self-edge (cycle)
                    if (subs.add(sub)) {
                        parentCount.merge(sub, 1, Integer::sum);
                        if (seen.add(sub)) stack.push(sub);
                    }
                }
            }
        }
        // Phase 2: propagate ITEM demand, convert to crafts with ceil(itemDemand / outputPerCraft).
        Map<AEKey, BigInteger> itemDemand = new HashMap<>();
        Map<AEKey, BigInteger> fuzzyItemDemand = new HashMap<>();
        Deque<AEKey> queue = new ArrayDeque<>();
        total.put(outputKey, BigInteger.valueOf(rootCraftTimes));
        queue.add(outputKey);
        while (!queue.isEmpty()) {
            AEKey p = queue.poll();
            BigInteger pCrafts = total.getOrDefault(p, BigInteger.ZERO);
            Bundle[] pArr = bundleCache.get(p);
            if (pArr == null || pArr[0] == null) continue;
            for (Map.Entry<AEKey, BigInteger> e : pArr[0].itemNeeds.entrySet()) {
                AEKey c = e.getKey();
                if (c.equals(p)) continue;
                if (total.containsKey(c)) continue; // cycle back-edge → cut
                BigInteger add = pCrafts.multiply(e.getValue());
                if (add.signum() != 0) itemDemand.merge(c, add, BigInteger::add);
                BigInteger fuzzyPerCraft = pArr[0].fuzzyItemNeeds.getOrDefault(c, BigInteger.ZERO);
                if (fuzzyPerCraft.signum() != 0) {
                    BigInteger fadd = pCrafts.multiply(fuzzyPerCraft);
                    if (fadd.signum() != 0) fuzzyItemDemand.merge(c, fadd, BigInteger::add);
                }
                int rem = parentCount.merge(c, 0, Integer::sum) - 1;
                parentCount.put(c, rem);
                if (rem == 0) {
                    BigInteger demand = itemDemand.getOrDefault(c, BigInteger.ZERO);
                    Bundle[] cArr = bundleCache.get(c);
                    if (cArr == null || cArr[0] == null) {
                        missingItems.add(c, toLongSafe(demand, "agg-miss:" + c));
                    } else {
                        long opc = outputPerCraftOf(c, cArr[0]);
                        // STOCK-AWARE SUB-CRAFT + FUZZY GROUP + PROCESSING DEFAULT FUZZY + EXACT/FUZZY SPLIT.
                        java.util.Set<AEKey> replacementGroup = PatternCompiler.getFuzzyGroup(c);
                        boolean hasReplacement = replacementGroup.size() > 1;
                        long primaryStock = realStockOf(c);
                        java.util.Set<AEKey> nbtVariants = new java.util.HashSet<>();
                        if (PatternCompiler.isProcessingInput(c)) {
                            ensureRealStockSnapshot();
                            if (realStockCache != null) {
                                for (Map.Entry<AEKey, Long> fe : realStockCache.findFuzzy(c,
                                        FuzzyMode.IGNORE_ALL)) {
                                    AEKey v = fe.getKey();
                                    if (v.equals(c) || replacementGroup.contains(v)) continue;
                                    nbtVariants.add(v);
                                    primaryStock += realStockOf(v);
                                }
                            }
                        }
                        long substituteStock = 0;
                        if (hasReplacement) {
                            for (AEKey v : replacementGroup) {
                                if (v.equals(c)) continue;
                                substituteStock += realStockOf(v);
                            }
                        }
                        BigInteger fuzzyDemand = fuzzyItemDemand.getOrDefault(c, BigInteger.ZERO);
                        if (fuzzyDemand.signum() < 0) fuzzyDemand = BigInteger.ZERO;
                        if (fuzzyDemand.compareTo(demand) > 0) fuzzyDemand = demand;
                        BigInteger exactDemand = demand.subtract(fuzzyDemand);
                        long fromStock = 0;
                        long primaryForExact = 0, primaryForFuzzy = 0;
                        if (primaryStock > 0 && demand.signum() > 0) {
                            primaryForExact = Math.min(primaryStock, toLongSafe(exactDemand, "prim-exact:" + c));
                            primaryForFuzzy = Math.min(primaryStock - primaryForExact,
                                    toLongSafe(fuzzyDemand, "prim-fuzzy:" + c));
                            long consumedPrimary = primaryForExact + primaryForFuzzy;
                            if (consumedPrimary > 0) {
                                long remaining = consumedPrimary;
                                java.util.List<AEKey> primaries = new java.util.ArrayList<>(nbtVariants.size() + 1);
                                primaries.add(c);
                                primaries.addAll(nbtVariants);
                                for (AEKey v : primaries) {
                                    long s = realStockOf(v);
                                    if (s <= 0) continue;
                                    long take = Math.min(remaining, s);
                                    if (take <= 0) continue;
                                    usedItems.add(v, take);
                                    simulation.extract(v, take, Actionable.MODULATE);
                                    stockFromNetwork.merge(v, BigInteger.valueOf(take), BigInteger::add);
                                    remaining -= take;
                                }
                                fromStock += consumedPrimary;
                            }
                        }
                        long remainingFuzzy = Math.max(0L,
                                toLongSafe(fuzzyDemand.subtract(BigInteger.valueOf(primaryForFuzzy)),
                                        "rem-fuzzy:" + c));
                        long substituteForFuzzy = 0;
                        if (remainingFuzzy > 0 && substituteStock > 0) {
                            substituteForFuzzy = Math.min(remainingFuzzy, substituteStock);
                            long remaining = substituteForFuzzy;
                            for (AEKey v : replacementGroup) {
                                if (v.equals(c)) continue;
                                long s = realStockOf(v);
                                if (s <= 0) continue;
                                long take = Math.min(remaining, s);
                                if (take <= 0) continue;
                                usedItems.add(v, take);
                                simulation.extract(v, take, Actionable.MODULATE);
                                remaining -= take;
                            }
                            fromStock += substituteForFuzzy;
                        }
                        if (hasReplacement) {
                            long poolAdd = toLongSafe(fuzzyDemand, "pool:" + c);
                            if (poolAdd > 0) {
                                for (AEKey v : replacementGroup) {
                                    if (v.equals(c)) continue;
                                    stockFromNetwork.merge(v, BigInteger.valueOf(poolAdd), BigInteger::add);
                                }
                            }
                        }
                        BigInteger netDeficit = demand.subtract(BigInteger.valueOf(fromStock));
                        if (netDeficit.signum() < 0) netDeficit = BigInteger.ZERO;
                        BigInteger crafts = netDeficit.add(BigInteger.valueOf(opc - 1)).divide(BigInteger.valueOf(opc));
                        total.put(c, crafts);
                        queue.add(c);
                    }
                }
            }
        }
        // (v1.10.3 RECURSION) Self-adjacent patterns.
        this.selfAdjacentKeys = computeSelfKeys(total);
        if (selfAdjacentKeys != null && !selfAdjacentKeys.isEmpty()) {
            correctRecursion(total, initialStock);
        }
        // Phase 3: apply each bundle exactly once, children before parents.
        java.util.Set<AEKey> applied = new java.util.HashSet<>();
        for (AEKey k : total.keySet()) applyOrdered(k, applied, total);
        // (v1.10.x CATALYST) Feedback loops (raw/lossy catalyst cycles).
        Map<AEKey, Long> loopMissing = computeFeedbackLoopMissing(total, initialStock);
        if (!loopMissing.isEmpty()) {
            for (Map.Entry<AEKey, Long> e : loopMissing.entrySet()) {
                missingItems.remove(e.getKey());
                if (e.getValue() > 0) missingItems.add(e.getKey(), e.getValue());
            }
        }
        // (v1.10.3) Pure-conversion-ring feasibility guard.
        Map<AEKey, Long> ringMissing = computeConversionRingMissing(total, initialStock);
        if (!ringMissing.isEmpty()) {
            for (Map.Entry<AEKey, Long> e : ringMissing.entrySet()) {
                missingItems.add(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * (v1.10.3 RECURSION) Detect self-adjacent patterns.
     */
    private Map<AEKey, Map<AEKey, long[]>> computeSelfKeys(Map<AEKey, BigInteger> total) {
        Map<AEKey, Map<AEKey, long[]>> result = new HashMap<>();
        if (patternResolver == null) return result;
        for (Map.Entry<AEKey, BigInteger> en : total.entrySet()) {
            AEKey key = en.getKey();
            if (key == null || en.getValue().signum() <= 0) continue;
            Bundle[] arr = bundleCache.get(key);
            if (arr == null || arr[0] == null) continue;
            IPatternDetails details = patternResolver.apply(key);
            if (details == null || isUnseededSelfLoop(details)) continue;
            Map<AEKey, Long> inputs = new HashMap<>();
            if (details.getInputs() != null) {
                for (IPatternDetails.IInput in : details.getInputs()) {
                    GenericStack[] possible = in.getPossibleInputs();
                    if (possible == null || possible.length == 0 || possible[0] == null
                            || possible[0].what() == null) continue;
                    AEKey ik = possible[0].what();
                    GenericStack remStack = in.getRemainingKey(ik);
                    AEKey rem = remStack == null ? null : remStack.what();
                    if (rem != null && rem.equals(ik)) continue;
                    long amt = in.getMultiplier() * Math.max(1L, possible[0].amount());
                    inputs.merge(ik, amt, Long::sum);
                }
            }
            if (inputs.isEmpty()) continue;
            Map<AEKey, Long> outputs = new HashMap<>();
            if (details.getOutputs() != null) {
                for (GenericStack gs : details.getOutputs()) {
                    if (gs == null || gs.what() == null) continue;
                    outputs.merge(gs.what(), gs.amount(), Long::sum);
                }
            }
            if (outputs.isEmpty()) continue;
            Map<AEKey, long[]> self = new HashMap<>();
            for (Map.Entry<AEKey, Long> e : inputs.entrySet()) {
                Long out = outputs.get(e.getKey());
                if (out != null && out > 0) {
                    self.put(e.getKey(), new long[]{e.getValue(), out});
                }
            }
            if (!self.isEmpty()) result.put(key, self);
        }
        return result;
    }

    /**
     * (v1.10.3 RECURSION) Correct the aggregation for self-adjacent patterns.
     */
    private void correctRecursion(Map<AEKey, BigInteger> total, KeyCounter initialStock) {
        for (Map.Entry<AEKey, Map<AEKey, long[]>> en : selfAdjacentKeys.entrySet()) {
            AEKey k = en.getKey();
            Map<AEKey, long[]> self = en.getValue();
            BigInteger cur = total.getOrDefault(k, BigInteger.ZERO);
            if (cur.signum() <= 0) continue;
            long t = toLongSafe(cur, "rec-total:" + k);
            if (t <= 0) continue;
            // (a) Seed requirement.
            for (Map.Entry<AEKey, long[]> se : self.entrySet()) {
                long in = se.getValue()[0];
                if (in <= 0) continue;
                long s = stockOf(initialStock, se.getKey());
                if (s < in) {
                    missingItems.add(se.getKey(), in - s);
                    t = 0;
                }
            }
            if (t <= 0) {
                total.put(k, BigInteger.ZERO);
                continue;
            }
            // (b) Primary-output amplifier (net > 0): correct the craft count.
            for (Map.Entry<AEKey, long[]> se : self.entrySet()) {
                if (!se.getKey().equals(k)) continue;
                long in = se.getValue()[0];
                long out = se.getValue()[1];
                long net = out - in;
                if (net <= 0) continue;
                if (!k.equals(outputKey) || requestAmount == null) continue;
                long s = stockOf(initialStock, k);
                long req = toLongSafe(requestAmount, "rec-req:" + k);
                t = (req > s) ? (req - s + net - 1) / net : 0;
                break;
            }
            total.put(k, BigInteger.valueOf(t));
        }
    }

    /** (v1.10.3 RECURSION) Stock of a key in a KeyCounter snapshot (0 when absent). */
    private static long stockOf(KeyCounter stock, AEKey key) {
        return stock == null ? 0L : stock.get(key);
    }

    /**
     * (v1.10.x CATALYST) Snapshot the simulation's INITIAL network stock at the very
     * start of execute().
     */
    private KeyCounter snapshotExecuteStartStock(CraftingBytecode root) {
        KeyCounter snap = new KeyCounter();
        java.util.Set<AEKey> keys = new java.util.HashSet<>();
        java.util.Set<AEKey> visited = new java.util.HashSet<>();
        collectPlanKeys(outputKey, keys, visited);
        for (AEKey k : keys) {
            if (k == null) continue;
            long amt = simulation.extract(k, Long.MAX_VALUE, Actionable.SIMULATE);
            if (amt > 0) snap.add(k, amt);
        }
        return snap;
    }

    /**
     * Collect every AEKey the plan touches by walking the recipe graph through the
     * pattern resolver.
     */
    private void collectPlanKeys(AEKey key, java.util.Set<AEKey> keys, java.util.Set<AEKey> visited) {
        if (key == null || !visited.add(key)) return;
        keys.add(key);
        IPatternDetails p = patternResolver != null ? patternResolver.apply(key) : null;
        if (p == null) return;
        if (p.getInputs() != null) {
            for (IPatternDetails.IInput in : p.getInputs()) {
                GenericStack[] possible = in.getPossibleInputs();
                if (possible == null || possible.length == 0 || possible[0] == null
                        || possible[0].what() == null) continue;
                AEKey ik = possible[0].what();
                keys.add(ik);
                collectPlanKeys(ik, keys, visited);
            }
        }
        if (p.getOutputs() != null) {
            for (GenericStack gs : p.getOutputs()) {
                if (gs != null && gs.what() != null) keys.add(gs.what());
            }
        }
    }

    /** Structural per-craft recipe line of an in-plan pattern (for the loop analysis). */
    private static final class LoopPattern {
        final Map<AEKey, Long> inputs; // per-craft consumed (excluding returned/catalyst seeds)
        final Map<AEKey, Long> outputs; // per-craft produced (primary + byproducts)
        final java.util.Set<AEKey> byproducts; // outputs beyond the primary

        LoopPattern(Map<AEKey, Long> inputs, Map<AEKey, Long> outputs, java.util.Set<AEKey> byproducts) {
            this.inputs = inputs;
            this.outputs = outputs;
            this.byproducts = byproducts;
        }
    }

    /**
     * (v1.10.x CATALYST) Detect catalyst feedback loops and compute their required
     * working capital (seed), returning {@code loop item -> seed required beyond stock}.
     */
    private Map<AEKey, Long> computeFeedbackLoopMissing(Map<AEKey, BigInteger> total,
            KeyCounter initialStock) {
        Map<AEKey, LoopPattern> pats = new HashMap<>();
        java.util.Set<AEKey> primaryOutputs = new java.util.HashSet<>();
        for (Map.Entry<AEKey, BigInteger> en : total.entrySet()) {
            AEKey key = en.getKey();
            if (key == null || en.getValue().signum() <= 0) continue;
            Bundle[] arr = bundleCache.get(key);
            if (arr == null || arr[0] == null) continue;
            IPatternDetails details = patternResolver != null ? patternResolver.apply(key) : null;
            if (details == null) continue;
            Map<AEKey, Long> inputs = new HashMap<>();
            if (details.getInputs() != null) {
                for (IPatternDetails.IInput in : details.getInputs()) {
                    GenericStack[] possible = in.getPossibleInputs();
                    if (possible == null || possible.length == 0 || possible[0] == null
                            || possible[0].what() == null) continue;
                    AEKey ik = possible[0].what();
                    GenericStack remStack = in.getRemainingKey(ik);
                    AEKey rem = remStack == null ? null : remStack.what();
                    if (rem != null && rem.equals(ik)) continue;
                    long amt = in.getMultiplier() * Math.max(1L, possible[0].amount());
                    inputs.merge(ik, amt, Long::sum);
                }
            }
            Map<AEKey, Long> outputs = new HashMap<>();
            java.util.Set<AEKey> byproducts = new java.util.HashSet<>();
            GenericStack[] outs = details.getOutputs();
            if (outs != null) {
                for (int i = 0; i < outs.length; i++) {
                    GenericStack gs = outs[i];
                    if (gs == null || gs.what() == null) continue;
                    outputs.merge(gs.what(), gs.amount(), Long::sum);
                    if (i > 0) byproducts.add(gs.what());
                }
            }
            primaryOutputs.add(key);
            pats.put(key, new LoopPattern(inputs, outputs, byproducts));
        }
        if (pats.isEmpty()) return Collections.emptyMap();

        // Find SCCs of the item graph that contain a byproduct edge — catalyst feedback loops.
        Map<AEKey, java.util.Set<AEKey>> graph = new HashMap<>();
        for (LoopPattern p : pats.values()) {
            for (AEKey i : p.inputs.keySet()) {
                graph.computeIfAbsent(i, x -> new java.util.HashSet<AEKey>()).addAll(p.outputs.keySet());
            }
        }
        java.util.Set<AEKey> loopItems = new java.util.HashSet<>();
        for (java.util.Set<AEKey> scc : tarjanScc(graph)) {
            if (scc.size() <= 1) continue;
            boolean hasByproduct = false;
            outer:
            for (LoopPattern p : pats.values()) {
                for (AEKey i : p.inputs.keySet()) {
                    if (!scc.contains(i)) continue;
                    for (AEKey j : p.outputs.keySet()) {
                        if (scc.contains(j) && p.byproducts.contains(j)) {
                            hasByproduct = true;
                            break outer;
                        }
                    }
                }
            }
            if (hasByproduct) loopItems.addAll(scc);
        }
        if (loopItems.isEmpty()) return Collections.emptyMap();

        // Working-capital simulation.
        Map<AEKey, Long> available = new HashMap<>();
        if (initialStock != null) {
            for (Map.Entry<AEKey, Long> e : initialStock.entrySet()) {
                if (e.getValue().longValue() > 0) available.put(e.getKey(), e.getValue());
            }
        }
        Map<AEKey, Long> remaining = new HashMap<>();
        for (Map.Entry<AEKey, BigInteger> en : total.entrySet()) {
            if (en.getValue().signum() <= 0 || !pats.containsKey(en.getKey())) continue;
            remaining.put(en.getKey(), toLongSafe(en.getValue(), "loop-fire:" + en.getKey()));
        }
        Map<AEKey, Long> injected = new HashMap<>();
        long totalFires = 0;
        for (Long v : remaining.values()) totalFires += v;
        final long FIRE_CAP = 500_000L;
        long guard = 0;
        while (!remaining.isEmpty() && totalFires <= FIRE_CAP && guard++ < 2 * FIRE_CAP + 100) {
            AEKey fireable = null;
            for (Map.Entry<AEKey, Long> en : remaining.entrySet()) {
                if (en.getValue() <= 0) continue;
                LoopPattern p = pats.get(en.getKey());
                boolean ok = true;
                for (Map.Entry<AEKey, Long> e : p.inputs.entrySet()) {
                    if (available.getOrDefault(e.getKey(), 0L) < e.getValue()) { ok = false; break; }
                }
                if (ok) { fireable = en.getKey(); break; }
            }
            if (fireable != null) {
                LoopPattern p = pats.get(fireable);
                remaining.merge(fireable, -1L, Long::sum);
                totalFires--;
                for (Map.Entry<AEKey, Long> e : p.inputs.entrySet())
                    available.merge(e.getKey(), -e.getValue(), Long::sum);
                for (Map.Entry<AEKey, Long> e : p.outputs.entrySet())
                    available.merge(e.getKey(), e.getValue(), Long::sum);
                continue;
            }
            // Deadlock: pick the blocked pattern with the minimum input deficit.
            AEKey best = null;
            long bestDeficit = Long.MAX_VALUE;
            long bestPrimary = -1;
            for (Map.Entry<AEKey, Long> en : remaining.entrySet()) {
                if (en.getValue() <= 0) continue;
                LoopPattern p = pats.get(en.getKey());
                long deficit = 0;
                long primaryScore = 0;
                for (Map.Entry<AEKey, Long> e : p.inputs.entrySet()) {
                    long gap = Math.max(0L, e.getValue() - available.getOrDefault(e.getKey(), 0L));
                    deficit += gap;
                    if (gap > 0 && primaryOutputs.contains(e.getKey())) primaryScore++;
                }
                if (deficit == 0) { best = en.getKey(); bestDeficit = 0; break; }
                if (deficit < bestDeficit
                        || (deficit == bestDeficit && primaryScore > bestPrimary)) {
                    best = en.getKey(); bestDeficit = deficit; bestPrimary = primaryScore;
                }
            }
            if (best == null || bestDeficit <= 0) break;
            LoopPattern bp = pats.get(best);
            boolean injectedAny = false;
            for (Map.Entry<AEKey, Long> e : bp.inputs.entrySet()) {
                long gap = Math.max(0L, e.getValue() - available.getOrDefault(e.getKey(), 0L));
                if (gap > 0) {
                    injected.merge(e.getKey(), gap, Long::sum);
                    available.merge(e.getKey(), gap, Long::sum);
                    injectedAny = true;
                }
            }
            if (!injectedAny) break;
        }
        if (totalFires > FIRE_CAP) return Collections.emptyMap();

        Map<AEKey, Long> result = new HashMap<>();
        for (AEKey x : loopItems) {
            result.put(x, injected.getOrDefault(x, 0L));
        }
        return result;
    }

    /** Directed pure-conversion edge {@code from → to} exchanging {@code in} of from for {@code out} of to. */
    private static final class ConvEdge {
        final AEKey to;
        final long in;
        final long out;

        ConvEdge(AEKey to, long in, long out) {
            this.to = to;
            this.in = in;
            this.out = out;
        }

        AEKey to() { return to; }
        long in() { return in; }
        long out() { return out; }
    }

    /**
     * (v1.10.3) Pure-conversion-ring feasibility guard.
     */
    private Map<AEKey, Long> computeConversionRingMissing(Map<AEKey, BigInteger> total,
            KeyCounter initialStock) {
        java.util.Set<AEKey> reachableKeys = new java.util.HashSet<>();
        collectPlanKeys(outputKey, reachableKeys, new java.util.HashSet<AEKey>());
        Map<AEKey, java.util.List<LoopPattern>> recipesByKey = new HashMap<>();
        for (AEKey key : reachableKeys) {
            if (key == null) continue;
            java.util.List<IPatternDetails> patterns = (allPatternsResolver != null)
                    ? allPatternsResolver.apply(key) : Collections.<IPatternDetails>emptyList();
            if (patterns.isEmpty()) {
                IPatternDetails chosen = patternResolver != null ? patternResolver.apply(key) : null;
                if (chosen != null) patterns = Collections.singletonList(chosen);
            }
            for (IPatternDetails details : patterns) {
                if (details == null) continue;
                Map<AEKey, Long> in = new HashMap<>();
                if (details.getInputs() != null) {
                    for (IPatternDetails.IInput entry : details.getInputs()) {
                        GenericStack[] possible = entry.getPossibleInputs();
                        if (possible == null || possible.length == 0 || possible[0] == null
                                || possible[0].what() == null) continue;
                        AEKey ik = possible[0].what();
                        GenericStack remStack = entry.getRemainingKey(ik);
                        AEKey rem = remStack == null ? null : remStack.what();
                        if (rem != null && rem.equals(ik)) continue;
                        long amt = entry.getMultiplier() * Math.max(1L, possible[0].amount());
                        in.merge(ik, amt, Long::sum);
                    }
                }
                if (in.isEmpty()) continue;
                Map<AEKey, Long> out = new HashMap<>();
                java.util.Set<AEKey> bp = new java.util.HashSet<>();
                int idx = 0;
                if (details.getOutputs() != null) {
                    for (GenericStack gs : details.getOutputs()) {
                        if (gs == null || gs.what() == null) continue;
                        out.merge(gs.what(), gs.amount(), Long::sum);
                        if (idx > 0) bp.add(gs.what());
                        idx++;
                    }
                }
                if (out.isEmpty()) continue;
                recipesByKey.computeIfAbsent(key, x -> new java.util.ArrayList<LoopPattern>())
                        .add(new LoopPattern(in, out, bp));
            }
        }
        if (recipesByKey.isEmpty()) return Collections.emptyMap();

        Map<AEKey, java.util.Set<AEKey>> graph = new HashMap<>();
        for (java.util.List<LoopPattern> recs : recipesByKey.values()) {
            for (LoopPattern p : recs) {
                for (AEKey i : p.inputs.keySet()) {
                    graph.computeIfAbsent(i, x -> new java.util.HashSet<AEKey>())
                            .addAll(p.outputs.keySet());
                }
            }
        }
        Map<AEKey, Long> result = new HashMap<>();
        for (java.util.Set<AEKey> scc : tarjanScc(graph)) {
            if (scc.size() <= 1) continue;
            boolean pure = true;
            for (AEKey member : scc) {
                java.util.List<LoopPattern> recs = recipesByKey.get(member);
                if (recs == null) { pure = false; break; }
                for (LoopPattern p : recs) {
                    if (!p.byproducts.isEmpty() || p.inputs.size() != 1 || p.outputs.size() != 1
                            || !scc.contains(p.inputs.keySet().iterator().next())
                            || !scc.contains(p.outputs.keySet().iterator().next())) {
                        pure = false;
                        break;
                    }
                }
                if (!pure) break;
            }
            if (!pure) continue;
            Map<AEKey, java.util.List<ConvEdge>> adj = new HashMap<>();
            for (AEKey member : scc) {
                for (LoopPattern p : recipesByKey.get(member)) {
                    AEKey from = p.inputs.keySet().iterator().next();
                    AEKey to = p.outputs.keySet().iterator().next();
                    long a = p.inputs.get(from);
                    long b = p.outputs.get(to);
                    adj.computeIfAbsent(from, x -> new java.util.ArrayList<ConvEdge>())
                            .add(new ConvEdge(to, a, b));
                }
            }
            AEKey base = scc.iterator().next();
            Map<AEKey, BigInteger[]> value = new HashMap<>(); // key → {num, den}, in base units
            value.put(base, new BigInteger[]{BigInteger.ONE, BigInteger.ONE});
            Deque<AEKey> queue = new ArrayDeque<>();
            queue.add(base);
            boolean consistent = true;
            while (!queue.isEmpty() && consistent) {
                AEKey cur = queue.poll();
                BigInteger[] cv = value.get(cur);
                for (ConvEdge e : adj.getOrDefault(cur, Collections.<ConvEdge>emptyList())) {
                    BigInteger num = cv[0].multiply(BigInteger.valueOf(e.in()));
                    BigInteger den = cv[1].multiply(BigInteger.valueOf(e.out()));
                    BigInteger g = num.gcd(den);
                    if (g.signum() > 0 && !g.equals(BigInteger.ONE)) {
                        num = num.divide(g);
                        den = den.divide(g);
                    }
                    BigInteger[] existing = value.get(e.to());
                    if (existing == null) {
                        value.put(e.to(), new BigInteger[]{num, den});
                        queue.add(e.to());
                    } else if (!existing[0].equals(num) || !existing[1].equals(den)) {
                        consistent = false;
                        break;
                    }
                }
            }
            if (!consistent) continue;
            Map<AEKey, BigInteger> demand = new HashMap<>();
            for (AEKey k : total.keySet()) {
                if (scc.contains(k)) continue;
                java.util.List<LoopPattern> recs = recipesByKey.get(k);
                if (recs == null) continue;
                BigInteger t = total.get(k);
                if (t == null || t.signum() <= 0) continue;
                for (LoopPattern p : recs) {
                    for (Map.Entry<AEKey, Long> e : p.inputs.entrySet()) {
                        if (scc.contains(e.getKey())) {
                            demand.merge(e.getKey(), t.multiply(BigInteger.valueOf(e.getValue())),
                                    BigInteger::add);
                        }
                    }
                }
            }
            if (outputKey != null && scc.contains(outputKey) && requestAmount != null) {
                demand.merge(outputKey, requestAmount, BigInteger::add);
            }
            if (demand.isEmpty()) continue;
            BigInteger dNum = BigInteger.ZERO;
            BigInteger dDen = BigInteger.ONE;
            for (Map.Entry<AEKey, BigInteger> e : demand.entrySet()) {
                BigInteger[] v = value.get(e.getKey());
                if (v == null) continue;
                BigInteger termNum = e.getValue().multiply(v[0]);
                BigInteger termDen = v[1];
                dNum = dNum.multiply(termDen).add(termNum.multiply(dDen));
                dDen = dDen.multiply(termDen);
            }
            BigInteger sNum = BigInteger.ZERO;
            BigInteger sDen = BigInteger.ONE;
            for (AEKey member : scc) {
                BigInteger[] v = value.get(member);
                if (v == null) continue;
                long st = stockOf(initialStock, member);
                if (st <= 0) continue;
                BigInteger termNum = BigInteger.valueOf(st).multiply(v[0]);
                BigInteger termDen = v[1];
                sNum = sNum.multiply(termDen).add(termNum.multiply(sDen));
                sDen = sDen.multiply(termDen);
            }
            if (sNum.multiply(dDen).compareTo(dNum.multiply(sDen)) >= 0) {
                continue; // ring is value-sufficient → feasible
            }
            BigInteger deficitNum = dNum.multiply(sDen).subtract(sNum.multiply(dDen));
            BigInteger deficitDen = dDen.multiply(sDen);
            AEKey best = null;
            BigInteger[] bestVal = null;
            for (AEKey member : scc) {
                if (!demand.containsKey(member)) continue;
                BigInteger[] v = value.get(member);
                if (v == null || v[0].signum() <= 0) continue;
                if (best == null || v[0].multiply(bestVal[1]).compareTo(bestVal[0].multiply(v[1])) < 0) {
                    best = member;
                    bestVal = v;
                }
            }
            if (best != null) {
                BigInteger need = deficitNum.multiply(bestVal[1])
                        .add(deficitDen.multiply(bestVal[0]).subtract(BigInteger.ONE))
                        .divide(deficitDen.multiply(bestVal[0]));
                long amount = need.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : need.longValue();
                if (amount > 0) result.put(best, Math.max(result.getOrDefault(best, 0L), amount));
            }
        }
        return result;
    }

    /**
     * Iterative Tarjan strongly-connected-components over a small item graph.
     */
    private static java.util.List<java.util.Set<AEKey>> tarjanScc(Map<AEKey, java.util.Set<AEKey>> graph) {
        java.util.List<java.util.Set<AEKey>> sccs = new java.util.ArrayList<>();
        if (graph.isEmpty()) return sccs;
        Map<AEKey, Integer> index = new HashMap<>();
        Map<AEKey, Integer> low = new HashMap<>();
        Deque<AEKey> stack = new ArrayDeque<>();
        java.util.Set<AEKey> onStack = new java.util.HashSet<>();
        int[] counter = {0};
        for (AEKey start : graph.keySet()) {
            if (index.containsKey(start)) continue;
            Deque<Object[]> work = new ArrayDeque<>();
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
                if (it == null) it = graph.getOrDefault(node, Collections.<AEKey>emptySet()).iterator();
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

    /**
     * Output amount of {@code key} produced per craft, read from the 1-craft bundle's
     * own emitted map (falls back to 1).
     */
    private static long outputPerCraftOf(AEKey key, Bundle b) {
        BigInteger out = b.emitted.get(key);
        if (out != null && out.signum() > 0) {
            long v = out.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : out.longValue();
            return v > 0 ? v : 1;
        }
        return 1;
    }

    /** Real network stock of a key (live inventory, incl. fluids), O(1) cached. */
    private long realStockOf(AEKey key) {
        ensureRealStockSnapshot();
        return realStockCache.get(key);
    }

    /**
     * Lazily snapshot the live network inventory (reset every execute()).
     * rv4: reads IStorageGrid item+fluid lists into a shim KeyCounter.
     */
    private void ensureRealStockSnapshot() {
        if (realStockCache == null) {
            KeyCounter snap = new KeyCounter();
            try {
                if (networkKey instanceof IGrid) {
                    IGrid g = (IGrid) networkKey;
                    IStorageGrid st = g.getCache(IStorageGrid.class);
                    if (st != null) {
                        IItemList<IAEItemStack> items = AEApi.instance().storage().createItemList();
                        st.getItemInventory().getAvailableItems(items);
                        for (IAEItemStack is : items) {
                            snap.add(AEKey.of(is), is.getStackSize());
                        }
                        IItemList<IAEFluidStack> fluids = AEApi.instance().storage().createFluidList();
                        st.getFluidInventory().getAvailableItems(fluids);
                        for (IAEFluidStack fs : fluids) {
                            snap.add(AEKey.of(fs), fs.getStackSize());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            realStockCache = snap;
        }
    }

    /**
     * (v1.10.x) Effective fuzzy group for {@code key}.
     */
    private java.util.Set<AEKey> fuzzyFamilyOf(AEKey key) {
        java.util.Set<AEKey> group = PatternCompiler.getFuzzyGroup(key);
        if (!PatternCompiler.isProcessingInput(key)) {
            return group;
        }
        ensureRealStockSnapshot();
        java.util.Set<AEKey> family = new java.util.HashSet<>(group);
        if (realStockCache != null) {
            for (Map.Entry<AEKey, Long> e : realStockCache.findFuzzy(key, FuzzyMode.IGNORE_ALL)) {
                family.add(e.getKey());
            }
        }
        return family;
    }

    /**
     * True if {@code pattern} is an <em>unseeded self-growth loop</em> (A -> 2A).
     */
    private static boolean isUnseededSelfLoop(IPatternDetails pattern) {
        if (pattern == null) return false;
        GenericStack primary = pattern.getPrimaryOutput();
        if (primary == null || primary.what() == null) return false;
        AEKey out = primary.what();
        IPatternDetails.IInput[] inputs = pattern.getInputs();
        if (inputs == null || inputs.length == 0) return false;
        for (IPatternDetails.IInput input : inputs) {
            GenericStack[] possible = input.getPossibleInputs();
            if (possible == null || possible.length == 0) return false;
            boolean anySelf = false;
            for (GenericStack gs : possible) {
                if (gs != null && gs.what() != null && gs.what().equals(out)) {
                    anySelf = true;
                    break;
                }
            }
            if (!anySelf) return false;
        }
        return true;
    }

    /** Read-only DFS that applies each bundle exactly once, children before parents. */
    private void applyOrdered(AEKey k, java.util.Set<AEKey> applied, Map<AEKey, BigInteger> total) {
        if (!applied.add(k)) return;
        Bundle[] arr = bundleCache.get(k);
        if (arr != null && arr[0] != null) {
            for (Map.Entry<AEKey, BigInteger> e : arr[0].itemNeeds.entrySet()) applyOrdered(e.getKey(), applied, total);
        }
        BigInteger t = total.getOrDefault(k, BigInteger.ZERO);
        if (t.signum() == 0) return;
        if (arr == null || arr[0] == null) {
            missingItems.add(k, toLongSafe(t, "agg-miss:" + k));
            return;
        }
        Bundle scaled = arr[0].scale(t);
        Map<AEKey, long[]> self = selfAdjacentKeys != null ? selfAdjacentKeys.get(k) : null;
        if (self != null && !self.isEmpty()) {
            for (Map.Entry<AEKey, long[]> se : self.entrySet()) {
                long seed = se.getValue()[0];
                if (seed > 0) scaled.used.put(se.getKey(), BigInteger.valueOf(seed));
            }
        }
        subtractStockFromNetwork(scaled);
        applyBundleDirect(scaled);
        // (v1.10.x DURABILITY) Finite-use (durability) tool demand.
        for (Map.Entry<AEKey, long[]> d : arr[0].durability.entrySet()) {
            AEKey toolKey = d.getKey();
            long amount = d.getValue()[0];
            long uses = d.getValue()[1];
            if (amount <= 0 || uses <= 0) continue;
            BigInteger units = t.add(BigInteger.valueOf(uses - 1)).divide(BigInteger.valueOf(uses));
            long demand = toLongSafe(units.multiply(BigInteger.valueOf(amount)), "dur:" + toolKey);
            if (demand <= 0) continue;
            simulation.addStackBytes(toolKey, 1, demand); nodeCount++;
            long got = simulation.extract(toolKey, demand, Actionable.MODULATE);
            if (got > 0) {
                long internal = simInternal.get(toolKey);
                long fromInternal = Math.min(got, internal);
                if (fromInternal > 0) simInternal.add(toolKey, -fromInternal);
                long fromNetwork = got - fromInternal;
                if (fromNetwork > 0) usedItems.add(toolKey, fromNetwork);
            }
            long shortfall = demand - got;
            if (shortfall > 0) missingItems.add(toolKey, shortfall);
        }
    }

    /**
     * (v1.9.8) Remove the network-stock portion of a key (already consumed by the
     * stock-aware sub-craft branch as {@code fromStock}) from a parent bundle's
     * {@code used} demand.
     */
    private void subtractStockFromNetwork(Bundle b) {
        if (stockFromNetwork == null || stockFromNetwork.isEmpty()) return;
        java.util.Iterator<Map.Entry<AEKey, BigInteger>> it = b.used.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<AEKey, BigInteger> e = it.next();
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
     * cached 1-craft bundle.
     */
    private boolean subBundlesComplete(Bundle b0) {
        if (b0.itemNeeds.isEmpty()) return true;
        for (Map.Entry<AEKey, BigInteger> e : b0.itemNeeds.entrySet()) {
            Bundle[] sub = bundleCache.get(e.getKey());
            if (sub == null || sub[0] == null) return false;
        }
        return true;
    }

    /** Undo a bundle's effects — reverse order of apply. */
    private void revertBundle(Bundle b) {
        simulation.addBytes(-toBytesDouble(b.bytes));
        // Reverse patterns first (no sim state dependency)
        for (Map.Entry<IPatternDetails, BigInteger> e : b.patterns.entrySet()) {
            long val = toLongSafe(e.getValue(), "pat-revert:" + e.getKey());
            long newVal = patternTimes.merge(e.getKey(), -val, Long::sum);
            if (newVal == 0) patternTimes.remove(e.getKey());
            simulation.addCrafting(e.getKey(), -val);
        }
        // Reverse missing
        for (Map.Entry<AEKey, BigInteger> e : b.missing.entrySet()) {
            long val = toLongSafe(e.getValue(), "miss-revert:" + e.getKey());
            missingItems.add(e.getKey(), -val);
            if (missingItems.get(e.getKey()) == 0) missingItems.remove(e.getKey());
        }
        // Reverse used (undo extraction → re-insert to sim, undo usedItems).
        for (Map.Entry<AEKey, BigInteger> e : b.used.entrySet()) {
            long val = toLongSafe(e.getValue(), "used-revert:" + e.getKey());
            simulation.insert(e.getKey(), val, Actionable.MODULATE);
            long internal = simInternal.get(e.getKey());
            long fromInternal = Math.min(val, internal);
            long fromNetwork = val - fromInternal;
            if (fromNetwork > 0) usedItems.add(e.getKey(), -fromNetwork);
            if (usedItems.get(e.getKey()) == 0) usedItems.remove(e.getKey());
        }
        // Reverse emitted (undo insert → extract from sim, undo emittedItems and simInternal).
        for (Map.Entry<AEKey, BigInteger> e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit-revert:" + e.getKey());
            simulation.extract(e.getKey(), val, Actionable.MODULATE);
            emittedItems.add(e.getKey(), -val);
            if (emittedItems.get(e.getKey()) == 0) emittedItems.remove(e.getKey());
            simInternal.add(e.getKey(), -val);
            if (simInternal.get(e.getKey()) == 0) simInternal.remove(e.getKey());
        }
        // Reverse catalyst seeds.
        for (Map.Entry<AEKey, BigInteger> e : b.seeds.entrySet()) {
            long val = toLongSafe(e.getValue(), "seed-revert:" + e.getKey());
            catalystSeedItems.add(e.getKey(), -val);
            if (catalystSeedItems.get(e.getKey()) == 0) catalystSeedItems.remove(e.getKey());
        }
        // Reverse durability rates.
        for (Map.Entry<AEKey, long[]> e : b.durability.entrySet()) {
            durabilityItems.remove(e.getKey());
        }
    }

    private Bundle captureDelta() {
        Bundle b = new Bundle();
        b.bytes = BigInteger.valueOf((long) simulation.getBytes());
        if (!usedItems.isEmpty()) { java.util.ArrayList<AEKey> ks = new java.util.ArrayList<>(usedItems.keySet()); for (AEKey k : ks) { long v = usedItems.get(k); if (v != 0) b.used.put(k, BigInteger.valueOf(v)); } }
        if (!emittedItems.isEmpty()) { java.util.ArrayList<AEKey> ks = new java.util.ArrayList<>(emittedItems.keySet()); for (AEKey k : ks) { long v = emittedItems.get(k); if (v != 0) b.emitted.put(k, BigInteger.valueOf(v)); } }
        if (!missingItems.isEmpty()) { java.util.ArrayList<AEKey> ks = new java.util.ArrayList<>(missingItems.keySet()); for (AEKey k : ks) { long v = missingItems.get(k); if (v != 0) b.missing.put(k, BigInteger.valueOf(v)); } }
        if (!simInternal.isEmpty()) { java.util.ArrayList<AEKey> ks = new java.util.ArrayList<>(simInternal.keySet()); for (AEKey k : ks) { long v = simInternal.get(k); if (v != 0) b.internal.put(k, BigInteger.valueOf(v)); } }
        if (!catalystSeedItems.isEmpty()) { java.util.ArrayList<AEKey> ks = new java.util.ArrayList<>(catalystSeedItems.keySet()); for (AEKey k : ks) { long v = catalystSeedItems.get(k); if (v != 0) b.seeds.put(k, BigInteger.valueOf(v)); } }
        if (!durabilityItems.isEmpty()) { for (Map.Entry<AEKey, long[]> en : durabilityItems.entrySet()) b.durability.put(en.getKey(), en.getValue()); }
        if (!patternTimes.isEmpty()) { java.util.ArrayList<IPatternDetails> ks = new java.util.ArrayList<>(patternTimes.keySet()); for (IPatternDetails k : ks) { long v = patternTimes.get(k); if (v != 0) b.patterns.put(k, BigInteger.valueOf(v)); } }
        return b;
    }

    private Bundle diffBundle(Bundle after, Bundle before) {
        Bundle b = new Bundle();
        b.bytes = after.bytes.subtract(before.bytes);
        for (Map.Entry<AEKey, BigInteger> e : after.used.entrySet()) {
            BigInteger bv = before.used.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.used.put(e.getKey(), d);
        }
        for (Map.Entry<AEKey, BigInteger> e : after.emitted.entrySet()) {
            BigInteger bv = before.emitted.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.emitted.put(e.getKey(), d);
        }
        for (Map.Entry<AEKey, BigInteger> e : after.missing.entrySet()) {
            BigInteger bv = before.missing.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.missing.put(e.getKey(), d);
        }
        for (Map.Entry<AEKey, BigInteger> e : after.internal.entrySet()) {
            BigInteger bv = before.internal.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.internal.put(e.getKey(), d);
        }
        for (Map.Entry<AEKey, BigInteger> e : after.seeds.entrySet()) {
            BigInteger bv = before.seeds.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.seeds.put(e.getKey(), d);
        }
        for (Map.Entry<AEKey, long[]> e : after.durability.entrySet()) {
            if (!before.durability.containsKey(e.getKey())) b.durability.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<IPatternDetails, BigInteger> e : after.patterns.entrySet()) {
            BigInteger bv = before.patterns.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.patterns.put(e.getKey(), d);
        }
        return b;
    }

    /** Subtract one map from another, removing non-positive entries. */
    private static void subtractMap(Map<AEKey, BigInteger> target, Map<AEKey, BigInteger> o) {
        for (Map.Entry<AEKey, BigInteger> e : o.entrySet()) {
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
        subtractMap(target.seeds, o.seeds);
        for (Map.Entry<AEKey, long[]> e : o.durability.entrySet()) target.durability.remove(e.getKey());
        for (Map.Entry<IPatternDetails, BigInteger> e : o.patterns.entrySet()) {
            BigInteger t = target.patterns.getOrDefault(e.getKey(), BigInteger.ZERO).subtract(e.getValue());
            if (t.signum() <= 0) target.patterns.remove(e.getKey()); else target.patterns.put(e.getKey(), t);
        }
        for (Map.Entry<AEKey, BigInteger> e : o.needs.entrySet()) {
            Bundle[] sb = bundleCache.get(e.getKey());
            if (sb != null && sb[0] != null) subtractBundle(target, sb[0].scale(e.getValue()));
        }
    }

    public CraftingVM(Object networkKey, Function<AEKey, IPatternDetails> patternResolver) {
        this.networkKey = networkKey;
        this.patternResolver = patternResolver;
    }

    /** Allows a cached/reused VM to swap its resolver without rebuilding the instance. */
    public void setPatternResolver(Function<AEKey, IPatternDetails> patternResolver) {
        this.patternResolver = patternResolver;
    }

    /** (v1.10.3) Supplies ALL patterns per output key for the pure-conversion-ring analysis. */
    public void setAllPatternsResolver(Function<AEKey, java.util.List<IPatternDetails>> resolver) {
        this.allPatternsResolver = resolver;
    }

    public ICraftingPlan execute(CraftingBytecode requestBytecode, CraftingSimulationState simulation) {
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
        this.catalystSeedItems = new KeyCounter();
        this.durabilityItems = new HashMap<>();
        this.patternTimes = new HashMap<>();
        this.simulation = simulation;
        this.nodeCount = 1;
        this.rootCraftTimes = 0;
        this.batchRemainder = null;
        this.aggregated = false;
        this.outputKey = requestBytecode.getOutput();
        this.extractIsClaim = false;
        this.requestAmount = requestedAmount;
        // (v1.9.5) realStockCache MUST be reset on every execute().
        this.realStockCache = null;
        this.stockFromNetwork = new HashMap<>();
        resolvingKeys.clear();
        circularCache.clear();
        cyclicCraftKeys.clear();
        jitFailCache.clear();
        this.executeStartStock = snapshotExecuteStartStock(requestBytecode);

        long vmStartNs = System.nanoTime();

        loadBytecode(requestBytecode);

        while (pc < code.length) {
            int op = code[pc++] & 0xFF;
            switch (op) {
                case 0: { int idx=readShort(); long cnt=readLong(); pushL(popL()*cnt); break; } // PUSH_ITEM
                case 1: pushL(readLong()); break; // PUSH_LONG
                case 2: { // ADD with overflow detection
                    long b=popL(), a=popL(), r=a+b;
                    if (((a^r)&(b^r)) < 0) { push(BigInteger.valueOf(a).add(BigInteger.valueOf(b))); }
                    else pushL(r);
                    break;
                }
                case 3: { // SUB with overflow detection
                    long b=popL(), a=popL(), r=a-b;
                    if (((a^b)&(a^r)) < 0) { push(BigInteger.valueOf(a).subtract(BigInteger.valueOf(b))); }
                    else pushL(r);
                    break;
                }
                case 4: { // MUL with overflow detection
                    long b=popL(), a=popL();
                    if ((b&(b-1))==0) { pushL(a << Long.numberOfTrailingZeros(b)); break; } // power-of-2 fast path
                    long r=a*b;
                    if (b!=0 && r/b!=a) { push(BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))); }
                    else pushL(r);
                    break;
                }
                case 5: { // DIV_ROUNDUP — bitwise fast path for powers of 2
                    long pc2=popL(), rq=popL();
                    if (pc2 <= 0) { pushL(0); break; }
                    if ((pc2 & (pc2 - 1)) == 0) {
                        pushL((rq + pc2 - 1) >>> Long.numberOfTrailingZeros(pc2));
                    } else {
                        pushL((rq + pc2 - 1) / pc2);
                    }
                    break;
                }
                case 6: { // EXTRACT_INGREDIENT
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
                    if (got < needed && PatternCompiler.isProcessingInput(key)) {
                        long remaining = needed - got;
                        for (AEKey variant : fuzzyFamilyOf(key)) {
                            if (variant.equals(key)) continue;
                            long vgot = simulation.extract(variant, remaining, Actionable.MODULATE);
                            if (vgot <= 0) continue;
                            long vint = simInternal.get(variant);
                            long vfromInt = Math.min(vgot, vint);
                            if (vfromInt > 0) simInternal.add(variant, -vfromInt);
                            long vfromNet = vgot - vfromInt;
                            if (!extractIsClaim && vfromNet > 0) usedItems.add(variant, vfromNet);
                            got += vgot;
                            remaining -= vgot;
                            if (remaining <= 0) break;
                        }
                    }
                    extractIsClaim = false;
                    pushL(Math.max(0, needed - got));
                    break;
                }
                case 7: { readShort(); popL(); break; } // RECORD_OUTPUT
                case 8: { readShort(); popL(); break; } // RECORD_INGREDIENT (legacy)
                case 9: { int idx=readShort(); long cnt=popL(); if(cnt>0) missingItems.add(constantPool[idx], cnt); break; } // RECORD_MISSING
                case 10: push(peek()); break; // DUP
                case 11: popL(); break; // POP
                case 12: { long b=popL(),a=popL(); pushL(b); pushL(a); break; } // SWAP
                case 13: { // RECORD_PATTERN
                    int idx = readShort(); IPatternDetails pat = patternPool[idx]; long times = popL();
                    if (times > 0) {
                        patternTimes.merge(pat, times, Long::sum);
                        simulation.addCrafting(pat, times);
                        simulation.addBytes((double)times);
                    }
                    break;
                }
                case 14: { // CALL
                    int pidx = readShort(); IPatternDetails pat = patternPool[pidx]; long ct = popL();
                    if (ct <= 0) break;
                    boolean isRoot = callStack.isEmpty();
                    if (isRoot) rootCraftTimes = ct;
                    // SELF-GROWTH LOOP CUT (v1.8.26): A -> 2A can never be fired.
                    if (isRoot && isUnseededSelfLoop(pat)) {
                        rootCraftTimes = 0;
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
                        break;
                    }
                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, pat);
                    if (sbc == null) { PatternCompiler.compileIfAbsent(networkKey, pat); sbc = PatternCompiler.getCompiled(networkKey, pat); }
                    if (sbc == null || callStack.size() >= MAX_CALL_DEPTH) break;
                    if (isRoot) {
                        Bundle snap = captureDelta();
                        resolvingKeys.add(outputKey);
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, outputKey)
                            .withBundle(outputKey, snap, ct));
                        loadBytecode(sbc); pushL(1);
                    } else {
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                        loadBytecode(sbc); pushL(ct);
                    }
                    break;
                }
                case 15: { // RETURN
                    if(callStack.isEmpty()){pc=code.length;break;}
                    CallFrame f=callStack.pop(); code=f.code; constantPool=f.constantPool;
                    patternPool=f.patternPool; pc=f.returnPc;
                    extractIsClaim = true;
                    if(f.resolvingKey!=null) {
                        if(f.bundleKey!=null && f.bundleKey.equals(f.resolvingKey)) {
                            Bundle after = captureDelta();
                            Bundle delta = diffBundle(after, f.bundleBefore);
                            if (f.subCalls != null && !f.subCalls.isEmpty()) {
                                for (Map.Entry<AEKey, Long> sc : f.subCalls.entrySet()) {
                                    AEKey sk = sc.getKey();
                                    long sreq = sc.getValue();
                                    if (cyclicCraftKeys.contains(sk)) continue;
                                    long sopc = 1;
                                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, patternResolver.apply(sk));
                                    if (sbc != null) sopc = sbc.getOutputAmountPerCraft();
                                    if (sreq > 0) {
                                        delta.itemNeeds.merge(sk, BigInteger.valueOf(sreq), BigInteger::add);
                                        if (sopc > 0) {
                                            long scts = (sreq + sopc - 1) / sopc;
                                            if (scts > 0) delta.needs.merge(sk, BigInteger.valueOf(scts), BigInteger::add);
                                        }
                                    }
                                }
                            }
                            if (f.fuzzySubCalls != null && !f.fuzzySubCalls.isEmpty()) {
                                for (Map.Entry<AEKey, Long> sc : f.fuzzySubCalls.entrySet()) {
                                    AEKey sk = sc.getKey();
                                    long sreq = sc.getValue();
                                    if (cyclicCraftKeys.contains(sk)) continue;
                                    if (sreq > 0) {
                                        delta.fuzzyItemNeeds.merge(sk, BigInteger.valueOf(sreq), BigInteger::add);
                                    }
                                }
                            }
                            Bundle[] bundles = bundleCache.computeIfAbsent(f.resolvingKey, k -> new Bundle[MAX_BUNDLE_BITS]);
                            bundles[0] = delta;
                            resolvingKeys.remove(f.resolvingKey);
                            boolean enclosingCapture = !callStack.isEmpty() && callStack.peek().bundleKey != null;
                            if (callStack.isEmpty()) {
                                revertBundle(delta);
                                extractIsClaim = true;
                            } else if (enclosingCapture) {
                                revertBundle(delta);
                                extractIsClaim = true;
                            } else if (f.savedReq > 1) {
                                revertBundle(delta);
                                pushL(f.savedReq);
                                pc = f.returnPc - 3;
                            } else {
                                revertBundle(delta);
                                applyBundle(delta);
                                extractIsClaim = true;
                            }
                        } else {
                            resolvingKeys.remove(f.resolvingKey);
                            extractIsClaim = true;
                        }
                    }
                    break;
                }
                case 16: { // CALL_BY_KEY with JIT for cts>1
                    int kidx = readShort(); AEKey tk = constantPool[kidx]; long req = popL();
                    if (req <= 0) break;
                    if (tk == null) {
                        break;
                    }
                    boolean slotFuzzy = currentSlotFuzzy;
                    currentSlotFuzzy = false;
                    IPatternDetails sub = patternResolver.apply(tk);
                    if (sub == null) {
                        AEKey ck = tk.dropSecondary();
                        if (!ck.equals(tk)) sub = patternResolver.apply(ck);
                    }
                    if (sub == null) {
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long availSim = simulation.extract(tk, req, Actionable.SIMULATE);
                        if (slotFuzzy) {
                            for (AEKey variant : fuzzyFamilyOf(tk)) {
                                if (variant.equals(tk)) continue;
                                availSim += simulation.extract(variant, req, Actionable.SIMULATE);
                            }
                        } else if (PatternCompiler.isProcessingInput(tk)) {
                            ensureRealStockSnapshot();
                            if (realStockCache != null) {
                                for (Map.Entry<AEKey, Long> fe : realStockCache.findFuzzy(tk,
                                        FuzzyMode.IGNORE_ALL)) {
                                    AEKey v = fe.getKey();
                                    if (v.equals(tk)) continue;
                                    availSim += simulation.extract(v, req, Actionable.SIMULATE);
                                }
                            }
                        }
                        long shortfall = req - availSim;
                        if (shortfall > 0) missingItems.add(tk, shortfall);
                        break;
                    }
                    // SELF-GROWTH LOOP CUT.
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
                        missingItems.add(tk, req); break;
                    }
                    if (circularCache.contains(tk)) {
                        missingItems.add(tk, req); break;
                    }
                    if (!resolvingKeys.add(tk)) {
                        // Cycle: consume whatever the network actually holds.
                        circularCache.add(tk);
                        CallFrame capFrame = callStack.peek();
                        if (capFrame != null && capFrame.bundleKey != null && !capFrame.bundleKey.equals(tk)) {
                            cyclicCraftKeys.add(capFrame.bundleKey);
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
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    long opc = sbc.getOutputAmountPerCraft();
                    long cts = opc <= 0 ? 0 : (req + opc - 1) / opc;
                    if (cts <= 0) { resolvingKeys.remove(tk); break; }

                    boolean capturing = !callStack.isEmpty() && callStack.peek().bundleKey != null;
                    if (!callStack.isEmpty()) callStack.peek().recordSubCall(tk, req);
                    if (slotFuzzy && !callStack.isEmpty()) {
                        callStack.peek().recordFuzzySubCall(tk, req);
                    }

                    Bundle[] bundles = bundleCache.computeIfAbsent(tk, k -> new Bundle[MAX_BUNDLE_BITS]);

                    if (capturing) {
                        if (bundles[0] == null) {
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                        } else if (!subBundlesComplete(bundles[0])) {
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
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, 1));
                            loadBytecode(sbc); pushL(cts);
                            break;
                        }
                        if (jitFailCache.contains(tk)) {
                            resolvingKeys.remove(tk);
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                            loadBytecode(sbc); pushL(1);
                            break;
                        }
                        Bundle b0 = bundles[0];
                        boolean sat1ok = true;
                        for (Map.Entry<AEKey, BigInteger> e : b0.used.entrySet()) {
                            long usedPerCall = toLongSafe(e.getValue(), "sat:" + e.getKey());
                            long internalPerCall = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            long netDrain = Math.max(0, usedPerCall - internalPerCall);
                            if (netDrain == 0) continue;
                            long totalAvail = simulation.extract(e.getKey(), netDrain, Actionable.SIMULATE);
                            long vmInternal = simInternal.get(e.getKey());
                            long realAvail = Math.max(0, totalAvail - vmInternal);
                            if (realAvail < netDrain) { sat1ok = false; break; }
                        }
                        if (sat1ok) {
                            applyBundle(b0);
                            extractIsClaim = true;
                            resolvingKeys.remove(tk);
                            break;
                        }
                        jitFailCache.add(tk);
                        resolvingKeys.remove(tk);
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                        loadBytecode(sbc); pushL(1);
                        break;
                    }

                    // cts>1
                    if (bundles[0] != null) {
                        Bundle b0 = bundles[0];
                        boolean selfSufficient = true;
                        for (Map.Entry<AEKey, BigInteger> e : b0.used.entrySet()) {
                            long internal = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            if (toLongSafe(e.getValue(), "jit") > internal) { selfSufficient = false; break; }
                        }
                        if (selfSufficient) {
                            applyBundle(b0.scale(cts));
                            resolvingKeys.remove(tk);
                            break;
                        }
                        applyBundleDeficit(b0.scale(cts));
                        resolvingKeys.remove(tk);
                        break;
                    }

                    Bundle snap = captureDelta();
                    callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                        .withBundle(tk, snap, req));
                    loadBytecode(sbc); pushL(1);
                    break;
                }
                case 20: currentSlotFuzzy = true; break; // FUZZY_SLOT (0x14)
                case 17: { int idx=readShort(); long amt=popL(); // INSERT_OUTPUT
                    if(amt>0){
                        simulation.insert(constantPool[idx],amt,Actionable.MODULATE);
                        simInternal.add(constantPool[idx], amt);
                        emittedItems.add(constantPool[idx], amt);
                    }
                    break;
                }
                case 18: { // CATALYST_SEED <keyIdx> — one-time catalyst/container seed demand
                    int idx = readShort(); long amt = popL();
                    if (amt > 0 && constantPool[idx] != null) {
                        catalystSeedItems.add(constantPool[idx], amt);
                    }
                    break;
                }
                case 19: { // DURABILITY_TOOL <keyIdx> — finite-use tool rate (amount, uses)
                    int idx = readShort(); long uses = popL(); long amt = popL();
                    if (amt > 0 && uses > 0 && constantPool[idx] != null) {
                        durabilityItems.put(constantPool[idx], new long[]{amt, uses});
                    }
                    break;
                }
                case 255: { // HALT
                    simulation.addBytes(nodeCount*8.0);
                    if(rootCraftTimes>0&&outputKey!=null) simulation.addStackBytes(outputKey,1,rootCraftTimes);
                    ICraftingPlan plan = buildPlan(requestedAmount);
                    logPerfLine(vmStartNs);
                    return plan;
                }
                default: break; // unknown opcode, skip
            }
        }
        ICraftingPlan plan = buildPlan(requestedAmount);
        logPerfLine(vmStartNs);
        return plan;
    }

    /** Performance log: total calc time for this request (microsecond precision). */
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

        long bytes = (long) Math.ceil(simulation.getBytes());
        long deliver;
        if (requestedAmount.compareTo(BIG_MAX_LONG) > 0) {
            deliver = Long.MAX_VALUE; batchRemainder = requestedAmount.subtract(BIG_MAX_LONG);
        } else { deliver = requestedAmount.longValue(); batchRemainder = null; }
        // A complete plan (no missing items) must be simulation=false.
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

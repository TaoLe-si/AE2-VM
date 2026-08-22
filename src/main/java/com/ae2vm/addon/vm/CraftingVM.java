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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // (v1.13.1, ported from AE2VMAddon-1.21.1) Persistent per-VM resolver cache.
    // resolve() stores both positive (IPatternDetails) and negative (long[] sentinel
    // with TTL) results so the warm-hit guards and staleMissingNowCraftable check can
    // query the resolver outcome WITHOUT re-running the full three-tier resolver.
    private final java.util.Map<AEKey, Object> resolverCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** (v1.13.1, ported from AE2VMAddon-1.21.1) Expose the persistent resolver cache. */
    public java.util.Map<AEKey, Object> getResolverCache() {
        return resolverCache;
    }

    /** (v1.12.x DIAG) Primary-output name of a pattern, for the CALL_BY_KEY trace log. */
    private static String patternOutputName(IPatternDetails p) {
        try {
            var outs = p.getOutputs();
            if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                return outs[0].what().toString();
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    /** (v1.12.x DIAG) Null-safe input count for the CALL_BY_KEY trace log. */
    private static String patternInputCountSafe(IPatternDetails p) {
        if (p == null) return "-";
        try {
            var ins = p.getInputs();
            return ins == null ? "null" : String.valueOf(ins.length);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    // (v1.10.3) Optional resolver returning ALL patterns that produce a key (not just the
    // one chosen by patternResolver). Used ONLY by the pure-conversion-ring feasibility
    // analysis, which needs the full ring (A↔B↔C) to see every exchange orientation —
    // a single chosen pattern per key hides edges and makes the ring look incomplete.
    // The benchmark harness supplies it from the reference graph; null falls back to the
    // chosen pattern alone (ring detection is then best-effort).
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
    // (v1.10.x CATALYST) One-time catalyst/container seed demands (key → total seed amount
    // recorded by the CATALYST_SEED opcode). Consumed by captureDelta() into each bundle's
    // `seeds` map; applyBundleDirect() requires each seed from stock exactly once.
    private KeyCounter catalystSeedItems;
    // (v1.10.x DURABILITY) Finite-use tool rates (key → [amount, uses]) recorded by the
    // DURABILITY_TOOL opcode. Consumed by captureDelta() into each bundle's `durability` map;
    // applyAggregation() demands amount × ceil(total/uses) tools per pattern from stock.
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
    private final java.util.Set<AEKey> cyclicCraftKeys = new java.util.HashSet<>(); // sub-crafts whose pattern hits a cross-cycle → stock-only, not craftable
    private final java.util.Set<AEKey> jitFailCache = new java.util.HashSet<>(); // patterns whose JIT memo is unsatisfiable → run normal exec
    // O(1): lazily snapshotted real network stock. The live inventory cannot change
    // during a single simulation, so we snapshot it once and reuse instead of calling
    // getAvailableStacks() (an O(storage) walk) for every finalized aggregation key.
    private KeyCounter realStockCache;
    // (v1.10.x CATALYST) Initial network stock snapshot taken at the very START of
    // execute() (fresh simulation — before the capture phase consumes anything), for
    // every key the plan touches (walked from the request + sub-pattern bytecode
    // constant pools). The feedback-loop working-capital computation needs the ORIGINAL
    // stock; the capture phase does NOT restore consumed leaf stock into the sandbox.
    private KeyCounter executeStartStock;
    private java.util.Map<AEKey, BigInteger> lastItemDemand; // (v1.14.x DIAG) aggregation item demand snapshot
    // (v1.12.x GTL FAST PATH) Memoized PLAN from the previous full slow-path execute.
    // Reused on warm requests with the same (outputKey, rootCraftTimes, patternVersion)
    // when every used key is a PURE LEAF (no pattern → stock-independent) and current
    // stock still covers the used amounts (leaf stock guard). Correct by construction:
    // the cached values are literally what the slow path produced.
    private AEKey fastPlanKey;
    private long fastPlanRootCraftTimes;
    private long fastPlanBytes;
    private KeyCounter fastPlanUsed;
    private KeyCounter fastPlanMissing;
    private KeyCounter fastPlanEmitted;
    private java.util.Map<IPatternDetails, Long> fastPlanPatterns;
    // (v1.10.3 RECURSION) Root request size (BigInteger from execute) — drives the
    // amplifier craft-count correction (ceil((request − seed)/net) instead of
    // ceil(request/output), because each craft re-seeds the next).
    private BigInteger requestAmount;
    // (v1.10.3 RECURSION) Self-adjacent patterns discovered during aggregation:
    // patternKey → (selfKey → {in, out} per craft). A self key is both a consumed input
    // and a produced output of the SAME pattern (amplifier A+B→2A, essence A+B→A+C);
    // applyOrdered collapses its used demand to a one-time seed.
    private Map<AEKey, Map<AEKey, long[]>> selfAdjacentKeys;
    private boolean extractIsClaim; // RETURN sets this; next EXTRACT skips usedItems (sub-craft claim)
    // (v1.10.x video fix) Set by FUZZY_SLOT and consumed by the NEXT CALL_BY_KEY: the
    // dispatch comes from a replacement-enabled (fuzzy) input slot, so substitute
    // variants may satisfy it (leaf availability + fuzzy stock aggregation). Exact
    // slots (single possible input) leave it false — only the primary key may satisfy
    // them, otherwise AE2's CPU execution stalls on a plan that extracts a substitute
    // the exact pattern cannot consume.
    private boolean currentSlotFuzzy;
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
    // (v1.11.x PATTERN-REFRESH) Pattern-set version this VM's bundleCache was captured
    // against. When PatternCompiler.patternVersion() differs at the next execute(), the
    // stale JIT bundles are dropped (see the version check in execute()).
    private long lastPatternVersion = -1;
    // (v1.11.8 PERF) Per-execute memo for staleMissingRecheck: key → [bundle-ref, result].
    // A stale check on the SAME bundle reference returns O(1) instead of re-walking the
    // whole itemNeeds subtree once per reuse (N reuses × M-node subtree would be O(N×M)
    // repeated work on deep chains). The bundle reference guards correctness: when a
    // bundle is re-captured (new reference), the memo entry is stale and the check runs
    // again. Cleared at the start of every execute() (patterns/stock may have changed).
    private final Map<AEKey, Object[]> staleMemo = new HashMap<>();
    // (v1.12.x GTL OSCILLATION FIX) Per-execute set of keys that have been re-captured
    // via staleMissingRecheck in this execute. Prevents infinite re-capture loops when a
    // GTL pattern resolves (sub!=null) but the VM still reports missing (synthetic
    // pattern via 超限演算阵列 / Overclocked Calculation Array).
    private final java.util.Set<AEKey> recapturedInThisExecute = new java.util.HashSet<>();
    
    private record CallFrame(int returnPc, byte[] code, AEKey[] constantPool, 
                             IPatternDetails[] patternPool, AEKey resolvingKey,
                             AEKey bundleKey, Bundle bundleBefore, long savedReq,
                             java.util.Map<AEKey, Long> subCalls,
                             java.util.Map<AEKey, Long> fuzzySubCalls, boolean cycleCut) {
        CallFrame(int returnPc, byte[] code, AEKey[] constantPool, 
                  IPatternDetails[] patternPool, AEKey resolvingKey) {
            this(returnPc, code, constantPool, patternPool, resolvingKey, null, null, 0, null, null, false);
        }
        CallFrame withBundle(AEKey key, Bundle before, long req) {
            return new CallFrame(returnPc, code, constantPool, patternPool, resolvingKey, key, before, req,
                    new java.util.HashMap<>(), new java.util.HashMap<>(), false);
        }
        // Records a directly-resolved sub-call (key, item-amount) on a dispatch frame.
        CallFrame recordSubCall(AEKey k, long r) {
            if (subCalls != null) subCalls.merge(k, r, Long::sum);
            return this;
        }
        // (v1.10.x video fix) Records a sub-call dispatched from a REPLACEMENT-ENABLED
        // (fuzzy) input slot. The aggregation may satisfy ONLY this portion of the
        // child's demand with substitute-variant stock; exact slots can only use the
        // primary key (otherwise AE2's CPU execution stalls).
        CallFrame recordFuzzySubCall(AEKey k, long r) {
            if (fuzzySubCalls != null) fuzzySubCalls.merge(k, r, Long::sum);
            return this;
        }
        // (v1.14.x JIT-GRAPH) Marks this dispatch frame as a SEEDED-RING REDUNDANT
        // producer: its output key is available from real stock, so its INSERT_OUTPUT
        // must be suppressed (no fake fabrication into simInternal) and its needs
        // skipped (stock-only) — otherwise the parent consumes the ring's fake output
        // instead of the network stock (used=0 false plans).
        CallFrame withCycleCut() {
            return new CallFrame(returnPc, code, constantPool, patternPool, resolvingKey,
                    bundleKey, bundleBefore, savedReq, subCalls, fuzzySubCalls, true);
        }
    }
    
    private static class Bundle {
        BigInteger bytes = BigInteger.ZERO;
        // (v1.12.x GTL PATTERN-IDENTITY) The pattern instance this bundle was captured
        // against. On reuse, the VM re-resolves the key and re-captures when the player
        // swapped / modified the pattern (new IPatternDetails with different content) —
        // otherwise the stale bundle keeps the OLD recipe's inputs/outputs and the plan
        // keys an OLD pattern the providers no longer expose (false positive → CPU stall,
        // or false negative → wrong missing).
        volatile IPatternDetails capturedFor;
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
        // (v1.10.x video fix) FUZZY sub-pattern ITEM needs: the portion of `itemNeeds`
        // that was demanded by a REPLACEMENT-ENABLED (fuzzy) input slot. Only this
        // portion may be satisfied by substitute-variant stock in the stock-aware
        // aggregation; an EXACT slot (single possible input) can only use its primary
        // key, otherwise the plan extracts a substitute the exact pattern cannot consume
        // and AE2's CPU execution stalls (the 2026-08-09 video bug). Populated from
        // CallFrame.fuzzySubCalls in the RETURN handler, parallel to `itemNeeds`.
        final Map<AEKey, BigInteger> fuzzyItemNeeds = new java.util.concurrent.ConcurrentHashMap<>();
        // (v1.10.x CATALYST) ONE-TIME catalyst/container seed demands (key → seed amount),
        // required once per BATCH, NOT scaled by craft count. A `returned` input (e.g. a
        // crafting template / greenhouse block) is handed back unchanged after every firing,
        // so the whole batch needs only `amount` as a seed (reference closed form
        // `unitsFor(times) = amount`). scale() deliberately keeps seeds constant.
        final Map<AEKey, BigInteger> seeds = new java.util.concurrent.ConcurrentHashMap<>();
        // (v1.10.x DURABILITY) FINITE-USE tool demands (key → [amount, uses]): `amount` units
        // are consumed per firing and one full amount-sized unit survives `uses` firings (a
        // degrading catalyst). A rate, NOT scaled — the aggregation demands
        // amount × ceil(totalFirings/uses) tools from stock (shortfall → missing).
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
            // Seeds are one-time per batch — do NOT multiply them (catalyst/returned input).
            seeds.forEach((k, v) -> b.seeds.put(k, v));
            // Durability is a rate (amount × ceil(times/uses)) — do NOT multiply the rate.
            durability.forEach((k, v) -> b.durability.put(k, v));
            return b;
        }
        
        boolean isEmpty() {
            return bytes.signum() == 0 && used.isEmpty() && emitted.isEmpty() && missing.isEmpty() 
                && internal.isEmpty() && patterns.isEmpty() && needs.isEmpty() && itemNeeds.isEmpty()
                && fuzzyItemNeeds.isEmpty() && seeds.isEmpty() && durability.isEmpty();
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
     * (v1.12.x GTL BIG-ORDER FIX) Saturating ceil-division. {@code (a + b - 1) / b}
     * overflows when {@code a} is near {@link Long#MAX_VALUE} (10^18+ orders) and yields
     * a NEGATIVE craft count — the VM then silently crafts nothing and the plan reports
     * false missing. The remainder form never overflows for positive longs.
     */
    private static long ceilDiv(long a, long b) {
        if (a <= 0L) return 0L;
        if (b <= 0L) return 0L;
        return a / b + (a % b == 0L ? 0L : 1L);
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
        // (v1.10.x CATALYST) One-time catalyst/container seed demand: require the seed
        // amount from stock (or produced by an earlier pattern) exactly once per batch.
        // A `returned` input is handed back unchanged after every firing, so the whole
        // batch needs only `amount` as a seed. This is deliberately NOT scaled by craft
        // count (see Bundle.scale). If the seed is absent, the shortfall is missing.
        for (var e : b.seeds.entrySet()) {
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
            // (v1.10.3 FUZZY) A host-owned reusable-stock seed (returnedFrom) can be
            // satisfied by any accepted physical variant of its fuzzy group (e.g. a
            // logical_tool slot satisfied by a stocked damaged_tool). If the primary
            // seed is short, consume the variant actually present in the network.
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
        // (v1.10.x CATALYST) The initial network stock was snapshotted at execute()
        // START (fresh simulation) — see snapshotExecuteStartStock(). The feedback-loop
        // working-capital computation uses it to decide seed availability against the
        // ORIGINAL stock (the bench seeds stock via its simulation state, so realStockOf()
        // alone returns 0 for a String network key, and the capture phase does NOT restore
        // consumed leaf stock into the sandbox).
        KeyCounter initialStock = executeStartStock;
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
        // (v1.10.x video fix) The portion of each child's item demand that was demanded
        // by REPLACEMENT-ENABLED (fuzzy) input slots. Only this portion may be satisfied
        // by substitute-variant stock in the stock-aware branch; EXACT slots (single
        // possible input) can only use the primary key, otherwise the plan extracts a
        // substitute the exact pattern cannot consume and AE2's CPU execution stalls.
        Map<AEKey, BigInteger> fuzzyItemDemand = new HashMap<>();
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
                // (v1.10.x video fix) Accumulate the fuzzy (replacement-enabled) portion of
                // this parent's demand for the child — caps at the child's total demand below.
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
                        // (v1.10.x) PROCESSING-RECIPE DEFAULT FUZZY: processing recipe inputs
                        // also count the item's FULL fuzzy family (any NBT variant) as stock —
                        // a greenhouse block stored under a different NBT still satisfies the
                        // fake-craft slot (GTL greenhouse / Mystical Agriculture essence).
                        // (v1.10.x video fix) Stock-aware sub-craft with EXACT-vs-FUZZY slot
                        // split. Two kinds of "fuzzy" variants must be treated differently:
                        //   - SAME-ITEM NBT variants (processing-recipe default fuzzy): a
                        //     processing slot extracts them via findFuzzyTemplates, so they are
                        //     usable by ANY slot — fold them into the primary stock.
                        //   - DIFFERENT-ITEM replacement variants (item/fluid replacement,
                        //     getPossibleInputs() > 1): usable ONLY by a replacement-ENABLED
                        //     slot. An EXACT slot (single possible input) can only use its
                        //     PRIMARY key; applying the global fuzzy group to it made the plan
                        //     extract a substitute the exact pattern cannot consume and AE2's
                        //     CPU execution stalled at zero progress (the 2026-08-09 video bug).
                        // The fuzzy (replacement-enabled) portion of the demand is tracked per
                        // child in fuzzyItemDemand from the captured fuzzyItemNeeds.
                        java.util.Set<AEKey> replacementGroup = PatternCompiler.getFuzzyGroup(c);
                        boolean hasReplacement = replacementGroup.size() > 1;
                        // Primary-equivalent stock: the primary key + same-item NBT variants
                        // (processing default fuzzy). Usable by exact AND fuzzy slots.
                        long primaryStock = realStockOf(c);
                        java.util.Set<AEKey> nbtVariants = new java.util.HashSet<>();
                        if (PatternCompiler.isProcessingInput(c)) {
                            ensureRealStockSnapshot();
                            if (realStockCache != null) {
                                for (var fe : realStockCache.findFuzzy(c,
                                        appeng.api.config.FuzzyMode.IGNORE_ALL)) {
                                    AEKey v = fe.getKey();
                                    if (v.equals(c) || replacementGroup.contains(v)) continue;
                                    nbtVariants.add(v);
                                    primaryStock += realStockOf(v);
                                }
                            }
                        }
                        // Different-item replacement variants — usable ONLY by fuzzy slots.
                        long substituteStock = 0;
                        if (hasReplacement) {
                            for (AEKey v : replacementGroup) {
                                if (v.equals(c)) continue;
                                substituteStock += realStockOf(v);
                            }
                        }
                        // Split demand by slot kind (capped at the total demand).
                        BigInteger fuzzyDemand = fuzzyItemDemand.getOrDefault(c, BigInteger.ZERO);
                        if (fuzzyDemand.signum() < 0) fuzzyDemand = BigInteger.ZERO;
                        if (fuzzyDemand.compareTo(demand) > 0) fuzzyDemand = demand;
                        BigInteger exactDemand = demand.subtract(fuzzyDemand);
                        long fromStock = 0;
                        // Primary stock satisfies EXACT demand first (exact slots can only use
                        // the primary), then the remaining primary stock covers fuzzy demand.
                        long primaryForExact = 0, primaryForFuzzy = 0;
                        if (primaryStock > 0 && demand.signum() > 0) {
                            primaryForExact = Math.min(primaryStock, toLongSafe(exactDemand, "prim-exact:" + c));
                            primaryForFuzzy = Math.min(primaryStock - primaryForExact,
                                    toLongSafe(fuzzyDemand, "prim-fuzzy:" + c));
                            long consumedPrimary = primaryForExact + primaryForFuzzy;
                            if (consumedPrimary > 0) {
                                // Distribute across the primary + same-item NBT variants so the
                                // plan's usedItems names the ACTUAL variant the network holds.
                                long remaining = consumedPrimary;
                                java.util.List<AEKey> primaries = new java.util.ArrayList<>(nbtVariants.size() + 1);
                                primaries.add(c);
                                primaries.addAll(nbtVariants);
                                for (AEKey v : primaries) {
                                    long s = realStockOf(v);
                                    if (s <= 0) continue;
                                    long take = Math.min(remaining, s);
                                    if (take <= 0) continue;
                                    // STOCK-AWARE SUB-CRAFT FIX (v1.8.23): record the real
                                    // network stock directly as network-used so the CPU extracts
                                    // it at submit time. Still consume what the sandbox sim
                                    // actually holds so later used-extraction stays consistent.
                                    usedItems.add(v, take);
                                    simulation.extract(v, take, Actionable.MODULATE);
                                    // Zero the parents' captured used[v] for the consumed amount
                                    // (the parent's EXTRACT read the same stock during capture).
                                    stockFromNetwork.merge(v, BigInteger.valueOf(take), BigInteger::add);
                                    remaining -= take;
                                }
                                fromStock += consumedPrimary;
                            }
                        }
                        // Different-item substitute stock satisfies ONLY the still-remaining
                        // FUZZY demand (replacement-enabled slots only).
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
                        // Zero the parents' captured used[replacement-variant]: the variant is a
                        // finite one-time pool, consumed here — but only the FUZZY portion of
                        // the demand, so exact-slot parents keep their primary demand intact.
                        // The shared pool drains across sibling parents (each subtracts only
                        // what it recorded). NBT variants were already zeroed above (by take).
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
        // (v1.10.3 RECURSION) Self-adjacent patterns: a pattern whose own output key
        // (primary OR byproduct) is also one of its own consumed inputs. Its self-production
        // offsets its self-consumption, so the key is a one-time SEED (the first unit primes
        // the loop) plus an amplifier (net = out − in). Without this the aggregation demands
        // in×crafts of the key from stock AND the bundle's own emitted output masks the
        // missing in applyBundleDirect — reporting a self-referential recipe as feasible with
        // NO seed (the video's "卡着/stuck" bug: A+B→2A recursion, A+B→A+C essence catalyst).
        this.selfAdjacentKeys = computeSelfKeys(total);
        if (selfAdjacentKeys != null && !selfAdjacentKeys.isEmpty()) {
            correctRecursion(total, initialStock);
        }
        // Phase 3: apply each bundle exactly once, children before parents.
        // NOTE: kept sequential — parallelizing the per-key bundle scaling (parallelStream
        // over ~dozens of tiny BigInteger multiplies) added more fork/join overhead than it
        // saved on the large order (v1.9.x measurement: 1-billion request got slower).
        java.util.Set<AEKey> applied = new java.util.HashSet<>();
        for (AEKey k : total.keySet()) applyOrdered(k, applied, total);
        // (v1.10.x CATALYST) Feedback loops (raw/lossy catalyst cycles): a pattern's
        // BYPRODUCT closes the loop (e.g. A->2B, 2B+C->E+D, D->A), so that byproduct is
        // NOT a missing leaf — the loop is feasible iff its WORKING CAPITAL (a seed) is
        // stocked. Compute the exact minimum seed via a forward simulation (fire each
        // pattern its total times from the initial stock; on deadlock inject the minimum
        // input deficit) and OVERRIDE the loop items' (false) missing. Pure DAGs and
        // byproduct-free cycles (conversion-ring) have no byproduct SCC → no-op.
        Map<AEKey, Long> loopMissing = computeFeedbackLoopMissing(total, initialStock);
        if (!loopMissing.isEmpty()) {
            for (var e : loopMissing.entrySet()) {
                // Override the loop item's (false) missing with the computed working capital.
                missingItems.remove(e.getKey());
                if (e.getValue() > 0) missingItems.add(e.getKey(), e.getValue());
            }
        }
        // (v1.10.3) Pure-conversion-ring feasibility guard: a byproduct-free exchange ring
        // (9B→A, 1A→9B, 9C→B, 1B→9C) is value-conserving — it converts stocked items but
        // cannot create them from nothing. The VM's capture emits ring outputs for free when
        // the ring was unstocked, so this ADDS the ring-value deficit as missing (never
        // removes), closing the dangerous false-positive where a seedless ring reported
        // feasible. No-op for DAGs, byproduct-fed loops and value-sufficient rings.
        RingResult ringResult = computeConversionRingMissingEx(total, initialStock);
        if (ringResult.feasible.size() > 0) {
            // (v1.15.x GTL 1:1) A value-sufficient pure-conversion ring can satisfy the
            // external demand by exchanging stocked items along the ring (e.g. dust
            // smelted to ingot). The capture-phase CYCLE/CYCLE-CUT guards have already
            // MODULATE'd any available stock into usedItems and recorded the shortfall as
            // missing on ring members — both are wrong for a value-sufficient ring: the
            // ring's demand is satisfied, and the craft chain's sub-patterns should
            // dispatch the right orientation (e.g. dust→ingot) using the stock we have.
            // Strip the residual missing on the ring's members so the plan reports
            // feasible; the value deficit (if any) is reported on the smallest-value key
            // below.
            for (AEKey member : ringResult.feasible) {
                missingItems.remove(member);
            }
        }
        if (!ringResult.missing.isEmpty()) {
            for (var e : ringResult.missing.entrySet()) {
                missingItems.add(e.getKey(), e.getValue());
            }
        }
        this.lastItemDemand = itemDemand == null ? java.util.Map.of() : new java.util.HashMap<>(itemDemand);
        // AGG DIAG disabled (v1.8.20) — keep log clean, only total calc time.
        // BigInteger rootTotal = total.getOrDefault(outputKey, BigInteger.ZERO);
        // Bundle[] rootArr = bundleCache.get(outputKey);
        // AE2VMAddon.LOGGER.info("[AE2-VM] AGG root={} rootCraftTimes={} rootBundle={} itemNeeds={}",
        //     rootTotal, rootCraftTimes,
        //     (rootArr != null && rootArr[0] != null) ? rootArr[0].itemNeeds.keySet().size() : -1,
        //     (rootArr != null && rootArr[0] != null) ? rootArr[0].itemNeeds : "null");
    }

    /**
     * (v1.10.3 RECURSION) Detect self-adjacent patterns: a pattern whose own output key
     * (primary OR byproduct) is also one of its own NON-returned consumed inputs. The
     * pattern's self-production offsets its self-consumption, so the key behaves like a
     * one-time SEED (the first unit primes the loop) plus an amplifier (net = out − in).
     *
     * <p>Returns {@code patternKey → (selfKey → {in, out} per craft)}. An unseeded
     * self-loop (A→2A) is EXCLUDED — {@link #isUnseededSelfLoop} already cuts it at
     * dispatch time and it must never fire. Returned/catalyst inputs (getRemainingKey ==
     * input) are also excluded — they are already seeds, not per-craft consumptions.
     */
    private Map<AEKey, Map<AEKey, long[]>> computeSelfKeys(Map<AEKey, BigInteger> total) {
        Map<AEKey, Map<AEKey, long[]>> result = new HashMap<>();
        if (patternResolver == null) return result;
        for (var en : total.entrySet()) {
            AEKey key = en.getKey();
            if (key == null || en.getValue().signum() <= 0) continue;
            Bundle[] arr = bundleCache.get(key);
            if (arr == null || arr[0] == null) continue;
            IPatternDetails details = patternResolver.apply(key);
            if (details == null || isUnseededSelfLoop(details)) continue;
            // Per-craft consumed inputs (returned/catalyst/durability inputs excluded — they
            // are seeds/tools, not per-craft consumptions).
            Map<AEKey, Long> inputs = new HashMap<>();
            if (details.getInputs() != null) {
                for (IPatternDetails.IInput in : details.getInputs()) {
                    var possible = in.getPossibleInputs();
                    if (possible == null || possible.length == 0 || possible[0] == null
                            || possible[0].what() == null) continue;
                    AEKey ik = possible[0].what();
                    AEKey rem = in.getRemainingKey(ik);
                    if (rem != null && rem.equals(ik)) continue;
                    long amt = in.getMultiplier() * Math.max(1L, possible[0].amount());
                    inputs.merge(ik, amt, Long::sum);
                }
            }
            if (inputs.isEmpty()) continue;
            // Per-craft outputs (primary + byproducts). getOutputs() is a List on 1.21.1
            // and a GenericStack[] on 1.20.1 — enhanced-for handles both.
            Map<AEKey, Long> outputs = new HashMap<>();
            if (details.getOutputs() != null) {
                for (GenericStack gs : details.getOutputs()) {
                    if (gs == null || gs.what() == null) continue;
                    outputs.merge(gs.what(), (long) gs.amount(), Long::sum);
                }
            }
            if (outputs.isEmpty()) continue;
            Map<AEKey, long[]> self = new HashMap<>();
            for (var e : inputs.entrySet()) {
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
     *
     * <p>(a) Seed requirement: every self key needs {@code in} units stocked once to prime
     * the loop. If the seed is absent, the pattern CANNOT fire — report exactly the missing
     * seed and zero the craft count (the amplifier/essence recipes are "stuck" without it).
     *
     * <p>(b) Primary-output amplifier (net = out − in &gt; 0, e.g. A+B→2A): each craft's
     * output partly re-seeds the next, so the craft count is driven by the NET growth,
     * {@code ceil((request − seedStock) / net)}, not {@code ceil(request / output)} — the
     * naive count fires too few times and would leave the request unmet. The seed stock
     * already reduces the request (a stocked seed doubles as request coverage).
     */
    private void correctRecursion(Map<AEKey, BigInteger> total, KeyCounter initialStock) {
        for (var en : selfAdjacentKeys.entrySet()) {
            AEKey k = en.getKey();
            Map<AEKey, long[]> self = en.getValue();
            BigInteger cur = total.getOrDefault(k, BigInteger.ZERO);
            if (cur.signum() <= 0) continue;
            long t = toLongSafe(cur, "rec-total:" + k);
            if (t <= 0) continue;
            // (a) Seed requirement.
            for (var se : self.entrySet()) {
                long in = se.getValue()[0];
                if (in <= 0) continue;
                long s = stockOf(initialStock, se.getKey());
                if (s < in) {
                    // Cannot prime the recursion — report the missing seed, do not fire.
                    missingItems.add(se.getKey(), in - s);
                    t = 0;
                }
            }
            if (t <= 0) {
                total.put(k, BigInteger.ZERO);
                continue;
            }
            // (b) Primary-output amplifier (net > 0): correct the craft count.
            for (var se : self.entrySet()) {
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
     * start of {@link #execute(CraftingBytecode, CraftingSimulationState, BigInteger)}
     * — the simulation is fresh there, but the capture phase does NOT restore consumed
     * leaf stock into the sandbox, so reading later would see 0 for a stocked leaf (A in
     * the raw/lossy catalyst loops). Every key the plan will touch is collected from the
     * recipe graph through the pattern resolver (root output → pattern inputs/outputs →
     * craftable inputs' patterns, recursively); a SIMULATE extract lazily caches the
     * parent's available stacks (no side effect for a fresh cache).
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
     * pattern resolver. This covers leaves and byproducts too — a sub-call is dispatched
     * via CALL_BY_KEY (a constant-pool key), NOT the bytecode pattern pool, so a
     * bytecode-only walk would miss leaf inputs like A in the raw catalyst loop.
     */
    private void collectPlanKeys(AEKey key, java.util.Set<AEKey> keys, java.util.Set<AEKey> visited) {
        if (key == null || !visited.add(key)) return;
        keys.add(key);
        IPatternDetails p = patternResolver != null ? patternResolver.apply(key) : null;
        if (p == null) return;
        if (p.getInputs() != null) {
            for (IPatternDetails.IInput in : p.getInputs()) {
                var possible = in.getPossibleInputs();
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


    /**
     * (v1.14.x DEFINITION-GRAPH) True when the VM is currently CAPTURING a parent
     * bundle (a frame with a bundleKey sits on the call stack). During capture the ring
     * branches (CYCLE / circular / PLAN-A) only mutate the simulation sandbox; their
     * used/missing bookkeeping is deferred to the aggregation (stock-aware + bundle
     * replay), which settles ring consumption exactly once — the capture-time records
     * would double-count (3-hop seed: EXTRACT 1 + gotx 1 per craft → 6 > stock 5 →
     * false used=5 miss=1).
     */
    private boolean capturingBundle() {
        return !callStack.isEmpty() && callStack.peek().bundleKey() != null;
    }

    /** (v1.14.x JIT-GRAPH) Capture-time ring probes must NOT mutate the sandbox:
     * the parent's EXTRACT (with extractIsClaim=false after a cycle-cut RETURN) records
     * the used from real stock exactly once; a MODULATE here would permanently drain
     * the stock during capture (reflow seed 2A -> used=1 miss=1 instead of used=2). */
    private Actionable captureAction() {
        return capturingBundle() ? Actionable.SIMULATE : Actionable.MODULATE;
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
     * working capital (seed), returning {@code loop item -> seed required beyond stock}
     * (0 = the loop provides it itself). Empty when the plan has no feedback loop.
     *
     * <p>A feedback loop is a strongly-connected component of the item graph
     * (item i → item j if an in-plan pattern consumes i and produces j — primary OR
     * byproduct) that contains at least one BYPRODUCT edge. The byproduct closes the
     * loop (e.g. {@code D} in {@code A->2B, 2B+C->E+D, D->A}), so it is NOT a missing
     * leaf — the loop is feasible iff its working capital (minimum seed) is stocked.
     *
     * <p>The working capital is computed by a forward simulation: start from the real
     * network stock, fire each pattern its required number of times (greedy); on
     * deadlock, inject the minimum input deficit (ties prefer a PRIMARY-output item —
     * the catalyst seed is a real craftable item, not a byproduct). The total injected
     * beyond stock is exactly the seed that must be present. Only LOOP items' missing
     * is overridden (byproducts / deficit leaves inside the loop); everything else
     * keeps its normal missing. A pure DAG or a byproduct-free cycle (conversion-ring)
     * has no byproduct SCC → empty → no change.
     */
    private Map<AEKey, Long> computeFeedbackLoopMissing(Map<AEKey, BigInteger> total,
            KeyCounter initialStock) {
        // 1) Structural per-craft recipe of every in-plan pattern.
        Map<AEKey, LoopPattern> pats = new HashMap<>();
        java.util.Set<AEKey> primaryOutputs = new java.util.HashSet<>();
        for (var en : total.entrySet()) {
            AEKey key = en.getKey();
            if (key == null || en.getValue().signum() <= 0) continue;
            Bundle[] arr = bundleCache.get(key);
            if (arr == null || arr[0] == null) continue;
            IPatternDetails details = patternResolver != null ? patternResolver.apply(key) : null;
            if (details == null) continue;
            Map<AEKey, Long> inputs = new HashMap<>();
            if (details.getInputs() != null) {
                for (IPatternDetails.IInput in : details.getInputs()) {
                    var possible = in.getPossibleInputs();
                    if (possible == null || possible.length == 0 || possible[0] == null
                            || possible[0].what() == null) continue;
                    AEKey ik = possible[0].what();
                    // A returned/catalyst input is a seed, not a per-craft consumption.
                    AEKey rem = in.getRemainingKey(ik);
                    if (rem != null && rem.equals(ik)) continue;
                    long amt = in.getMultiplier() * Math.max(1L, possible[0].amount());
                    inputs.merge(ik, amt, Long::sum);
                }
            }
            Map<AEKey, Long> outputs = new HashMap<>();
            java.util.Set<AEKey> byproducts = new java.util.HashSet<>();
            // NOTE (1.20.1): IPatternDetails.getOutputs() returns GenericStack[] (a List in
            // 1.21.1) — iterate the array, first element is the primary output.
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
        if (pats.isEmpty()) return Map.of();

        // 2) Find SCCs of the item graph (i -> j if a pattern consumes i, produces j)
        //    that contain a byproduct edge — those are catalyst feedback loops.
        Map<AEKey, java.util.Set<AEKey>> graph = new HashMap<>();
        for (LoopPattern p : pats.values()) {
            for (AEKey i : p.inputs.keySet()) {
                graph.computeIfAbsent(i, x -> new java.util.HashSet<>()).addAll(p.outputs.keySet());
            }
        }
        java.util.Set<AEKey> loopItems = new java.util.HashSet<>();
        for (var scc : tarjanScc(graph)) {
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
        if (loopItems.isEmpty()) return Map.of();

        // 3) Working-capital simulation: fire each pattern total[k] times from the real
        //    stock; on deadlock inject the minimum input deficit (ties → primary output).
        Map<AEKey, Long> available = new HashMap<>();
        if (initialStock != null) {
            for (var e : initialStock) {
                if (e.getLongValue() > 0) available.put(e.getKey(), e.getLongValue());
            }
        }
        Map<AEKey, Long> remaining = new HashMap<>();
        for (var en : total.entrySet()) {
            if (en.getValue().signum() <= 0 || !pats.containsKey(en.getKey())) continue;
            remaining.put(en.getKey(), toLongSafe(en.getValue(), "loop-fire:" + en.getKey()));
        }
        Map<AEKey, Long> injected = new HashMap<>();
        // Cap the simulation so pathological huge loops never hang; the reference
        // catalyst loops are tiny, and beyond the cap we just skip the override.
        long totalFires = 0;
        for (Long v : remaining.values()) totalFires += v;
        final long FIRE_CAP = 500_000L;
        long guard = 0;
        while (!remaining.isEmpty() && totalFires <= FIRE_CAP && guard++ < 2 * FIRE_CAP + 100) {
            AEKey fireable = null;
            for (var en : remaining.entrySet()) {
                if (en.getValue() <= 0) continue;
                LoopPattern p = pats.get(en.getKey());
                boolean ok = true;
                for (var e : p.inputs.entrySet()) {
                    if (available.getOrDefault(e.getKey(), 0L) < e.getValue()) { ok = false; break; }
                }
                if (ok) { fireable = en.getKey(); break; }
            }
            if (fireable != null) {
                LoopPattern p = pats.get(fireable);
                remaining.merge(fireable, -1L, Long::sum);
                totalFires--;
                for (var e : p.inputs.entrySet())
                    available.merge(e.getKey(), -e.getValue(), Long::sum);
                for (var e : p.outputs.entrySet())
                    available.merge(e.getKey(), e.getValue(), Long::sum);
                continue;
            }
            // Deadlock: pick the blocked pattern with the minimum input deficit; on ties,
            // prefer one whose deficit is on PRIMARY outputs (the catalyst seed is a real
            // craftable item, not a byproduct — matches the reference's seed choice).
            AEKey best = null;
            long bestDeficit = Long.MAX_VALUE;
            long bestPrimary = -1;
            for (var en : remaining.entrySet()) {
                if (en.getValue() <= 0) continue;
                LoopPattern p = pats.get(en.getKey());
                long deficit = 0;
                long primaryScore = 0;
                for (var e : p.inputs.entrySet()) {
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
            for (var e : bp.inputs.entrySet()) {
                long gap = Math.max(0L, e.getValue() - available.getOrDefault(e.getKey(), 0L));
                if (gap > 0) {
                    injected.merge(e.getKey(), gap, Long::sum);
                    available.merge(e.getKey(), gap, Long::sum);
                    injectedAny = true;
                }
            }
            if (!injectedAny) break;
        }
        if (totalFires > FIRE_CAP) return Map.of(); // too large to simulate — skip override

        // 4) Only report working capital for LOOP items (byproduct-fed cycle).
        Map<AEKey, Long> result = new HashMap<>();
        for (AEKey x : loopItems) {
            result.put(x, injected.getOrDefault(x, 0L));
        }
        return result;
    }

    /** Directed pure-conversion edge {@code from → to} exchanging {@code in} of from for {@code out} of to. */
    private record ConvEdge(AEKey to, long in, long out) {
    }

    /**
     * (v1.10.3) Pure-conversion-ring feasibility guard. A byproduct-free SCC where every
     * pattern exchanges one internal item for another (e.g. {@code 9B→A, 1A→9B, 9C→B,
     * 1B→9C}) is value-conserving: it can convert STOCKED items but cannot create them
     * from nothing. The capture records empty {@code used} for unstocked ring items, so
     * the aggregation emits ring outputs for free and reports a conversion ring FEASIBLE
     * with no seed — a dangerous false positive (the plan "completes" but the actual craft
     * is stuck, the video bug class). This computes each ring's exact exchange value
     * (BigInteger fractions) and compares the stocked ring value against the external
     * demand value; on shortfall it reports the deficit on the smallest-value
     * externally-demanded ring key. It only ever ADDS missing (never removes), so DAGs,
     * byproduct-fed feedback loops and seeded (value-sufficient) rings are unaffected.
     *
     * <p>(v1.15.x GTL 1:1) The return shape also carries the set of ring members that
     * are value-sufficient — the aggregation can use it to STRIP the residual missing
     * written by the capture-phase CYCLE/CYCLE-CUT guards. Without this, a 1:1 pure
     * conversion ring (e.g. iron_dust ↔ iron_ingot with stocked dust) would still
     * report the ring's external demand as missing, because the capture already
     * MODULATE'd available stock and recorded the shortfall before value sufficiency
     * was even computable.
     */
    private record RingResult(java.util.Set<AEKey> feasible, java.util.Map<AEKey, Long> missing) {}

    private RingResult computeConversionRingMissingEx(Map<AEKey, BigInteger> total,
            KeyCounter initialStock) {
        java.util.Set<AEKey> feasibleRings = new java.util.HashSet<>();
        java.util.Map<AEKey, Long> result = new java.util.HashMap<>();
        // 1) Per-craft recipe lines for EVERY pattern of every REACHABLE key — a key may have
        //    MULTIPLE pure-conversion patterns (e.g. B: 1A→9B AND 9C→1B), all contributing
        //    edges to the ring. The keys are collected from the recipe graph (NOT just `total`,
        //    which drops cyclic members like C when the aggregation cuts the back-edge), so the
        //    FULL ring A↔B↔C is visible. The resolver returns all patterns when available.
        java.util.Set<AEKey> reachableKeys = new java.util.HashSet<>();
        collectPlanKeys(outputKey, reachableKeys, new java.util.HashSet<>());
        Map<AEKey, java.util.List<LoopPattern>> recipesByKey = new HashMap<>();
        for (AEKey key : reachableKeys) {
            if (key == null) continue;
            java.util.List<IPatternDetails> patterns = java.util.List.of();
            if (allPatternsResolver != null) {
                java.util.List<IPatternDetails> l = allPatternsResolver.apply(key);
                if (l != null) patterns = l;
            }
                if (patterns.isEmpty()) {
                    IPatternDetails chosen = patternResolver != null ? patternResolver.apply(key) : null;
                    if (chosen != null) patterns = java.util.List.of(chosen);
                }
            for (IPatternDetails details : patterns) {
                if (details == null) continue;
                Map<AEKey, Long> in = new HashMap<>();
                if (details.getInputs() != null) {
                    for (IPatternDetails.IInput entry : details.getInputs()) {
                        var possible = entry.getPossibleInputs();
                        if (possible == null || possible.length == 0 || possible[0] == null
                                || possible[0].what() == null) continue;
                        AEKey ik = possible[0].what();
                        AEKey rem = entry.getRemainingKey(ik);
                        if (rem != null && rem.equals(ik)) continue; // returned seed, not consumed
                        long amt = entry.getMultiplier() * Math.max(1L, possible[0].amount());
                        in.merge(ik, amt, Long::sum);
                    }
                }
                if (in.isEmpty()) continue;
                Map<AEKey, Long> out = new HashMap<>();
                java.util.Set<AEKey> bp = new java.util.HashSet<>();
                int idx = 0;
                if (details.getOutputs() != null) {
                    // getOutputs() is a GenericStack[] on 1.20.1, a List on 1.21.1 — enhanced-for handles both.
                    for (GenericStack gs : details.getOutputs()) {
                        if (gs == null || gs.what() == null) continue;
                        out.merge(gs.what(), (long) gs.amount(), Long::sum);
                        if (idx > 0) bp.add(gs.what());
                        idx++;
                    }
                }
                if (out.isEmpty()) continue;
                recipesByKey.computeIfAbsent(key, x -> new java.util.ArrayList<>())
                        .add(new LoopPattern(in, out, bp));
            }
        }
        if (recipesByKey.isEmpty()) return new RingResult(java.util.Set.of(), java.util.Map.of());

        // 2) Item graph i→j (a pattern consumes i, produces j) over ALL patterns, then SCCs.
        Map<AEKey, java.util.Set<AEKey>> graph = new HashMap<>();
        for (java.util.List<LoopPattern> recs : recipesByKey.values()) {
            for (LoopPattern p : recs) {
                for (AEKey i : p.inputs.keySet()) {
                    graph.computeIfAbsent(i, x -> new java.util.HashSet<>())
                            .addAll(p.outputs.keySet());
                }
            }
        }
        for (var scc : tarjanScc(graph)) {
            if (scc.size() <= 1) continue;
            // 3) Pure-conversion check: EVERY recipe of a member must exchange exactly one
            //    internal item for exactly one other internal item, with no byproducts.
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
            // (v1.15.x GTL DIAG) Log each non-trivial SCC and the pure-conversion verdict.
            // DEBUG level: the diag was useful while locking down the 1:1 ring fix, but
            // every nontrivial ring floods INFO every request and drowns the log.
            AE2VMAddon.LOGGER.debug("[AE2-VM-RING] SCC size={} members={} pure={}",
                    scc.size(), scc, pure);
            if (!pure) continue;
            // 4) Exchange values (BigInteger fractions) via edge BFS; skip if inconsistent.
            Map<AEKey, java.util.List<ConvEdge>> adj = new HashMap<>();
            for (AEKey member : scc) {
                for (LoopPattern p : recipesByKey.get(member)) {
                    AEKey from = p.inputs.keySet().iterator().next();
                    AEKey to = p.outputs.keySet().iterator().next();
                    long a = p.inputs.get(from);
                    long b = p.outputs.get(to);
                    adj.computeIfAbsent(from, x -> new java.util.ArrayList<>())
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
                for (ConvEdge e : adj.getOrDefault(cur, java.util.List.of())) {
                    // value(to) = value(cur) × in / out
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
                        consistent = false; // inconsistent rates → not a simple pure ring
                        break;
                    }
                }
            }
            if (!consistent) continue;
            // 5) External demand on the ring from non-ring consumers (+ root request if a ring key).
            Map<AEKey, BigInteger> demand = new HashMap<>();
            for (AEKey k : total.keySet()) {
                if (scc.contains(k)) continue;
                java.util.List<LoopPattern> recs = recipesByKey.get(k);
                if (recs == null) continue;
                BigInteger t = total.get(k);
                if (t == null || t.signum() <= 0) continue;
                for (LoopPattern p : recs) {
                    for (var e : p.inputs.entrySet()) {
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
            // 6) Ring-value comparison (exact fractions): stockValue vs demandValue.
            BigInteger dNum = BigInteger.ZERO;
            BigInteger dDen = BigInteger.ONE;
            for (var e : demand.entrySet()) {
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
            // stockValue < demandValue  ⇔  sNum/sDen < dNum/dDen  ⇔  sNum×dDen < dNum×sDen
            if (sNum.multiply(dDen).compareTo(dNum.multiply(sDen)) >= 0) {
                // (v1.15.x GTL 1:1) Value-sufficient ring: any capture-phase CYCLE/CYCLE-CUT
                // book-keeping on the ring's members is a false positive — the stocked items
                // can satisfy the external demand (via the ring's own exchange orientations)
                // without firing any sub-craft. Record the ring's members in the
                // feasibleRings set so the aggregation strips the residual missing on them
                // (the CYCLE guard already wrote "missing=<req>" on those keys before this
                // value comparison could run). The result map stays empty for this ring.
                for (AEKey member : scc) {
                    if (demand.containsKey(member)) {
                        feasibleRings.add(member);
                    }
                }
                continue; // ring is value-sufficient → feasible, no missing
            }
            // 7) Report the deficit on the smallest-value externally-demanded ring key.
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
                // amount = ceil(deficit / value(best)) = ceil(deficitNum×den / (deficitDen×num))
                BigInteger need = deficitNum.multiply(bestVal[1])
                        .add(deficitDen.multiply(bestVal[0]).subtract(BigInteger.ONE))
                        .divide(deficitDen.multiply(bestVal[0]));
                long amount = need.compareTo(BIG_MAX_LONG) > 0 ? Long.MAX_VALUE : need.longValue();
                if (amount > 0) result.put(best, Math.max(result.getOrDefault(best, 0L), amount));
            }
        }
        return new RingResult(feasibleRings, result);
    }

    /**
     * Iterative Tarjan strongly-connected-components over a small item graph. Returns
     * each SCC as a set of keys (singletons included).
     */
    private static java.util.List<java.util.Set<AEKey>> tarjanScc(Map<AEKey, java.util.Set<AEKey>> graph) {
        java.util.List<java.util.Set<AEKey>> sccs = new java.util.ArrayList<>();
        if (graph.isEmpty()) return sccs;
        Map<AEKey, Integer> index = new HashMap<>();
        Map<AEKey, Integer> low = new HashMap<>();
        Deque<AEKey> stack = new ArrayDeque<>();
        java.util.Set<AEKey> onStack = new java.util.HashSet<>();
        int[] counter = {0};
        // Iterative DFS with an explicit frame: [node, iteratorIndex].
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
                if (it.hasNext()) continue; // shouldn't happen
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
        ensureRealStockSnapshot();
        return realStockCache.get(key);
    }

    /** Lazily snapshot the live network inventory (reset every execute()). */
    private void ensureRealStockSnapshot() {
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
    }

    /**
     * (v1.10.x) Effective fuzzy group for {@code key}. Processing recipes (处理配方)
     * default to FUZZY matching: their inputs are matched against the item's FULL fuzzy
     * family in the real network (same item, any NBT/damage — {@code FuzzyMode.IGNORE_ALL}),
     * so a stored variant satisfies the slot even though AE2 encodes the processing input
     * as a single exact variant. Non-processing keys fall back to the compile-time
     * substitution group (or the exact key alone).
     */
    private java.util.Set<AEKey> fuzzyFamilyOf(AEKey key) {
        java.util.Set<AEKey> group = PatternCompiler.getFuzzyGroup(key);
        if (!PatternCompiler.isProcessingInput(key)) {
            return group;
        }
        ensureRealStockSnapshot();
        java.util.Set<AEKey> family = new java.util.HashSet<>(group);
        if (realStockCache != null) {
            for (var e : realStockCache.findFuzzy(key, appeng.api.config.FuzzyMode.IGNORE_ALL)) {
                family.add(e.getKey());
            }
        }
        return family;
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
        GenericStack primary;
        try {
            primary = pattern.getPrimaryOutput();
        } catch (RuntimeException e) {
            // (v1.12.x GTL DEFENSIVE) A pattern with no usable primary output (empty
            // getOutputs() — possible with buggy/partial modpack recipes) must not NPE
            // here: treat it as NOT a self-loop; the normal missing path handles it.
            return false;
        }
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
        // (v1.10.3 RECURSION) A self-adjacent pattern's own key is a one-time seed, not a
        // per-craft consumption: its self-production offsets its self-consumption (amplifier
        // A+B→2A, essence A+B→A+C). The seed amount was verified stocked by correctRecursion;
        // collapse the used demand to `in` so the loop can prime without demanding in×crafts
        // from stock (which, combined with the emitted self-output, masked the real missing).
        Map<AEKey, long[]> self = selfAdjacentKeys != null ? selfAdjacentKeys.get(k) : null;
        if (self != null && !self.isEmpty()) {
            for (var se : self.entrySet()) {
                long seed = se.getValue()[0];
                long out = se.getValue()[1];
                long net = out - seed;
                if (seed > 0) {
                    // (v1.10.x SEED-KEEP) Self key is a one-time seed: prime the loop with
                    // `seed` (= in) from stock, but its own production re-seeds the loop, so
                    // it must NOT be counted as full output. Only the NET growth beyond the
                    // RETAINED seed leaves the loop as produced output (seed + net × t):
                    //   - essence (A+B→A+C, net=0): emitted = seed → the network keeps
                    //     exactly the seed (1 A stays stocked, NOT inflated to n) — the
                    //     "最后保留一个种子不被消耗" requirement.
                    //   - amplifier (A+B→2A, net>0): emitted = seed + net×t → exactly meets
                    //     the request (n A), no over-production (was 2t = 2n−2).
                    scaled.used.put(se.getKey(), BigInteger.valueOf(seed));
                    if (net >= 0) {
                        BigInteger selfEmitted = BigInteger.valueOf(seed)
                                .add(BigInteger.valueOf(net).multiply(t));
                        scaled.emitted.put(se.getKey(), selfEmitted.max(BigInteger.valueOf(seed)));
                    }
                }
            }
        }
        subtractStockFromNetwork(scaled);
        applyBundleDirect(scaled);
        // (v1.10.x DURABILITY) Finite-use (durability) tool demand: `amount` units are
        // consumed per firing and one full unit survives `uses` firings, so a batch of
        // `t` firings needs amount × ceil(t/uses) tools — NOT amount × t (consumed) and
        // NOT one seed (catalyst). Consume from stock, shortfall → missing (the reference
        // durability/finite-use-chain closed form "成环差分").
        for (var d : arr[0].durability.entrySet()) {
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
     * (v1.11.x STALE-MISSING RECHECK) True if the cached bundle recorded a missing
     * leaf that NOW has a pattern. A bundle captured while an intermediate key had no
     * pattern stores it in {@code missing} (the CALL_BY_KEY sub=null branch). When the
     * player later ADDS that pattern, the bundleCache should be dropped — but if
     * {@code PatternProviderLogicMixin.onUpdatePatterns → bumpPatternVersion} never
     * fires (mixin not applied, or the pattern was added via an entry point that does
     * not route through {@code PatternProviderLogic.updatePatterns}), the stale bundle
     * is reused forever and the intermediate stays "missing" (the melodic_item_conduit
     * → pulsating_powder report: works when crafted alone, missing in the chain).
     * This recheck re-resolves every missing key against the live resolver: if any now
     * has a pattern, the bundle is stale → callers re-capture it so the new pattern is
     * used instead of the stale missing. No-op when nothing changed (cost is O(missing),
     * resolver-cache hit for keys whose pattern was already known).
     *
     * <p>(v1.11.8 fix) The missing leaf can live in a DEEP sub-bundle, NOT the reused
     * bundle itself: e.g. melodic_item_conduit → melodic_alloy_ingot →
     * crystalline_pink_slime_ingot → crystalline_alloy_ingot → pulsating_powder.
     * Only crystalline_alloy_ingot's bundle records pulsating_powder in its DIRECT
     * missing; melodic_alloy_ingot's bundle has empty missing and references
     * crystalline_alloy_ingot via itemNeeds. On reuse of melodic_alloy_ingot's bundle
     * the direct check sees empty missing and skips — the deep pulsating_powder is
     * never re-resolved. So the check must walk the whole itemNeeds subtree and test
     * every sub-bundle's missing (game log: 01:09:59 reused melodic_alloy_ingot
     * bundle → missing={pulsating_powder=9} persisted even after the pattern existed;
     * pulsating_powder alone crafted fine at 01:10:09).
     */
    private boolean staleMissingRecheck(AEKey tk, Bundle b0) {
        // (v1.11.8 PERF) Memoized per-execute: same bundle reference → O(1) hit.
        // The bundle reference (not just the key) is part of the memo identity, so a
        // re-captured bundle (new reference) is never served a stale verdict.
        Object[] memoEntry = staleMemo.get(tk);
        if (memoEntry != null && memoEntry[0] == b0) {
            return (Boolean) memoEntry[1];
        }
        boolean r = staleMissingRecheckSubtree(tk, b0, new java.util.HashSet<>());
        staleMemo.put(tk, new Object[]{b0, r});
        return r;
    }

    private boolean staleMissingRecheckSubtree(AEKey key, Bundle b0, java.util.Set<AEKey> visited) {
        if (b0 == null) return false;
        // Memo check for sub-bundles too: nested reuses share the per-execute memo.
        Object[] memoEntry = staleMemo.get(key);
        if (memoEntry != null && memoEntry[0] == b0) {
            return (Boolean) memoEntry[1];
        }
        boolean result = false;
        // (v1.11.9 PERF) Single pass over the bundle's DIRECT sub-craft needs (itemNeeds)
        // that does BOTH the reverse-stale check AND the recursive subtree walk, so each
        // reused bundle's itemNeeds is iterated ONCE (not once per check). The missing-map
        // check below is separate (missing keys are leaves, not itemNeeds).
        // (v1.11.9 REVERSE STALE: PATTERN REMOVED) If a DIRECT sub-craft that was
        // craftable when this bundle was captured (it appears in itemNeeds) NOW has NO
        // pattern, the bundle is stale in the OPPOSITE direction: the intermediate can no
        // longer be crafted, but the cached bundle still claims it can → the final product
        // reports "feasible / can order" while the actual craft stalls (the "最终产物可以
        // 下单，但中间产物不可以下单" report — player removed the intermediate's pattern,
        // yet the final item still shows as orderable). Re-capture so the removed pattern
        // is reflected as a missing intermediate instead of a false-feasible final product.
        if (!b0.itemNeeds.isEmpty()) {
            for (var e : b0.itemNeeds.entrySet()) {
                AEKey sub = e.getKey();
                if (sub.equals(outputKey)) continue;
                // Reverse stale: a previously-craftable intermediate lost its pattern.
                if (patternResolver != null) {
                    IPatternDetails p = patternResolver.apply(sub);
                    if (p == null) {
                        AEKey ck = sub.dropSecondary();
                        if (!ck.equals(sub)) p = patternResolver.apply(ck);
                    }
                    if (p == null) {
                        for (AEKey member : fuzzyFamilyOf(sub)) {
                            if (member.equals(sub)) continue;
                            if (patternResolver.apply(member) != null) { p = patternResolver.apply(member); break; }
                        }
                    }
                    if (p == null) { result = true; break; } // a craftable intermediate lost its pattern → stale
                }
                // Recursive subtree walk (deep missing leaf) — same itemNeeds iteration.
                if (!result && visited.add(sub)) {
                    Bundle[] subArr = bundleCache.get(sub);
                    if (subArr != null && subArr[0] != null) {
                        if (staleMissingRecheckSubtree(sub, subArr[0], visited)) { result = true; break; }
                    }
                }
            }
        }
        // (v1.11.8) Forward stale: a previously-missing leaf now has a pattern. This walks
        // the bundle's OWN missing map (leaves are not in itemNeeds, so a separate loop).
        if (!result && !b0.missing.isEmpty()) {
            for (var e : b0.missing.entrySet()) {
                AEKey k = e.getKey();
                if (k.equals(outputKey)) continue;
                IPatternDetails p = patternResolver != null ? patternResolver.apply(k) : null;
                if (p == null) {
                    AEKey ck = k.dropSecondary();
                    if (!ck.equals(k)) p = patternResolver.apply(ck);
                }
                if (p == null && patternResolver != null) {
                    // (v1.11.x fuzzy-family) Same fallback as CALL_BY_KEY: a sibling variant
                    // of a registered fuzzy group may be craftable.
                    for (AEKey member : fuzzyFamilyOf(k)) {
                        if (member.equals(k)) continue;
                        if (patternResolver.apply(member) != null) { p = patternResolver.apply(member); break; }
                    }
                }
                if (p != null) { result = true; break; } // a previously-missing key now has a pattern → stale
            }
        }
        staleMemo.put(key, new Object[]{b0, result});
        return result;
    }

    /**
     * (v1.12.x GTL PATTERN-IDENTITY) True if the cached bundle was captured against a
     * DIFFERENT pattern than the one the resolver now returns for {@code tk} — i.e. the
     * player swapped / modified the intermediate's pattern (new IPatternDetails with
     * different content) without a version bump (GTL sleeping-ticker / refresh-window
     * edge). Reuse would key the plan on an OLD pattern the providers no longer expose
     * (false positive → CPU stall) or demand the OLD recipe's inputs (false negative),
     * so such bundles must be re-captured.
     */
    private boolean bundlePatternChanged(Bundle b0, AEKey tk) {
        if (b0 == null) return false;
        IPatternDetails current = patternResolver != null ? patternResolver.apply(tk) : null;
        return !patternsEquivalent(b0.capturedFor, current);
    }

    /** Content-level pattern equality (identity alone is unreliable: providers may hand
     *  out fresh instances for the same encoded stack). Compares outputs + inputs. */
    private static boolean patternsEquivalent(IPatternDetails a, IPatternDetails b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        GenericStack[] ao = a.getOutputs();
        GenericStack[] bo = b.getOutputs();
        if (ao == null || bo == null || ao.length != bo.length) return false;
        for (int i = 0; i < ao.length; i++) {
            if (!stacksEqual(ao[i], bo[i])) return false;
        }
        IPatternDetails.IInput[] ai = a.getInputs();
        IPatternDetails.IInput[] bi = b.getInputs();
        if (ai == null || bi == null || ai.length != bi.length) return false;
        for (int i = 0; i < ai.length; i++) {
            if (ai[i].getMultiplier() != bi[i].getMultiplier()) return false;
            GenericStack[] ap = ai[i].getPossibleInputs();
            GenericStack[] bp = bi[i].getPossibleInputs();
            if (ap == null || bp == null || ap.length != bp.length) return false;
            for (int j = 0; j < ap.length; j++) {
                if (!stacksEqual(ap[j], bp[j])) return false;
            }
        }
        return true;
    }

    private static boolean stacksEqual(GenericStack a, GenericStack b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.amount() == b.amount() && a.what() != null && a.what().equals(b.what());
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
        // Reverse catalyst seeds (undo the one-time seed demand recorded by CATALYST_SEED).
        // Like `missing`, the seed was never extracted during capture — it only demands a
        // fixed amount from stock at apply time, so there is no sim state to undo here.
        for (var e : b.seeds.entrySet()) {
            long val = toLongSafe(e.getValue(), "seed-revert:" + e.getKey());
            catalystSeedItems.add(e.getKey(), -val);
            if (catalystSeedItems.get(e.getKey()) == 0) catalystSeedItems.remove(e.getKey());
        }
        // Reverse durability rates (the DURABILITY_TOOL opcode recorded them during capture;
        // the bundle holds a copy, so drop them from the live field for sibling calls).
        for (var e : b.durability.entrySet()) {
            durabilityItems.remove(e.getKey());
        }
        // NOTE: needs are NOT reverted here — sub-tree effects are never applied during
        // capture (capture context), so there is nothing to undo for them.
    }
    
    /**
     * (v1.15.x DEFENSIVE) Simulation bytes are captured via the mixin-injected
     * {@code CraftingSimulationStateAccessor}. In the offline bench environment the
     * mixin is not applied to AE2's ChildCraftingSimulationState, so the cast used
     * to crash every full-chain request with a ClassCastException — treat a missing
     * accessor as 0 bytes (bytes are a plan statistic only; they never affect the
     * craft correctness).
     */
    private static long bytesOfSimulation(CraftingSimulationState s) {
        if (s instanceof com.ae2vm.addon.mixin.CraftingSimulationStateAccessor a) {
            return (long) a.getBytes();
        }
        return 0L;
    }

    private Bundle captureDelta() {
        Bundle b = new Bundle();
        b.bytes = BigInteger.valueOf(bytesOfSimulation(simulation));
        // Snapshot key sets then read values serially — single-threaded: no writers
        // during captureDelta (applyBundle/revertBundle run serially on the VM thread).
        if (!usedItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(usedItems.keySet()); for (AEKey k : ks) { long v = usedItems.get(k); if (v != 0) b.used.put(k, BigInteger.valueOf(v)); } }
        if (!emittedItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(emittedItems.keySet()); for (AEKey k : ks) { long v = emittedItems.get(k); if (v != 0) b.emitted.put(k, BigInteger.valueOf(v)); } }
        if (!missingItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(missingItems.keySet()); for (AEKey k : ks) { long v = missingItems.get(k); if (v != 0) b.missing.put(k, BigInteger.valueOf(v)); } }
        if (!simInternal.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(simInternal.keySet()); for (AEKey k : ks) { long v = simInternal.get(k); if (v != 0) b.internal.put(k, BigInteger.valueOf(v)); } }
        if (!catalystSeedItems.isEmpty()) { var ks = new java.util.ArrayList<AEKey>(catalystSeedItems.keySet()); for (AEKey k : ks) { long v = catalystSeedItems.get(k); if (v != 0) b.seeds.put(k, BigInteger.valueOf(v)); } }
        if (!durabilityItems.isEmpty()) { for (var en : durabilityItems.entrySet()) b.durability.put(en.getKey(), en.getValue()); }
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
        for (var e : after.seeds.entrySet()) {
            BigInteger bv = before.seeds.getOrDefault(e.getKey(), BigInteger.ZERO);
            BigInteger d = e.getValue().subtract(bv);
            if (d.signum() > 0) b.seeds.put(e.getKey(), d);
        }
        for (var e : after.durability.entrySet()) {
            if (!before.durability.containsKey(e.getKey())) b.durability.put(e.getKey(), e.getValue());
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
        subtractMap(target.seeds, o.seeds);
        for (var e : o.durability.entrySet()) target.durability.remove(e.getKey());
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

    /**
     * (v1.10.3) Supplies ALL patterns per output key for the pure-conversion-ring analysis
     * (see {@link #allPatternsResolver}). Optional — the benchmark harness sets it from the
     * reference graph; gameplay can leave it null for a best-effort ring view.
     */
    public void setAllPatternsResolver(Function<AEKey, java.util.List<IPatternDetails>> resolver) {
        this.allPatternsResolver = resolver;
    }
    /**
     * (v1.12.x GTL FIX) Clear the JIT bundleCache WITHOUT bumping the global pattern
     * version. The stale-missing retry loop in AE2VMCrafting must re-capture only its OWN
     * VM cache; bumpPatternVersion() is global and clears every VM on the grid, causing
     * concurrent recalculations to observe the transient pattern-removal window inside
     * CraftingService.refreshNodeCraftingProvider (removeProvider + addProvider) and
     * report \pattern not found\ (synthesis deadlock).
     */
    public void clearBundleCache() {
        synchronized (this) {
            bundleCache.clear();
        }
    }

    /**
     * (v1.12.x GTL FAST PATH v2) Conservative warm-path short-cut. Returns true ONLY when
     * the ENTIRE reachable bundle DAG (root + sub-bundles) is cached, each bundle's
     * captured pattern is content-identical to what the resolver returns NOW (so a player
     * swap/modify of any pattern forces the slow re-capture path), the graph is plain
     * (no missing captures, no catalyst seeds, no durability tools, no self-adjacent /
     * feedback-loop patterns — those need executeStartStock), and the pattern version is
     * unchanged. On success the caller skips bytecode execution and jumps to
     * {@link #buildPlan}; applyBundleDirect re-derives used/missing against the fresh
     * simulation so stock changes are still honoured.
     */
    private CraftingPlan tryFastPath(CraftingBytecode requestBytecode) {
        // (v1.12.x GTL FAST PATH v3 — MEMOIZED PLAN) Correct-by-construction fast path:
        // reuse the exact plan the previous SLOW execution produced, guarded by:
        //   1) same outputKey + rootCraftTimes + pattern version (cache key);
        //   2) the bundle DAG is still cached and content-identical to the resolver
        //      (deep identity walk — catches player pattern swaps/modifications);
        //   3) every key in the cached plan's usedItems is a PURE LEAF (no pattern →
        //      the slow path's stock-aware sub-craft cannot change craft counts with
        //      stock, so the cached counts stay valid);
        //   4) the leaf stock guard: each used leaf still has >= needed stock NOW
        //      (fall back to slow path when stock drained → it re-derives missing).
        if (PatternCompiler.patternVersion() != this.lastPatternVersion) return null;
        if (requestBytecode.getCodeLength() == 0) return null;
        long totalRequested = requestBytecode.getOutputAmountPerCraft();
        long perCraft = 1;
        IPatternDetails[] pool = requestBytecode.getPatternPool();
        if (pool != null && pool.length > 0) {
            GenericStack primary = pool[0].getPrimaryOutput();
            if (primary != null && primary.amount() > 0) perCraft = primary.amount();
        }
        long craftTimes = ceilDiv(totalRequested, perCraft);
        this.rootCraftTimes = craftTimes;
        // 1) cache key
        if (fastPlanKey == null || !fastPlanKey.equals(outputKey)
                || fastPlanRootCraftTimes != craftTimes) return null;
        if (fastPlanMissing != null && !fastPlanMissing.isEmpty()) return null; // stale missing
        // 2) deep identity walk of the cached DAG (HashSet — O(1), hot path;
        //    determinism for benchmarks comes from the red-black TreeMap cache store)
        java.util.Set<AEKey> visited = new java.util.HashSet<>();
        java.util.ArrayDeque<AEKey> dfs = new java.util.ArrayDeque<>();
        dfs.push(outputKey);
        visited.add(outputKey);
        while (!dfs.isEmpty()) {
            AEKey k = dfs.pop();
            Bundle[] arr = bundleCache.get(k);
            if (arr == null || arr[0] == null) return null;
            Bundle b = arr[0];
            IPatternDetails current = patternResolver != null ? patternResolver.apply(k) : null;
            if (!patternsEquivalent(b.capturedFor, current)) return null;
            for (var e : b.itemNeeds.entrySet()) {
                AEKey sub = e.getKey();
                if (sub.equals(k)) continue;
                if (visited.add(sub)) dfs.push(sub);
            }
        }
        // 3) + 4) leaf-only + stock guard on cached usedItems
        // (v1.15.x PERF) Self-emit byproduct ring fast-path: a used key that ALSO
        // appears in the cached emittedItems (its pattern produces it as a
        // byproduct, closing a ring) is self-supplied — no real network stock is
        // read for it, the run's craft count is fixed by the request alone, so the
        // cached plan stays correct on every re-execute as long as the request
        // size is unchanged. The old "craftable → refuse" guard was a too-coarse
        // leaf-only check that threw away every byproduct-closure ring
        // (warmBillionSeededRing median ~180μs → <10μs after this single rule).
        // Also skip the leaf stock guard for self-emit keys: a 10^9-unit run that
        // scans 10^9-element inventory on every warm hit dominated the timing
        // (260μs median before this guard was conditional).
        for (var u : fastPlanUsed) {
            AEKey uk = u.getKey();
            if (patternResolver != null && patternResolver.apply(uk) != null) {
                long emitted = fastPlanEmitted.get(uk);
                if (emitted < u.getLongValue()) return null; // not self-supplied → stock-sensitive
                continue; // self-emit byproduct ring: cached counts are exact, no stock scan
            }
            long needed = u.getLongValue();
            long avail = simulation.extract(uk, needed, Actionable.SIMULATE);
            if (avail < needed) return null; // stock drained → slow path re-derives missing
        }
        // Cache hit — rebuild the plan from cached components
        long deliver = totalRequested <= Long.MAX_VALUE ? totalRequested : Long.MAX_VALUE;
        this.batchRemainder = totalRequested > Long.MAX_VALUE
            ? java.math.BigInteger.valueOf(totalRequested).subtract(java.math.BigInteger.valueOf(Long.MAX_VALUE))
            : null;
        KeyCounter used = new KeyCounter();
        for (var e : fastPlanUsed) used.add(e.getKey(), e.getLongValue());
        KeyCounter missing = new KeyCounter();
        for (var e : fastPlanMissing) missing.add(e.getKey(), e.getLongValue());
        KeyCounter emitted = new KeyCounter();
        for (var e : fastPlanEmitted) emitted.add(e.getKey(), e.getLongValue());
        java.util.Map<IPatternDetails, Long> patterns = new java.util.HashMap<>(fastPlanPatterns);
        CraftingPlan plan = new CraftingPlan(
            new GenericStack(outputKey, deliver), fastPlanBytes, false, false,
            used, emitted, missing, patterns);
        this.usedItems = used;
        this.missingItems = missing;
        this.emittedItems = emitted;
        this.patternTimes = patterns;
        this.aggregated = true;
        return plan;
    }

    /**
     * (v1.12.x GTL FAST PATH) Store a FEASIBLE plan produced by the slow path into the
     * memoized fast-path cache (only feasible plans — missing plans always re-run slow
     * so stale missing can never be served).
     */
    private void storeFastPlanCache(ICraftingPlan plan) {
        if (plan == null || !plan.missingItems().isEmpty()) return;
        this.fastPlanKey = this.outputKey;
        this.fastPlanRootCraftTimes = this.rootCraftTimes;
        this.fastPlanBytes = plan.bytes();
        this.fastPlanUsed = new KeyCounter();
        for (var e : plan.usedItems()) fastPlanUsed.add(e.getKey(), e.getLongValue());
        this.fastPlanMissing = new KeyCounter();
        for (var e : plan.missingItems()) fastPlanMissing.add(e.getKey(), e.getLongValue());
        this.fastPlanEmitted = new KeyCounter();
        for (var e : plan.emittedItems()) fastPlanEmitted.add(e.getKey(), e.getLongValue());
        // Red-black TreeMap (deterministic iteration order for repeatable benchmarks).
        // Safe comparator: identity first, then instance toString (unique per object in
        // practice; ordering is stable within a JVM run).
        this.fastPlanPatterns = new java.util.TreeMap<>(
            (a, b) -> a == b ? 0 : a.toString().compareTo(b.toString()));
        this.fastPlanPatterns.putAll(plan.patternTimes());
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
        // (v1.12.x GTL FAST PATH) Try the memoized warm path FIRST, BEFORE any of the
        // per-request state allocations (512-slot stack, 9 KeyCounters, ArrayDeques).
        // Warm hits then cost only: DAG identity walk + leaf stock guard + plan copy
        // (measured: 2.7μs for 2-level, ~5μs for 24-level Fibonacci, 3.4μs for 10^9).
        this.simulation = simulation;
        this.outputKey = requestBytecode.getOutput();
        this.requestAmount = requestedAmount;
        this.extractIsClaim = false;
        this.aggregated = false;
        CraftingPlan fastPlan = tryFastPath(requestBytecode);
        if (fastPlan != null) {
            return fastPlan;
        }
        // ---- slow path: per-request state reset ----
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
        this.simulation = simulation; // redundant, kept for clarity
        this.nodeCount = 1;
        this.rootCraftTimes = 0;
        this.batchRemainder = null;
        this.aggregated = false; // redundant, kept for clarity
        this.outputKey = requestBytecode.getOutput(); // redundant, kept for clarity
        this.extractIsClaim = false;
        // (v1.10.3 RECURSION) Keep the root request size for the aggregation's amplifier
        // craft-count correction (the recursion closed form needs the requested amount).
        this.requestAmount = requestedAmount;
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
        // (v1.11.8 PERF) staleMemo must be cleared every execute(): patterns/stock may
        // have changed since the last request, so a memoized "not stale" verdict from an
        // earlier request could hide a newly-added pattern (the exact bug this recheck
        // exists to catch). Per-execute clearing keeps the check correct; within ONE
        // execute the memo makes repeated reuses O(1).
        staleMemo.clear(); // (v1.11.8 PERF) cleared per execute
        recapturedInThisExecute.clear();
        // set changed since the last request (PatternProviderLogic.updatePatterns →
        // PatternCompiler.bumpPatternVersion). A bundle captured while an intermediate key
        // had no pattern records it as a missing leaf; with a stale bundle the new pattern
        // is never re-resolved and the intermediate stays "missing" until a restart. The
        // bundleCache is a JIT memo only — dropping it costs one re-capture, never
        // correctness.
        long pv = PatternCompiler.patternVersion();
        if (pv != this.lastPatternVersion) {
            AE2VMAddon.LOGGER.info("[AE2-VM] execute() clearing bundleCache: lastVersion={}, newVersion={}", this.lastPatternVersion, pv);
            bundleCache.clear();
            // (v1.12.x GTL SYNC) Also clear the persistent resolver cache — stale positive
            // entries (IPatternDetails cached from a previous request for a now-removed
            // pattern) would otherwise make the VM report a false-positive feasible plan.
            resolverCache.clear();
            this.lastPatternVersion = pv;
        }
        // (v1.9.11) Cache hygiene no longer DROPS bundles whose missing is non-empty.
        // Their `missing` is a capture-time snapshot; applyBundleDirect now re-verifies
        // it against the live sandbox (extract if stock now exists, else missing), so a
        // stale snapshot can no longer leak into a new plan. Dropping was catastrophic
        // for the JIT: under empty stock every low-level bundle had non-empty missing,
        // so the drop cascaded to the entire chain (v1.9.10) and every request fully
        // re-captured. Keeping every bundle cached preserves the structure (sub-bundles
        // stay present → subBundlesComplete passes → high reuse) while missing is
        // always recomputed live.
        // (v1.10.x CATALYST) Snapshot the simulation's INITIAL network stock here — the
        // simulation is fresh at execute() start, but the capture phase does NOT restore
        // consumed leaf stock into the sandbox, so a later snapshot would read 0 for a
        // stocked leaf (A in the raw/lossy catalyst loops). Walking the request + all
        // sub-pattern recipe graph collects every key the plan will touch.
        this.executeStartStock = snapshotExecuteStartStock(requestBytecode);
        
        long vmStartNs = System.nanoTime(); // total calc time (capture + aggregation + buildPlan)
        
        // (v1.11.x DEBUG LOG) Log the start of a crafting execution: output key
        // and requested amount. The END log shows the accurate total input count.
        AE2VMAddon.LOGGER.info("[AE2-VM] === CRAFT START === outputKey={}, requestedAmount={}",
                outputKey, requestedAmount);
        
        loadBytecode(requestBytecode);
        
        // Opcodes (hardcoded to match Opcode.java): 0=PUSH_ITEM,1=PUSH_LONG,2=ADD,3=SUB,4=MUL,5=DIV_ROUNDUP,
        // 6=EXTRACT,7=RECORD_OUTPUT,8=RECORD_INGREDIENT,9=RECORD_MISSING,
        // 10=DUP,11=POP,12=SWAP,13=RECORD_PATTERN,14=CALL,15=RETURN,16=CALL_BY_KEY,17=INSERT_OUTPUT,
        // 20=FUZZY_SLOT,255=HALT
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
                case 5 -> { // DIV_ROUNDUP — saturating ceil-div (no overflow at Long.MAX_VALUE)
                    long pc2=popL(), rq=popL();
                    if (pc2 <= 0 || rq <= 0) { pushL(0); break; }
                    pushL(rq / pc2 + (rq % pc2 == 0L ? 0L : 1L));
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
                    // (v1.10.x) PROCESSING-RECIPE DEFAULT FUZZY: processing recipe inputs
                    // are matched against the item's full fuzzy family (any NBT variant).
                    // If the exact key is short, consume the actual variant present in the
                    // network and record IT in usedItems, so the CPU can extract it at
                    // submit time (GTL greenhouse fake-craft / MA essence: block stored
                    // under a different NBT than the pattern's encoded input).
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
                    extractIsClaim = !f.cycleCut(); // (v1.14.x) cut frame produced nothing — its claim EXTRACT must record stock used
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
                            // (v1.10.x video fix) Record the FUZZY (replacement-enabled) portion
                            // of the direct sub-call needs — only this portion may be satisfied
                            // by substitute-variant stock in the stock-aware aggregation. Exact
                            // slots can only use their primary key (see CallFrame.fuzzySubCalls).
                            if (f.fuzzySubCalls != null && !f.fuzzySubCalls.isEmpty()) {
                                for (var sc : f.fuzzySubCalls.entrySet()) {
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
                            // (v1.12.x GTL PATTERN-IDENTITY) Stamp the pattern this bundle was
                            // captured for; reuse compares it against the CURRENT resolver result.
                            delta.capturedFor = (patternResolver != null && f.resolvingKey != null)
                                    ? patternResolver.apply(f.resolvingKey) : null;
                            resolvingKeys.remove(f.resolvingKey);
                            boolean enclosingCapture = !callStack.isEmpty() && callStack.peek().bundleKey() != null;
                            if (callStack.isEmpty()) {
                                // Root request frame: its bundle (direct effects + direct needs)
                                // is stored for the final aggregation. Undo this 1-craft's direct
                                // effects on the simulation; applyAggregation() replays everything
                                // exactly once. Never rewind/apply here.
                                revertBundle(delta);
                                extractIsClaim = !f.cycleCut(); // (v1.14.x) cut frame → claim EXTRACT must record stock used
                            } else if (enclosingCapture) {
                                // Capture context: a parent is building its bundle. Undo this
                                // 1-craft's applied DIRECT effects; the parent references us via
                                // needs and will apply our bundle on replay.
                                revertBundle(delta);
                                extractIsClaim = !f.cycleCut(); // (v1.14.x) cut frame → claim EXTRACT must record stock used
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
                                extractIsClaim = !f.cycleCut(); // (v1.14.x) cut frame → claim EXTRACT must record stock used
                            }
                        } else {
                            resolvingKeys.remove(f.resolvingKey);
                            extractIsClaim = !f.cycleCut(); // (v1.14.x) cut frame → claim EXTRACT must record stock used
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
                    // (v1.10.x video fix) Consume the slot-fuzzy marker emitted by FUZZY_SLOT.
                    // A replacement-enabled slot may be satisfied by substitute variants; an
                    // EXACT slot (single possible input) can only use its primary key.
                    boolean slotFuzzy = currentSlotFuzzy;
                    currentSlotFuzzy = false;
                    // TEMP DIAG removed (v1.9.1): was logging every call to find the
                    // leaf-drop branch. No longer needed.
                    // AE2VMAddon.LOGGER.info("[AE2-VM DIAG] CALL {} req={} depth={} rkContains={}", tk, req,
                    //     callStack.size(), resolvingKeys.contains(tk));
                    IPatternDetails sub = patternResolver.apply(tk);
                    if (sub == null) {
                        AEKey ck = tk.dropSecondary();
                        if (!ck.equals(tk)) sub = patternResolver.apply(ck);
                    }
                    // (v1.11.x) CRAFTABLE FUZZY-FAMILY SUBSTITUTE — long, complex,
                    // multi-replacement chains ("长复杂多合成替换链"): if the exact /
                    // dropSecondary key has NO pattern but a SIBLING variant of the same
                    // item (registered fuzzy group / processing-recipe NBT family) IS
                    // craftable, craft that member to satisfy this slot. Without this the
                    // variant is treated as an un-craftable leaf and reported missing even
                    // though a pattern exists (the "有样板却提示缺少" false-missing: the
                    // parent demands X[B], only X[A] — same base, different variant — has a
                    // pattern, and the demand is silently dropped to missing). The demand is
                    // REMAPPED to the crafted member (sub-call, bundle, aggregation all name
                    // it); the parent's EXTRACT fuzzy-substitution chain consumes the crafted
                    // output. No-op when no family member is craftable (falls through to the
                    // normal missing check below).
                    if (sub == null) {
                        for (AEKey member : fuzzyFamilyOf(tk)) {
                            if (member.equals(tk)) continue;
                            IPatternDetails msub = patternResolver.apply(member);
                            if (msub != null) {
                                sub = msub;
                                tk = member; // the craft is for the member, not the demanded key
                                break;
                            }
                        }
                    }
                    if (sub == null) {
                        // (v1.11.x DIAG) Track when patterns aren't found
                        AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY {} req={} sub=null (pattern not found) → missing", tk, req);
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
                        // (v1.10.x) PROCESSING-RECIPE DEFAULT FUZZY: processing recipe
                        // inputs also count the item's FULL fuzzy family (any NBT variant)
                        // as available — a greenhouse block stored under a different NBT
                        // still satisfies the fake-craft slot (GTL greenhouse / MA essence).
                        // (v1.10.x video fix) Only a replacement-ENABLED (slotFuzzy) slot may
                        // be satisfied by DIFFERENT-ITEM substitute variants. An EXACT slot
                        // (single possible input) checks ONLY its primary key — plus, for a
                        // processing-recipe input, the SAME-ITEM NBT variants (extracted via
                        // findFuzzyTemplates at execution). The global fuzzy group (registered
                        // by an UNRELATED pattern that enables replacement) must NOT suppress
                        // the missing for an exact slot — otherwise the plan reports feasible
                        // but AE2's CPU execution can never extract the primary for that exact
                        // pattern → the craft stalls at zero progress.
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long availSim = simulation.extract(tk, req, Actionable.SIMULATE);
                        if (slotFuzzy) {
                            for (AEKey variant : fuzzyFamilyOf(tk)) {
                                if (variant.equals(tk)) continue;
                                availSim += simulation.extract(variant, req, Actionable.SIMULATE);
                            }
                        } else if (PatternCompiler.isProcessingInput(tk)) {
                            // Processing exact slot: same-item NBT variants are acceptable.
                            ensureRealStockSnapshot();
                            if (realStockCache != null) {
                                for (var fe : realStockCache.findFuzzy(tk,
                                        appeng.api.config.FuzzyMode.IGNORE_ALL)) {
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
                    // SELF-GROWTH LOOP CUT (v1.8.26): the resolved sub-pattern is an
                    // unseeded A->2A loop — firing it would duplicate items from nothing.
                    // Treat it exactly like a cycle: consume whatever stock exists and
                    // mark the shortfall missing, never dispatch the pattern.
                    if (isUnseededSelfLoop(sub)) {
                        simulation.addStackBytes(tk, 1, req); nodeCount++;
                        long gotx = simulation.extract(tk, req, captureAction());
                        if (gotx > 0) {
                            long internal = simInternal.get(tk);
                            long fromInternal = Math.min(gotx, internal);
                            if (fromInternal > 0) simInternal.add(tk, -fromInternal);
                            long fromNetwork = gotx - fromInternal;
                            if (!extractIsClaim && fromNetwork > 0 && !capturingBundle()) usedItems.add(tk, fromNetwork);
                        } else {
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    PatternCompiler.compileIfAbsent(networkKey, sub);
                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, sub);
                    if (sbc == null) { missingItems.add(tk, req); break; }
                    if (callStack.size() >= MAX_CALL_DEPTH) {
                        AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → MAX_CALL_DEPTH {} DROP", tk, req, callStack.size());
                        missingItems.add(tk, req); break;
                    }
                    if (circularCache.contains(tk)) {
                        // (v1.14.x SEEDED-RING) A previously-seen ring still consumes network
                        // stock for its demand — parallel 1-craft siblings legitimately hit the
                        // cached ring (width, not depth). Consume stock; only the shortfall
                        // beyond what the network holds is missing. Never drop the whole demand:
                        // that made seeded 3-hop rings report spurious missing (used=5 miss=1
                        // for seed 5 / need 3).
                        long gotc = simulation.extract(tk, req, captureAction());
                        if (gotc > 0) {
                            long internalc = simInternal.get(tk);
                            long fromInternalc = Math.min(gotc, internalc);
                            if (fromInternalc > 0) simInternal.add(tk, -fromInternalc);
                            long fromNetworkc = gotc - fromInternalc;
                            if (!extractIsClaim && fromNetworkc > 0 && !capturingBundle()) usedItems.add(tk, fromNetworkc);
                        } else if (!extractIsClaim) {
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    if (!resolvingKeys.add(tk)) {
                        // Cycle: the pattern needs its own output. Consume whatever the network
                        // actually holds instead of marking the whole request missing.
                        AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → CYCLE, consuming available stock", tk, req);
                        circularCache.add(tk);
                        // (v1.14.x DEFINITION-GRAPH) The capturing pattern's craft is cut only
                        // when the cyclic call target sits in a DEAD ring (definition-graph SCC
                        // analysis: unseeded + no external supplier). Seeded rings (dust<->ingot
                        // with stock), externally-fed rings and re-flow rings keep their member
                        // crafts — they are legitimate production. Dead-ring members are
                        // stock-only: the craft is cut and the shortfall becomes missing.
                        // (v1.14.x JIT-GRAPH) Ring seed test: the ring is legitimate production when
                        // either the cyclic target OR the capturing (enclosing) pattern's output key
                        // holds network stock — a seed lets the ring be entered and terminate once
                        // the stock is consumed. Unseeded rings are cut (stock-only) and their
                        // shortfall becomes missing. O(1) per cycle — no separate graph pass.
                        boolean ringSeeded = simulation.extract(tk, 1, Actionable.SIMULATE) > 0;
                        {
                            CallFrame cf0 = callStack.peek();
                            if (cf0 != null && cf0.bundleKey() != null && !cf0.bundleKey().equals(tk)) {
                                ringSeeded |= simulation.extract(cf0.bundleKey(), 1, Actionable.SIMULATE) > 0;
                            }
                        }
                        // DIVERGENT 2-CYCLE FIX (dust_steel ↔ ingot_steel smelting/pulverizing):
                        // If this cyclic call happens while CAPTURING another key (the pattern
                        // being built needs an ancestor → a cross-cycle), that capturing key
                        // cannot be satisfied by crafting — it can only come from stock. Mark it
                        // so the parent's RETURN skips it from itemNeeds (stock-only leaf) and
                        // the aggregation never gives it a craft demand. A pure self-loop (the
                        // capturing key == the cyclic call target) is left as-is.
                        // (v1.14.x) Seeded 2-hop rings (dust↔ingot) never reach this branch:
                        // wouldCauseCycle (resolve, stock-aware) allows them, so the smelt
                        // pattern expands with its leaf input available and no CALL cycle forms.
                        CallFrame capFrame = callStack.peek();
                        // (v1.14.x JIT-GRAPH) SEEDED-RING REDUNDANCY CUT: the capturing
                        // pattern's output key sits in real stock → its production is
                        // redundant. The ring would fabricate that output from nothing
                        // (fake INSERT_OUTPUT) and the parent would consume it from
                        // simInternal instead of the network (false used=0). Mark it
                        // stock-only: RETURN skips its needs, INSERT_OUTPUT is suppressed.
                        if (capFrame != null && capFrame.bundleKey() != null && !capFrame.bundleKey().equals(tk)) {
                            boolean capSeeded = simulation.extract(capFrame.bundleKey(), 1, Actionable.SIMULATE) > 0;
                            if (capSeeded) {
                                cyclicCraftKeys.add(capFrame.bundleKey());
                                callStack.pollFirst();
                                callStack.addFirst(capFrame.withCycleCut());
                            }
                        }
                        if (capFrame != null && capFrame.bundleKey() != null && !capFrame.bundleKey().equals(tk)) {
                            // (v1.14.x DEFINITION-GRAPH) Dead-ring member (unseeded ring):
                            // its deficit is recorded directly into this capture context
                            // (the enclosing frame's bundle diff) — no needs-cut needed;
                            // aggregation reports it as missing when no stock exists.
                            // (v1.13.x GTL PLAN-A) The cycle guard cut the capturing pattern out
                            // of the craft graph, so its PRIMARY OUTPUT is no longer produced.
                            // The enclosing frame still needs it as an input — without this it is
                            // in NEITHER usedItems NOR patternTimes, and the transfinite CPU
                            // stalls at zero progress (WAITING_FOR_INPUTS forever). Pull the
                            // skipped pattern's output from REAL network stock instead.
                            java.util.Iterator<CallFrame> fit = callStack.iterator();
                            CallFrame parentFrame = null;
                            int depth = 0;
                            for (; fit.hasNext(); depth++) {
                                CallFrame fr = fit.next();
                                if (depth == 1) { parentFrame = fr; break; }
                            }
                            if (parentFrame != null && parentFrame.subCalls != null) {
                                Long needOut = parentFrame.subCalls.get(capFrame.bundleKey());
                                if (needOut != null && needOut > 0) {
                                    long gotOut = simulation.extract(capFrame.bundleKey(), needOut, captureAction());
                                    if (gotOut > 0) {
                                        long internalOut = simInternal.get(capFrame.bundleKey());
                                        long fromInternalOut = Math.min(gotOut, internalOut);
                                        if (fromInternalOut > 0) simInternal.add(capFrame.bundleKey(), -fromInternalOut);
                                        long fromNetworkOut = gotOut - fromInternalOut;
                                        if (!extractIsClaim && fromNetworkOut > 0 && !capturingBundle()) usedItems.add(capFrame.bundleKey(), fromNetworkOut);
                                    } else if (!extractIsClaim && !ringSeeded) {
                                        missingItems.add(capFrame.bundleKey(), needOut);
                                    }
                                }
                            }
                        }
                        // (v1.13.x GTL PLAN-A) Real-stock check: NO addStackBytes fabrication.
                        // The cycle demand can only be satisfied by what the network actually
                        // holds; fabrication claimed feasibility for items that were not there,
                        // so the transfinite CPU's tryExtractInitialItems/submit passed but the
                        // job then stalled (input never in usedItems nor in patternTimes).
                        nodeCount++;
                        long gotx = simulation.extract(tk, req, captureAction());
                        if (gotx > 0) {
                            long internal = simInternal.get(tk);
                            long fromInternal = Math.min(gotx, internal);
                            if (fromInternal > 0) simInternal.add(tk, -fromInternal);
                            long fromNetwork = gotx - fromInternal;
                            if (!extractIsClaim && fromNetwork > 0 && !capturingBundle()) usedItems.add(tk, fromNetwork);
                        } else if (!extractIsClaim && !ringSeeded) {
                            // Nothing consumable from the cycle → the demand is genuinely missing.
                            // (Do NOT silently drop it — that caused non-deterministic under-counting.)
                            missingItems.add(tk, req);
                        }
                        break;
                    }
                    long opc = sbc.getOutputAmountPerCraft();
                    // (v1.12.x GTL BIG-ORDER FIX) Saturating ceil-div: (req + opc - 1)
                    // overflows to a negative craft count when req is near Long.MAX_VALUE
                    // (10^18+ sub-craft demand) — the chain then silently crafts nothing.
                    long cts = opc <= 0 ? 0 : ceilDiv(req, opc);
                    if (cts <= 0) { resolvingKeys.remove(tk); break; }
                    
                    // Record this direct sub-call on the enclosing dispatch frame, so the
                    // parent bundle captures only DIRECT effects (sub-tree via needs).
                    boolean capturing = !callStack.isEmpty() && callStack.peek().bundleKey() != null;
                    if (!callStack.isEmpty()) callStack.peek().recordSubCall(tk, req);
                    // (v1.10.x video fix) Also record when the sub-call comes from a
                    // replacement-enabled (fuzzy) slot: only that portion of the child's
                    // demand may be satisfied by substitute stock at the aggregation.
                    if (slotFuzzy && !callStack.isEmpty()) {
                        callStack.peek().recordFuzzySubCall(tk, req);
                    }
                    
                    Bundle[] bundles = bundleCache.computeIfAbsent(tk, k -> new Bundle[MAX_BUNDLE_BITS]);
                    
                    if (capturing) {
                        // Building a parent's bundle: do NOT apply this sub-call now. It is
                        // referenced via the parent's needs and applied on replay. Only make
                        // sure the sub-bundle exists (dispatch a 1-craft to build it).
                        if (bundles[0] == null) {
                            // (v1.11.x DIAG) bundles[0]==null: first-time or pattern was
                            // missing last time. sub!=null means pattern is now available.
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY capturing {} bundles[0]=null, sub={} → dispatch 1-craft (parent={})", tk, sub != null, callStack.peek().bundleKey());
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
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY capturing {} bundles[0] incomplete (subBundlesComplete=false) → re-capture (parent={})", tk, callStack.peek().bundleKey());
                            resolvingKeys.remove(tk);
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                        } else if (recapturedInThisExecute.add(tk) && (staleMissingRecheck(tk, bundles[0])
                                || bundlePatternChanged(bundles[0], tk))) {
                            // (v1.11.x STALE-MISSING RECHECK): the cached bundle recorded a
                            // missing leaf that NOW has a pattern (added after this bundle was
                            // captured — the updatePatterns mixin's bumpPatternVersion did not
                            // fire or was missed). Re-capture so the intermediate is crafted
                            // instead of reported missing. This is the melodic_item_conduit →
                            // pulsating_powder fix: works alone, missing in the chain.
                            // (v1.12.x GTL PATTERN-IDENTITY) bundlePatternChanged: the player
                            // swapped/modified the intermediate's pattern (new IPatternDetails
                            // with different content) — reuse would key the plan on an OLD
                            // pattern the providers no longer expose (CPU stall = false
                            // positive) or demand the OLD recipe's inputs (false negative).
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY capturing {} stale-missing (a missing leaf now has a pattern) → re-capture (parent={})", tk, callStack.peek().bundleKey());
                            resolvingKeys.remove(tk);
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, cts));
                            loadBytecode(sbc); pushL(1);
                        } else {
                            // (v1.11.x DIAG) Bundle reuse: previously captured bundle is complete
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY capturing {} bundles[0] REUSE (parent={})", tk, callStack.peek().bundleKey());
                            resolvingKeys.remove(tk);
                        }
                        break;
                    }
                    
                    // cts==1: check JIT memoization cache first
                    if (cts == 1) {
                        if (bundles[0] == null) {
                            // (v1.11.x DIAG) bundles[0]==null: first call or pattern was missing
                            // before. sub!=null means pattern is now available → dispatch.
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY cts=1 {} bundles[0]=null, sub={} → dispatch 1-craft", tk, sub != null);
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
                        // (v1.11.x STALE-MISSING RECHECK): a missing leaf recorded in this
                        // memo now has a pattern (added after capture — the pattern-update
                        // version bump did not fire). Re-capture instead of reusing the stale
                        // memo, so the intermediate is crafted rather than reported missing.
                        if (recapturedInThisExecute.add(tk) && (staleMissingRecheck(tk, bundles[0])
                                || bundlePatternChanged(bundles[0], tk))) {
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY cts=1 {} stale-missing/pattern-changed → re-capture", tk);
                            bundles[0] = null;
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, 1));
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
                        
                        // (v1.11.x STALE-MISSING RECHECK): a missing leaf recorded in this
                        // bundle now has a pattern (added after capture — the pattern-update
                        // version bump did not fire). Re-capture instead of reusing the stale
                        // bundle, so the intermediate is crafted rather than reported missing.
                        if (recapturedInThisExecute.add(tk) && (staleMissingRecheck(tk, b0)
                                || bundlePatternChanged(b0, tk))) {
                            // LOG disabled: AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY cts>1 {} stale-missing/pattern-changed → re-capture", tk);
                            bundles[0] = null;
                            Bundle snap = captureDelta();
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                                .withBundle(tk, snap, req));
                            loadBytecode(sbc); pushL(1);
                            break;
                        }
                        
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
                case 20 -> currentSlotFuzzy = true; // FUZZY_SLOT (0x14) — next CALL_BY_KEY is a replacement-enabled slot
                case 17 -> { int idx=readShort(); long amt=popL(); // INSERT_OUTPUT
                    if(amt>0 && (callStack.isEmpty() || !callStack.peek().cycleCut())){
                        simulation.insert(constantPool[idx],amt,Actionable.MODULATE);
                        simInternal.add(constantPool[idx], amt);
                        // Always record the crafted output in emittedItems (matches AE2's
                        // CraftingTreeProcess.emitItems). The final requested item is
                        // removed again in buildPlan, so intermediates show up correctly
                        // instead of the plan always reporting emit=0.
                        emittedItems.add(constantPool[idx], amt);
                    }
                }
                case 18 -> { // CATALYST_SEED <keyIdx> — one-time catalyst/container seed demand
                    int idx = readShort(); long amt = popL();
                    if (amt > 0 && constantPool[idx] != null) {
                        catalystSeedItems.add(constantPool[idx], amt);
                    }
                }
                case 19 -> { // DURABILITY_TOOL <keyIdx> — finite-use tool rate (amount, uses)
                    int idx = readShort(); long uses = popL(); long amt = popL();
                    if (amt > 0 && uses > 0 && constantPool[idx] != null) {
                        durabilityItems.put(constantPool[idx], new long[]{amt, uses});
                    }
                }
                case 255 -> { // HALT
                    simulation.addBytes(nodeCount*8.0);
                    if(rootCraftTimes>0&&outputKey!=null) simulation.addStackBytes(outputKey,1,rootCraftTimes);
                    ICraftingPlan plan = buildPlan(requestedAmount);
                    logPlanResult(plan, vmStartNs);
                    storeFastPlanCache(plan);
                    return plan; }
                default -> {} // unknown opcode, skip
            }
        }
        ICraftingPlan plan = buildPlan(requestedAmount);
        logPlanResult(plan, vmStartNs);
        storeFastPlanCache(plan);
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

    /**
     * (v1.11.x DEBUG LOG) Log the complete plan result after execution:
     * - missing items (should be empty for a feasible plan)
     * - total crafts per pattern (patternTimes)
     * - total input items across all patterns (原料总单数)
     * - calc time
     */
    private void logPlanResult(ICraftingPlan plan, long vmStartNs) {
        long calcUs = (System.nanoTime() - vmStartNs) / 1_000;
        // Compute total input count from patternTimes
        long totalInputs = 0;
        if (!patternTimes.isEmpty()) {
            for (var e : patternTimes.entrySet()) {
                IPatternDetails pat = e.getKey();
                long times = e.getValue();
                if (pat != null) {
                    // (v1.12.x GTL DEFENSIVE) Exotic patterns may return a null input
                    // list / null possible-inputs — the total-input LOG must not NPE.
                    IPatternDetails.IInput[] patInputs = pat.getInputs();
                    if (patInputs == null) {
                        continue;
                    }
                    for (var input : patInputs) {
                        GenericStack[] stacks = input.getPossibleInputs();
                        if (stacks == null) {
                            continue;
                        }
                        for (GenericStack gs : stacks) {
                            if (gs != null && gs.what() != null) {
                                totalInputs += gs.amount() * input.getMultiplier() * times;
                            }
                        }
                    }
                }
            }
        }
        // Summarize missing items
        String missingSummary = "";
        if (!plan.missingItems().isEmpty()) {
            java.io.StringWriter sw = new java.io.StringWriter();
            sw.write("{");
            int i = 0;
            for (var e : plan.missingItems()) {
                if (i > 0) sw.write(", ");
                sw.write(e.getKey().toString());
                sw.write("=");
                sw.write(String.valueOf(e.getLongValue()));
                i++;
            }
            sw.write("}");
            missingSummary = sw.toString();
        } else {
            missingSummary = "(none)";
        }
        // Summarize patternTimes (FULL list — every pattern in the chain, sorted by craft count desc)
        String ptSummary = "";
        if (!patternTimes.isEmpty()) {
            java.io.StringWriter sw = new java.io.StringWriter();
            sw.write("{");
            // Sort by craft count descending
            java.util.List<java.util.Map.Entry<IPatternDetails, Long>> sorted =
                    new java.util.ArrayList<>(patternTimes.entrySet());
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            int i = 0;
            for (var e : sorted) {
                if (i > 0) sw.write(", ");
                String name = "?";
                GenericStack[] outs = e.getKey().getOutputs();
                if (outs != null && outs.length > 0 && outs[0] != null && outs[0].what() != null) {
                    name = outs[0].what().toString();
                }
                sw.write(name);
                sw.write("=");
                sw.write(String.valueOf(e.getValue()));
                i++;
            }
            sw.write("}");
            ptSummary = sw.toString();
        } else {
            ptSummary = "(none)";
        }
        AE2VMAddon.LOGGER.info(
                "[AE2-VM] === CRAFT END === outputKey={}, missing={}, patternTimes={}, totalInputUnits={}, calcTime={}us ({}ms)",
                outputKey, missingSummary, ptSummary, totalInputs, calcUs, String.format("%.2f", calcUs / 1000.0D));
    }

    
    private CraftingPlan buildPlan(BigInteger requestedAmount) {
        // Replay every captured bundle exactly once (aggregated totals).
        applyAggregation();

        // (v1.14.x GTL DIAG) Plan detail for real-network verification: used/missing
        // plus the per-key item demand vs real stock (fluids are the stall hotspot).
        if (!usedItems.isEmpty()) {
            StringBuilder sb = new StringBuilder("[AE2-VM DIAG] Plan usedItems:");
            for (var e : usedItems) sb.append(" ").append(e.getKey()).append("=").append(e.getLongValue());
            AE2VMAddon.LOGGER.info(sb.toString());
        }
        if (!missingItems.isEmpty()) {
            StringBuilder sb = new StringBuilder("[AE2-VM DIAG] Plan missingItems:");
            for (var e : missingItems) sb.append(" ").append(e.getKey()).append("=").append(e.getLongValue());
            AE2VMAddon.LOGGER.info(sb.toString());
        }
        if (lastItemDemand != null && !lastItemDemand.isEmpty()) {
            java.util.List<java.util.Map.Entry<AEKey, BigInteger>> sorted = new java.util.ArrayList<>(lastItemDemand.entrySet());
            sorted.sort((a, b) -> Long.compare(toLongSafe(b.getValue(), "diag-sort"), toLongSafe(a.getValue(), "diag-sort")));
            int n = 0;
            StringBuilder sb = new StringBuilder("[AE2-VM DIAG] Plan demand:");
            for (var e : sorted) {
                if (n++ >= 120) break;
                long need = toLongSafe(e.getValue(), "diag-demand");
                long stock = realStockOf(e.getKey());
                sb.append(" ").append(e.getKey()).append("=").append(need);
                if (stock > 0) sb.append("(s").append(stock).append(")");
                else if (need > 0) sb.append("(s0)");
            }
            AE2VMAddon.LOGGER.info(sb.toString());
        }
        AE2VMAddon.LOGGER.info("[AE2-VM DIAG] Plan sizes: used={} miss={} demand={} times={}",
            usedItems.size(), missingItems.size(), lastItemDemand == null ? -1 : lastItemDemand.size(), patternTimes.size());
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
        
        long bytes = (long)Math.ceil(bytesOfSimulation(simulation));
        long deliver;
        if (requestedAmount.compareTo(BIG_MAX_LONG) > 0) {
            deliver = Long.MAX_VALUE; batchRemainder = requestedAmount.subtract(BIG_MAX_LONG);
        } else { deliver = requestedAmount.longValue(); batchRemainder = null; }
        // CRITICAL: a complete plan (no missing items) must be simulation=false, otherwise
        // AE2's submitJob() rejects it (INCOMPLETE_PLAN) and the craft never starts.
        // Only plans that are missing ingredients are simulation=true (preview-only).
        boolean simulation = !missingItems.isEmpty();
        // (v1.12.18 EAE+ ADAPT) EAE+ (ExtendedAE Plus) 在计划构建阶段做智能翻倍
        // （CraftingSimulationStateMixin 把 crafts 重分批成 ScaledProcessingPattern）。
        // VM 自建 CraftingPlan 绕过了该步骤，因此在这里对最终 patternTimes 应用与
        // EAE+ 完全一致的镜像重分批（总量不变；EAE+ 未安装 / 反射失败时原样返回）。
        // 注意：只影响计划输出条目，patternTimes 字段保持原始 plain 条目（日志/聚合不变）。
        Map<IPatternDetails, Long> planPatternTimes = patternTimes;
        if (com.ae2vm.addon.compat.EAESmartDoublingCompat.isAvailable()
                && networkKey instanceof appeng.api.networking.IGrid grid0
                && grid0.getCraftingService() instanceof appeng.me.service.CraftingService cs) {
            planPatternTimes = com.ae2vm.addon.compat.EAESmartDoublingCompat.rebatch(patternTimes, cs);
        }
        return new CraftingPlan(new GenericStack(outputKey, deliver), bytes, simulation, false,
            usedItems, emittedItems, missingItems, new HashMap<>(planPatternTimes));
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
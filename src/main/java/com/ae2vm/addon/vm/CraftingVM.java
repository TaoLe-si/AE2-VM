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
    
    private final java.util.Set<AEKey> resolvingKeys = new java.util.HashSet<>();
    private boolean extractIsClaim; // RETURN sets this; next EXTRACT skips usedItems (sub-craft claim)
    
    // JIT: per-pattern power-of-2 bundles. Bundle[0]=1 run, Bundle[k]=2^k runs.
    // Each bundle stores the complete subtree effects (incl. sub-patterns).
    // Bundle[k] = Bundle[k-1].scale(2) — linear effects, no re-execution.
    private static final int MAX_BUNDLE_BITS = 64; // long bits 0–63
    private final Map<AEKey, Bundle[]> bundleCache = new HashMap<>();
    
    private record CallFrame(int returnPc, byte[] code, AEKey[] constantPool, 
                             IPatternDetails[] patternPool, AEKey resolvingKey,
                             AEKey bundleKey, Bundle bundleBefore, long savedReq) {
        CallFrame(int returnPc, byte[] code, AEKey[] constantPool, 
                  IPatternDetails[] patternPool, AEKey resolvingKey) {
            this(returnPc, code, constantPool, patternPool, resolvingKey, null, null, 0);
        }
        CallFrame withBundle(AEKey key, Bundle before, long req) {
            return new CallFrame(returnPc, code, constantPool, patternPool, resolvingKey, key, before, req);
        }
    }
    
    private static class Bundle {
        BigInteger bytes = BigInteger.ZERO;
        final Map<AEKey, BigInteger> used = new HashMap<>();
        final Map<AEKey, BigInteger> emitted = new HashMap<>();
        final Map<AEKey, BigInteger> missing = new HashMap<>();
        final Map<AEKey, BigInteger> internal = new HashMap<>(); // VM-inserted items offset
        final Map<IPatternDetails, BigInteger> patterns = new HashMap<>();
        
        Bundle scale(long factor) { return scale(BigInteger.valueOf(factor)); }
        Bundle scale(BigInteger factor) {
            Bundle b = new Bundle();
            b.bytes = bytes.multiply(factor);
            for (var e : used.entrySet()) b.used.put(e.getKey(), e.getValue().multiply(factor));
            for (var e : emitted.entrySet()) b.emitted.put(e.getKey(), e.getValue().multiply(factor));
            for (var e : missing.entrySet()) b.missing.put(e.getKey(), e.getValue().multiply(factor));
            for (var e : internal.entrySet()) b.internal.put(e.getKey(), e.getValue().multiply(factor));
            for (var e : patterns.entrySet()) b.patterns.put(e.getKey(), e.getValue().multiply(factor));
            return b;
        }
        
        boolean isEmpty() {
            return bytes.signum() == 0 && used.isEmpty() && emitted.isEmpty() && missing.isEmpty() 
                && internal.isEmpty() && patterns.isEmpty();
        }
    }
    
    /** Safe BigInteger→long conversion. Caps at Long.MAX_VALUE, logs warning on overflow. */
    private static long toLongSafe(BigInteger v, String ctx) {
        if (v.compareTo(BIG_MAX_LONG) > 0) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] Value exceeds Long.MAX_VALUE for {}, capping: {}", ctx, v);
            return Long.MAX_VALUE;
        }
        if (v.signum() < 0) return 0; // shouldn't happen for counts
        return v.longValue();
    }
    
    private void applyBundle(Bundle b) {
        simulation.addBytes(toLongSafe(b.bytes, "bytes"));
        for (var e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit:" + e.getKey());
            simulation.insert(e.getKey(), val, Actionable.MODULATE);
            emittedItems.add(e.getKey(), val);
            simInternal.add(e.getKey(), val);
        }
        for (var e : b.internal.entrySet()) {
            long val = toLongSafe(e.getValue(), "int:" + e.getKey());
            simulation.insert(e.getKey(), val, Actionable.MODULATE);
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
    
    /** Undo a bundle's effects — reverse order of apply. */
    private void revertBundle(Bundle b) {
        simulation.addBytes(-toLongSafe(b.bytes, "bytes-revert"));
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
        // Reverse internal offset (undo the simulation insert made in apply)
        for (var e : b.internal.entrySet()) {
            long val = toLongSafe(e.getValue(), "int-revert:" + e.getKey());
            simulation.extract(e.getKey(), val, Actionable.MODULATE);
            simInternal.add(e.getKey(), -val);
            if (simInternal.get(e.getKey()) == 0) simInternal.remove(e.getKey());
        }
        // Reverse emitted (undo insert → extract from sim, undo emittedItems and simInternal)
        for (var e : b.emitted.entrySet()) {
            long val = toLongSafe(e.getValue(), "emit-revert:" + e.getKey());
            simulation.extract(e.getKey(), val, Actionable.MODULATE);
            emittedItems.add(e.getKey(), -val);
            if (emittedItems.get(e.getKey()) == 0) emittedItems.remove(e.getKey());
            simInternal.add(e.getKey(), -val);
            if (simInternal.get(e.getKey()) == 0) simInternal.remove(e.getKey());
        }
    }
    
    private Bundle captureDelta() {
        Bundle b = new Bundle();
        b.bytes = BigInteger.valueOf((long)((com.ae2vm.addon.mixin.CraftingSimulationStateAccessor)simulation).getBytes());
        for (var e : usedItems) {
            long v = e.getLongValue();
            if (v != 0) b.used.put(e.getKey(), BigInteger.valueOf(v));
        }
        for (var e : emittedItems) {
            long v = e.getLongValue();
            if (v != 0) b.emitted.put(e.getKey(), BigInteger.valueOf(v));
        }
        for (var e : missingItems) {
            long v = e.getLongValue();
            if (v != 0) b.missing.put(e.getKey(), BigInteger.valueOf(v));
        }
        for (var e : simInternal) {
            long v = e.getLongValue();
            if (v != 0) b.internal.put(e.getKey(), BigInteger.valueOf(v));
        }
        for (var e : patternTimes.entrySet()) {
            long v = e.getValue();
            if (v != 0) b.patterns.put(e.getKey(), BigInteger.valueOf(v));
        }
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
        this.outputKey = requestBytecode.getOutput();
        this.extractIsClaim = false;
        resolvingKeys.clear();
        
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
                    if (callStack.isEmpty()) rootCraftTimes = ct;
                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, pat);
                    if (sbc == null) { PatternCompiler.compileIfAbsent(networkKey, pat); sbc = PatternCompiler.getCompiled(networkKey, pat); }
                    if (sbc == null || callStack.size() >= MAX_CALL_DEPTH) break;
                    callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                    loadBytecode(sbc); pushL(ct);
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
                            Bundle[] bundles = bundleCache.computeIfAbsent(f.resolvingKey, k -> new Bundle[MAX_BUNDLE_BITS]);
                            bundles[0] = delta;
                            AE2VMAddon.LOGGER.info("[AE2-VM JIT] bundle[0] {}", f.resolvingKey);
                            if (f.savedReq > 1) {
                                AE2VMAddon.LOGGER.info("[AE2-VM JIT] revert+rewind {} savedReq={}", f.resolvingKey, f.savedReq);
                                revertBundle(delta);
                                resolvingKeys.remove(f.resolvingKey);
                                pushL(f.savedReq);
                                pc = f.returnPc - 3;
                            } else {
                                // cts=1 memo: execution effects stand, just save bundle for future
                                resolvingKeys.remove(f.resolvingKey);
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
                        AE2VMAddon.LOGGER.warn("[AE2-VM] CALL_BY_KEY with null constant entry (kidx={})", kidx);
                        break;
                    }
                    IPatternDetails sub = patternResolver.apply(tk);
                    if (sub == null) {
                        AEKey ck = tk.dropSecondary();
                        if (!ck.equals(tk)) sub = patternResolver.apply(ck);
                    }
                    if (sub == null) {
                        AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → no pattern → missing", tk, req);
                        missingItems.add(tk, req);
                        break;
                    }
                    PatternCompiler.compileIfAbsent(networkKey, sub);
                    CraftingBytecode sbc = PatternCompiler.getCompiled(networkKey, sub);
                    if (sbc == null) { missingItems.add(tk, req); break; }
                    if (callStack.size() >= MAX_CALL_DEPTH) break;
                    if (!resolvingKeys.add(tk)) {
                        AE2VMAddon.LOGGER.warn("[AE2-VM]   → CALL_BY_KEY {} req={} → already resolving → missing", tk, req);
                        missingItems.add(tk, req); break;
                    }
                    long opc = sbc.getOutputAmountPerCraft();
                    long cts = opc <= 0 ? 0 : (req + opc - 1) / opc;
                    if (cts <= 0) { resolvingKeys.remove(tk); break; }
                    
                    AE2VMAddon.LOGGER.info("[AE2-VM JIT] CALL_BY_KEY {} req={} opc={} cts={}", tk, req, opc, cts);
                    
                    // cts==1: check JIT memoization cache first
                    if (cts == 1) {
                        Bundle[] bundles = bundleCache.computeIfAbsent(tk, k -> new Bundle[MAX_BUNDLE_BITS]);
                        if (bundles[0] != null) {
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
                                AE2VMAddon.LOGGER.info("[AE2-VM]   JIT cts=1 check {} used/call={} int/call={} netDrain={} totalAvail={} vmInternal={} realAvail={}",
                                    e.getKey(), usedPerCall, internalPerCall, netDrain, totalAvail, vmInternal, realAvail);
                                if (realAvail < netDrain) { sat1ok = false; break; }
                            }
                            if (sat1ok) {
                                AE2VMAddon.LOGGER.info("[AE2-VM JIT] cts=1 hit {} → apply memo", tk);
                                applyBundle(b0);
                                extractIsClaim = true;
                                resolvingKeys.remove(tk);
                                break;
                            }
                            // Stale memo: run normal exec
                            AE2VMAddon.LOGGER.warn("[AE2-VM JIT] cts=1 memo {} unsatisfiable → normal exec", tk);
                            resolvingKeys.remove(tk);
                            callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                            loadBytecode(sbc); pushL(1);
                            break;
                        }
                        // First call: execute normally, capture bundle[0] on RETURN
                        // First call: execute normally, capture bundle[0] on RETURN
                        Bundle snap = captureDelta();
                        callStack.push(new CallFrame(pc, code, constantPool, patternPool, tk)
                            .withBundle(tk, snap, 1));
                        loadBytecode(sbc); pushL(cts);
                        break;
                    }
                    
                    // cts>1: check JIT self-sufficiency if bundle[0] exists.
                    // No dispatch — bundle[0] is created naturally by cts==1
                    // sub-calls. dispatch+revert+rewind corrupts simInternal
                    // when nested CALL_BY_KEY triggers sub-JIT during dispatch,
                    // because revertBundle's used/internal reversal order cannot
                    // perfectly unwind overlapping nested effects.
                    Bundle[] bundles = bundleCache.computeIfAbsent(tk, k -> new Bundle[MAX_BUNDLE_BITS]);
                    
                    if (bundles[0] != null) {
                        Bundle b0 = bundles[0];
                        
                        // Fast path: self-sufficient (internal >= used for all items)
                        boolean selfSufficient = true;
                        for (var e : b0.used.entrySet()) {
                            long internal = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            if (toLongSafe(e.getValue(), "jit") > internal) { selfSufficient = false; break; }
                        }
                        if (selfSufficient) {
                            AE2VMAddon.LOGGER.info("[AE2-VM JIT] self-sufficient {} cts={}", tk, cts);
                            for (long i = 0; i < cts; i++) applyBundle(b0);
                            resolvingKeys.remove(tk);
                            break;
                        }
                        
                        // Coverage path: compute max replay count from netDrain per item.
                        long maxCoverage = cts;
                        for (var e : b0.used.entrySet()) {
                            long usedPerCall = toLongSafe(e.getValue(), "cov:" + e.getKey());
                            long internalPerCall = b0.internal.getOrDefault(e.getKey(), BigInteger.ZERO).longValue();
                            long netDrain = Math.max(0, usedPerCall - internalPerCall);
                            if (netDrain == 0) continue;
                            long totalAvail = simulation.extract(e.getKey(), Long.MAX_VALUE / 4, Actionable.SIMULATE);
                            long realAvail = Math.max(0, totalAvail - simInternal.get(e.getKey()));
                            long times = realAvail / netDrain;
                            if (times < maxCoverage) maxCoverage = times;
                        }
                        if (maxCoverage > 0) {
                            for (long i = 0; i < maxCoverage; i++) applyBundle(b0);
                            long remainder = cts - maxCoverage;
                            if (remainder > 0) {
                                AE2VMAddon.LOGGER.warn("[AE2-VM JIT] coverage {} cts={} max={} remainder={} → normal exec", tk, cts, maxCoverage, remainder);
                                resolvingKeys.remove(tk);
                                callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                                loadBytecode(sbc); pushL(remainder);
                                break;
                            }
                            AE2VMAddon.LOGGER.info("[AE2-VM JIT] cts>1 done {} cts={}", tk, cts);
                            resolvingKeys.remove(tk);
                            break;
                        }
                        AE2VMAddon.LOGGER.warn("[AE2-VM JIT] coverage {} cts={} max=0 → normal exec", tk, cts);
                    }
                    // Normal exec (no memo or not self-sufficient or coverage exhausted)
                    resolvingKeys.remove(tk);
                    callStack.push(new CallFrame(pc, code, constantPool, patternPool, null));
                    loadBytecode(sbc); pushL(cts);
                }
                case 17 -> { int idx=readShort(); long amt=popL(); // INSERT_OUTPUT
                    if(amt>0){
                        simulation.insert(constantPool[idx],amt,Actionable.MODULATE);
                        simInternal.add(constantPool[idx], amt);
                        boolean isRoot = callStack.size() == 1;
                        if(isRoot) emittedItems.add(constantPool[idx],amt);
                    }
                }
                case 255 -> { // HALT
                    simulation.addBytes(nodeCount*8.0);
                    if(rootCraftTimes>0&&outputKey!=null) simulation.addStackBytes(outputKey,1,rootCraftTimes);
                    return buildPlan(requestedAmount); }
                default -> {} // unknown opcode, skip
            }
        }
        return buildPlan(requestedAmount);
    }
    
    private CraftingPlan buildPlan(BigInteger requestedAmount) {
        // Extension-provided items: produced externally → treat as emitted (will be crafted)
        if (!ecoExternalItems.isEmpty())
            for (AEKey k : ecoExternalItems.keySet()) {
                usedItems.remove(k);
                emittedItems.add(k, ecoExternalItems.get(k));
            }
        
        // finalOutput already separate in CraftingPlan — must not duplicate in emittedItems
        emittedItems.remove(outputKey);
        
        AE2VMAddon.LOGGER.info("[AE2-VM] === PLAN: used={} emit={} miss={}", usedItems.size(), emittedItems.size(), missingItems.size());
        for (var e : usedItems) AE2VMAddon.LOGGER.info("[AE2-VM]   USED {} x {}", e.getLongValue(), e.getKey());
        for (var e : emittedItems) AE2VMAddon.LOGGER.info("[AE2-VM]   EMIT {} x {}", e.getLongValue(), e.getKey());
        for (var e : missingItems) {
            // Diagnose false-missing: does the network actually have a pattern for this item?
            boolean hasPattern = patternResolver != null && patternResolver.apply(e.getKey()) != null;
            AE2VMAddon.LOGGER.info("[AE2-VM]   MISS {} x {} (hasPattern={})", e.getLongValue(), e.getKey(), hasPattern);
        }

        // DIAGNOSTIC: compare usedItems against real network stock. If any used item
        // exceeds what the network can actually provide, AE2's CPU will refuse the job
        // at submit time with CraftErrorMissingIngredient ("无法从网络提取原料").
        try {
            if (networkKey instanceof appeng.api.networking.IGrid grid) {
                var storage = grid.getStorageService();
                if (storage != null) {
                    var realStock = storage.getInventory().getAvailableStacks();
                    for (var e : usedItems) {
                        long avail = realStock.get(e.getKey());
                        if (avail < e.getLongValue()) {
                            AE2VMAddon.LOGGER.warn("[AE2-VM]   USED-SHORTFALL {}: need={} network={}", e.getKey(), e.getLongValue(), avail);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            AE2VMAddon.LOGGER.warn("[AE2-VM] usedItems-vs-network diagnostic failed: {}", t.toString());
        }
        
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

package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces: "刚放进样板供应器的样板，在合成规划内没有被算作可合成，
 * 但是单独合成又能计算" — when the pattern provider's resolver is NOT updated
 * (returns stale data) after new patterns are added.
 *
 * This can happen in real AE2 when the pattern provider's internal cache
 * is not invalidated after patterns are added/removed.
 *
 * Additionally tests: complex chain with BYPRODUCT main+secondary outputs.
 */
public class PatternProviderUpdateTest {

    // ---- keys ----
    private static final VariantKey TOP     = VariantKey.of("top",     "");
    private static final VariantKey MID     = VariantKey.of("mid",     "");
    private static final VariantKey LEAF    = VariantKey.of("leaf",    "");
    private static final VariantKey SCRAP   = VariantKey.of("scrap",   ""); // byproduct

    // Chain: TOP → MID → LEAF (with byproduct SCRAP at MID level)
    private static IPatternDetails pTop() {
        return new VPattern(TOP, 1, List.of(new ExactInput(MID, 1)),
                List.of(new GenericStack(SCRAP, 1)));
    }
    private static IPatternDetails pMid() {
        return new VPattern(MID, 1, List.of(new ExactInput(LEAF, 1)));
    }

    private static IPatternDetails pLeaf() {
        return new VPattern(LEAF, 1, List.of());
    }

    // ---------- Scenario 1: JIT fail cache not cleared on pattern registration ----------
    // An intermediate item's pattern is registered AFTER the first request.
    // The JIT memoization cache (which records satisfiability failures) is NOT
    // cleared when the pattern is added, so the key is still treated as uncraftable.
    // Additionally tests: complex chain with BYPRODUCT (main+secondary outputs).

    @Test
    void jitFailCacheNotClearedOnPatternRegistration() {
        // NOTE: NO stock of any item — everything must be crafted from patterns
        Map<VariantKey, Long> stock = new HashMap<>();

        // Initial patterns: only TOP (MID pattern NOT registered yet)
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(TOP, pTop());
        // NOTE: no MID pattern yet (MID has no output pattern, it's a true leaf at this point)

        // Create VM once — simulates reusing VM across pattern updates
        CraftingVM vm = new CraftingVM("jit-bug-test",
                key -> { if (key instanceof VariantKey vk) return patterns.get(vk); return null; });

        // STEP 1: Request TOP (MID has no pattern, stock is empty)
        // MID should be reported as missing (no pattern, no stock)
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req1 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan1 = vm.execute(req1, new StockSimState(stock));
        // MID should be missing (no pattern)
        assertTrue(hasMissing(plan1, MID),
                "Step 1: MID has no pattern, no stock → should be missing, actual: " + missing(plan1));

        // STEP 2: NOW register MID pattern (MID now produces 1 MID, consumes 1 LEAF)
        // Also register a LEAF pattern (LEAF produces 1 LEAF, no inputs)
        // BUG: jitFailCache still contains MID (from step 1 failure)
        patterns.put(MID, pMid());
        patterns.put(LEAF, pLeaf());
        PatternCompiler.clearCache();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);

        // STEP 3: Re-request TOP — MID now has P_MID, LEAF has P_LEAF
        // BUG: if jitFailCache is stale, MID might still be treated as uncraftable
        // even though P_MID now exists
        CraftingBytecode req2 = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan2 = vm.execute(req2, new StockSimState(stock));
        assertEquals(0L, plan2.missingItems().size(),
                "JIT BUG: MID now has P_MID, LEAF has P_LEAF → should NOT be missing. missing="
                + missing(plan2));

        // STEP 4: Request MID alone — should work
        CraftingBytecode req3 = PatternCompiler.compileRequest(patterns.get(MID), 1);
        ICraftingPlan plan3 = vm.execute(req3, new StockSimState(stock));
        assertEquals(0L, plan3.missingItems().size(),
                "MID alone should work. missing=" + missing(plan3));
    }

    // ---------- Scenario 2: Stale resolver (provider with internal state) ----------
    // The resolver is a mutable object whose internal state changes when patterns
    // are added, but the VM's reference to it doesn't change. This tests whether
    // the VM's patternResolver closure captures the initial state of the provider.

    @Test
    void staleResolverWithMutableProvider() {
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 1000L);

        // Mutable pattern provider (simulates real AE2 pattern provider)
        StalePatternProvider provider = new StalePatternProvider();
        provider.add(TOP, pTop());
        // NOTE: no MID pattern yet

        CraftingVM vm = new CraftingVM("stale-provider-test",
                key -> { if (key instanceof VariantKey vk) return provider.get(vk); return null; });

        // STEP 1
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        provider.compileAll();
        CraftingBytecode req1 = PatternCompiler.compileRequest(provider.get(TOP), 1);
        ICraftingPlan plan1 = vm.execute(req1, new StockSimState(stock));
        assertTrue(hasMissing(plan1, MID), "Step 1: MID not registered → missing");

        // STEP 2: Add MID to provider
        // BUG: StalePatternProvider's internal compiled cache is NOT cleared
        // when new patterns are added. So provider.get(MID) returns null
        // even though MID was just added.
        provider.add(MID, pMid());
        // NOTE: provider's internal caches are NOT invalidated

        // STEP 3: Re-request TOP
        // BUG: provider.get(MID) still returns null because compileAll was only
        // called once. The stale resolver prevents MID from being resolved.
        CraftingBytecode req2 = PatternCompiler.compileRequest(provider.get(TOP), 1);
        ICraftingPlan plan2 = vm.execute(req2, new StockSimState(stock));

        // This should pass but FAILS if provider is stale
        // (MID reported as missing even though pattern exists)
        assertEquals(0L, plan2.missingItems().size(),
                "STALE RESOLVER BUG: MID pattern was added but resolver still returns null. missing="
                + missing(plan2));

        // STEP 4: After calling compileAll again, MID should be found
        PatternCompiler.clearCache();
        provider.compileAll();
        CraftingBytecode req3 = PatternCompiler.compileRequest(provider.get(TOP), 1);
        ICraftingPlan plan3 = vm.execute(req3, new StockSimState(stock));
        assertEquals(0L, plan3.missingItems().size(),
                "After compileAll, MID should be resolved. missing=" + missing(plan3));
    }

    // ---------- helpers ----------

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(e.getKey().toString(), e.getLongValue());
        }
        return out;
    }

    private static boolean hasMissing(ICraftingPlan p, AEKey key) {
        for (var e : p.missingItems()) {
            if (e.getKey().equals(key)) return true;
        }
        return false;
    }

    // ---- minimal pattern/sim helpers ----

    private static final class VPattern implements IPatternDetails {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;
        VPattern(VariantKey out, long amount, List<IPatternDetails.IInput> inputList) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, amount)};
        }
        VPattern(VariantKey out, long amount, List<IPatternDetails.IInput> inputList,
                 List<GenericStack> byproducts) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            List<GenericStack> all = new ArrayList<>();
            all.add(new GenericStack(out, amount));
            all.addAll(byproducts);
            this.outputs = all.toArray(new GenericStack[0]);
        }
        @Override public GenericStack[] getOutputs() { return outputs; }
        @Override public IPatternDetails.IInput[] getInputs() { return inputs; }
        @Override public AEItemKey getDefinition() { return null; }
    }

    private static final class ExactInput implements IPatternDetails.IInput {
        private final GenericStack[] possible;
        ExactInput(AEKey key, long amount) {
            this.possible = new GenericStack[]{new GenericStack(key, amount)};
        }
        @Override public GenericStack[] getPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return 1; }
        @Override public boolean isValid(AEKey input, Level level) {
            return input.equals(possible[0].what());
        }
        @Override public AEKey getRemainingKey(AEKey template) { return null; }
    }

    private static final class StockSimState extends appeng.crafting.inv.CraftingSimulationState
            implements com.ae2vm.addon.mixin.CraftingSimulationStateAccessor {
        private final Map<VariantKey, Long> stock;
        StockSimState(Map<VariantKey, Long> stock) { this.stock = stock; }
        @Override
        protected long simulateExtractParent(AEKey what, long amount) {
            long available = what instanceof VariantKey k ? stock.getOrDefault(k, 0L) : 0L;
            return Math.min(available, amount);
        }
        @Override
        protected Iterable<AEKey> findFuzzyParent(AEKey input) {
            List<AEKey> variants = new ArrayList<>();
            for (VariantKey k : stock.keySet()) {
                if (k.base().equals(input.getPrimaryKey())) variants.add(k);
            }
            return variants;
        }
        @Override
        public double getBytes() {
            try {
                var f = appeng.crafting.inv.CraftingSimulationState.class.getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Simulates a pattern provider with an internal compiled-pattern cache that
     * is NOT invalidated when new patterns are added. This is the suspected root
     * cause of the "刚放进样板供应器的样板" bug.
     */
    private static final class StalePatternProvider {
        private final Map<VariantKey, IPatternDetails> raw = new HashMap<>();
        // Internal compiled cache — NOT invalidated on add()
        private final Map<VariantKey, CraftingBytecode> compiled = new HashMap<>();

        void add(VariantKey key, IPatternDetails pattern) {
            raw.put(key, pattern);
            // BUG: compileIfAbsent is NOT called here
            // In real AE2, this would correspond to updatePatterns not being called
        }

        IPatternDetails get(VariantKey key) {
            return raw.get(key);
        }

        void compileAll() {
            PatternCompiler.clearCache();
            for (IPatternDetails p : raw.values()) {
                PatternCompiler.compileIfAbsent(p);
            }
        }
    }
}

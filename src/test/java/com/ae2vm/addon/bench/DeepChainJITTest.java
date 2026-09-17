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
 * Reproduces the 10-20 layer deep chain false-missing bug:
 * "在长/复杂/多合成替换链中，有概率提示已经有样板的物品缺少物品，
 * 但实际上可以合成"
 *
 * Key scenario: A 20-level chain where JIT memoization interacts with
 * shared stock in a way that causes an intermediate item (that HAS a pattern)
 * to be incorrectly reported as missing.
 *
 * The critical interaction:
 * 1. Deep chain (10-20 levels): each level's bundle is captured via cts==1 path
 * 2. Shared stock: sibling crafts at same level consume from the same pool
 * 3. JIT satisfiability check (cts==1): for each used item, checks if
 *    realAvail >= netDrain. But realAvail is the CURRENT stock (after sibling
 *    levels consumed some), while netDrain is per-call amount.
 * 4. If a sibling level consumed stock between capture and JIT check,
 *    the satisfiability check fails → bundle is NOT applied via JIT
 * 5. The VM falls back to the no-bundle path, which might record the
 *    intermediate as missing.
 *
 * Additionally tests: chains WITH byproduct main+secondary outputs where
 * the byproduct output key resolution might interfere.
 */
public class DeepChainJITTest {

    // ---------- Scenario 1: 20-level chain with shared stock at multiple depths ----------
    // Chain: OUT[0] → OUT[1] → ... → OUT[19] → LEAF
    // Each OUT[i] pattern: 1 output, consumes 1 of OUT[i+1]
    // LEAF pattern: 1 output, no inputs
    // Partial stock at various levels: some OUT[i] have small stock, others have none
    // Shared stock: sibling items at same depth consume from same pool
    //
    // The bug: JIT satisfiability check might incorrectly fail for an intermediate
    // item that WAS satisfiable at capture time, because sibling stock consumption
    // reduced current stock below the threshold.

    @Test
    void twentyLevelChainWithSharedStock() {
        int DEPTH = 20;
        VariantKey[] OUT = new VariantKey[DEPTH];
        for (int i = 0; i < DEPTH; i++) OUT[i] = VariantKey.of("out_" + i, "");
        VariantKey LEAF = VariantKey.of("leaf", "");

        // Build patterns: OUT[i] → OUT[i+1], OUT[DEPTH-1] → LEAF, LEAF → no inputs
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        for (int i = 0; i < DEPTH - 1; i++) {
            patterns.put(OUT[i], simplePattern(OUT[i], List.of(new ExactInput(OUT[i + 1], 1))));
        }
        patterns.put(OUT[DEPTH - 1], simplePattern(OUT[DEPTH - 1], List.of(new ExactInput(LEAF, 1))));
        patterns.put(LEAF, simplePattern(LEAF, List.of()));

        // Partial stock: every 3rd level has enough for 1 craft, others have 0
        // This creates a scenario where JIT checks might fail because stock
        // at a deeper level is shared with a sibling that consumed it
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, (long) DEPTH); // just enough for 1 craft at each of 20 levels

        CraftingVM vm = new CraftingVM("deep-chain", key -> {
            if (key instanceof VariantKey vk) return patterns.get(vk);
            return null;
        });

        // First request: captures all bundles
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OUT[0]), 1);
        ICraftingPlan plan = vm.execute(req, new StockSimState(stock));

        // With 20 levels of craft, there should be NO missing items
        // (all patterns exist, stock is sufficient)
        assertEquals(0L, plan.missingItems().size(),
                "20-level chain with all patterns: no item should be missing. missing="
                + missing(plan));
    }

    // ---------- Scenario 2: 15-level chain with BYPRODUCT at each level ----------
    // Each level has: main output (the chain item) + byproduct (shared scrap)
    // The presence of byproduct might cause the JIT satisfiability check to
    // incorrectly evaluate shared stock consumption.

    @Test
    void fifteenLevelChainWithByproductEachLevel() {
        int DEPTH = 15;
        VariantKey[] MAIN = new VariantKey[DEPTH];
        for (int i = 0; i < DEPTH; i++) MAIN[i] = VariantKey.of("main_" + i, "");
        VariantKey SCRAP = VariantKey.of("scrap", "");
        VariantKey LEAF = VariantKey.of("leaf", "");

        // Patterns with byproduct
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        for (int i = 0; i < DEPTH - 1; i++) {
            patterns.put(MAIN[i], byproductPattern(MAIN[i],
                    List.of(new ExactInput(MAIN[i + 1], 1)), SCRAP, 1));
        }
        patterns.put(MAIN[DEPTH - 1], byproductPattern(MAIN[DEPTH - 1],
                List.of(new ExactInput(LEAF, 1)), SCRAP, 1));
        patterns.put(LEAF, simplePattern(LEAF, List.of()));

        // Stock: LEAF just enough for 1 craft each
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100L);

        CraftingVM vm = new CraftingVM("deep-byproduct", key -> {
            if (key instanceof VariantKey vk) return patterns.get(vk);
            return null;
        });

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(MAIN[0]), 1);
        ICraftingPlan plan = vm.execute(req, new StockSimState(stock));

        assertEquals(0L, plan.missingItems().size(),
                "15-level chain with byproduct: no missing items. missing="
                + missing(plan));
    }

    // ---------- Scenario 3: Deep chain where JIT bundle reuse fails ----------
    // First request: partial stock → some bundles captured with deficit
    // Second request (same VM): JIT reuses bundles, but satisfiability check
    // might incorrectly fail due to stock consumption by first request's siblings

    @Test
    void deepChainJITBundleReuseFailure() {
        int DEPTH = 12;
        VariantKey[] ITEMS = new VariantKey[DEPTH];
        for (int i = 0; i < DEPTH; i++) ITEMS[i] = VariantKey.of("item_" + i, "");

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        for (int i = 0; i < DEPTH - 1; i++) {
            patterns.put(ITEMS[i], simplePattern(ITEMS[i], List.of(new ExactInput(ITEMS[i + 1], 1))));
        }
        patterns.put(ITEMS[DEPTH - 1], simplePattern(ITEMS[DEPTH - 1], List.of()));

        // Partial stock at deepest level
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(ITEMS[DEPTH - 1], 5L); // only 5 crafts worth

        CraftingVM vm = new CraftingVM("deep-jit-reuse", key -> {
            if (key instanceof VariantKey vk) return patterns.get(vk);
            return null;
        });

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);

        // First execution: single request
        CraftingBytecode req1 = PatternCompiler.compileRequest(patterns.get(ITEMS[0]), 1);
        ICraftingPlan plan1 = vm.execute(req1, new StockSimState(stock));

        // Second execution: request same item with SAME VM (bundleCache persists)
        CraftingBytecode req2 = PatternCompiler.compileRequest(patterns.get(ITEMS[0]), 1);
        ICraftingPlan plan2 = vm.execute(req2, new StockSimState(stock));

        // Both should give the same result (all items craftable)
        assertEquals(plan1.missingItems().size(), plan2.missingItems().size(),
                "Repeated requests should give same missing count");

        // Both should have no missing items
        assertEquals(0L, plan2.missingItems().size(),
                "Deep chain: all patterns exist, no missing items. missing="
                + missing(plan2));
    }

    // ---------- Scenario 4: Multi-path deep chain (branching at each level) ----------
    // TOP needs [A, B], both A and B need [C, D], etc.
    // JIT bundle at C/D level might be incorrectly marked unsatisfiable
    // when stock is shared between sibling branches.

    @Test
    void multiPathDeepChainSharedStock() {
        VariantKey TOP = VariantKey.of("top", "");
        VariantKey L1A = VariantKey.of("l1_a", "");
        VariantKey L1B = VariantKey.of("l1_b", "");
        VariantKey L2A = VariantKey.of("l2_a", "");
        VariantKey L2B = VariantKey.of("l2_b", "");
        VariantKey LEAF = VariantKey.of("leaf", "");

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        // TOP → [L1A, L1B]
        patterns.put(TOP, simplePattern(TOP, List.of(
                new ExactInput(L1A, 1), new ExactInput(L1B, 1))));
        // L1A → [L2A, L2B]
        patterns.put(L1A, simplePattern(L1A, List.of(
                new ExactInput(L2A, 1), new ExactInput(L2B, 1))));
        // L1B → [L2A, L2B] (same inputs!)
        patterns.put(L1B, simplePattern(L1B, List.of(
                new ExactInput(L2A, 1), new ExactInput(L2B, 1))));
        // L2A → LEAF
        patterns.put(L2A, simplePattern(L2A, List.of(new ExactInput(LEAF, 1))));
        // L2B → LEAF
        patterns.put(L2B, simplePattern(L2B, List.of(new ExactInput(LEAF, 1))));

        // Stock: LEAF shared between L2A and L2B
        // L2A needs 2 crafts (top→l1a→l2a), L2B needs 2 crafts (top→l1b→l2b)
        // Total leaf needed = 4, stock = 3
        // L2A and L2B should each get some, but neither should be "missing" as in "no pattern"
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 3L); // less than total demand (4)

        CraftingVM vm = new CraftingVM("multi-path", key -> {
            if (key instanceof VariantKey vk) return patterns.get(vk);
            return null;
        });

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(TOP), 1);
        ICraftingPlan plan = vm.execute(req, new StockSimState(stock));

        // The missing should be LEAF (insufficient stock), NOT L1A/L1B/L2A/L2B
        // which all have patterns
        for (var e : plan.missingItems()) {
            String key = e.getKey().toString();
            assertTrue(key.equals("leaf"),
                    "Only LEAF (insufficient stock) should be missing, not " + key
                    + " which has a pattern. missing=" + missing(plan));
        }
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

    private static IPatternDetails simplePattern(VariantKey output, List<IPatternDetails.IInput> inputs) {
        return new VPattern(output, 1, inputs);
    }

    private static IPatternDetails byproductPattern(VariantKey main, List<IPatternDetails.IInput> inputs,
                                                    VariantKey byproduct, long byproductAmount) {
        return new VPattern(main, 1, inputs,
                List.of(new GenericStack(byproduct, byproductAmount)));
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
}

package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the report "在长/复杂/多合成替换链中，有概率把已经有样板的物品报成缺少"：
 * a craftable intermediate (it HAS a pattern) is put into {@code missingItems}
 * instead of being counted as to-be-crafted ({@code patternTimes}). The plan should
 * NEVER report missing for a key that has a pattern AND is reachable/needed.
 *
 * <p>Each fixture drives a REUSED {@link CraftingVM} across several requests (the
 * in-game per-grid VM reuse) because the bug is state-dependent ("有概率") — it
 * surfaces when a cached JIT bundle / stock-aware decision was made under one
 * stock level and then reused under another.
 */
public class LongMultiReplacementChainTest {

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /** One request = compile fresh + run on a FRESH VM (no cross-request reuse). */
    private static ICraftingPlan runOnce(Map<AEKey, IPatternDetails> byOutput, AEKey target,
                                         long amount, Map<BenchAEKey, Long> stock) {
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        FakeBenchGrid grid = new FakeBenchGrid(new HashMap<>(stock));
        CraftingVM vm = new CraftingVM(grid, byOutput::get);
        return vm.execute(req, new BenchSimulationState(stock));
    }

    /** Compile once, then run the SAME compiled request on a REUSED VM with evolving stock. */
    private static ICraftingPlan runReused(Map<AEKey, IPatternDetails> byOutput, AEKey target,
                                           long amount, Map<BenchAEKey, Long> stock,
                                           CraftingVM vm) {
        IPatternDetails top = byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
        return vm.execute(req, new BenchSimulationState(stock));
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) {
            out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        }
        return out;
    }

    private static Map<String, Long> crafted(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.patternTimes().entrySet()) {
            for (var gs : e.getKey().getOutputs()) {
                if (gs != null && gs.what() instanceof BenchAEKey k) {
                    out.merge(k.itemId(), e.getValue(), Long::sum);
                }
            }
        }
        return out;
    }

    private static long times(ICraftingPlan p, String id) {
        long t = 0;
        for (var e : p.patternTimes().entrySet()) {
            for (var gs : e.getKey().getOutputs()) {
                if (gs != null && gs.what() instanceof BenchAEKey k && k.itemId().equals(id)) {
                    t += e.getValue();
                }
            }
        }
        return t;
    }

    /**
     * Assert the core invariant: every key that is NEEDED by the plan and HAS a pattern
     * must be counted as to-be-crafted (appear in {@code patternTimes}), never reported
     * missing. Only true leaves (no pattern) may be missing.
     */
    private static void assertNoCraftableMissing(ICraftingPlan plan,
                                                 Map<AEKey, IPatternDetails> byOutput,
                                                 String what) {
        Map<String, Long> miss = missing(plan);
        for (var e : miss.entrySet()) {
            BenchAEKey k = BenchAEKey.of(e.getKey());
            boolean hasPattern = byOutput.containsKey(k);
            assertTrue(!hasPattern,
                    "[" + what + "] craftable key " + e.getKey() + " reported missing=" + e.getValue()
                            + " but it has a pattern and should be in patternTimes. missing=" + miss
                            + " crafted=" + crafted(plan));
        }
    }

    // ---------------------------------------------------------------------
    // Fixture: LONG LINEAR chain with replacement (fuzzy) groups at many levels.
    //   N1 = L0 + L1 ; Ni = N(i-1) + Li  (fresh leaf Li per level, fuzzy at even i)
    // Every leaf Li is fully stocked, so the ONLY question is whether the craftable
    // intermediates N1..N_levels are all counted as to-be-crafted (patternTimes),
    // never reported missing.
    // ---------------------------------------------------------------------
    private static Map<AEKey, IPatternDetails> deepLinearChain(int levels) {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        BenchAEKey[] keys = new BenchAEKey[levels + 1]; // keys[0]=N0, keys[i]=Ni craftable
        for (int i = 0; i <= levels; i++) {
            keys[i] = BenchAEKey.of("N" + i);
        }
        byOutput.put(keys[1], new BenchPatternDetails(keys[1], 1, List.of(
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("L0"), 1),
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("L1"), 1))));
        for (int i = 2; i <= levels; i++) {
            BenchAEKey leaf = BenchAEKey.of("L" + i);
            List<BenchPatternDetails.InputSpec> specs = new ArrayList<>();
            specs.add(BenchPatternDetails.InputSpec.of(keys[i - 1], 1));
            if (i % 2 == 0) {
                // replacement-enabled input: primary leaf Li or the substitute ALTi
                specs.add(BenchPatternDetails.InputSpec.fuzzy(leaf, 1, BenchAEKey.of("ALT" + i)));
            } else {
                specs.add(BenchPatternDetails.InputSpec.of(leaf, 1));
            }
            byOutput.put(keys[i], new BenchPatternDetails(keys[i], 1, specs));
        }
        return byOutput;
    }

    private static Map<BenchAEKey, Long> fullLinearStock(int levels) {
        Map<BenchAEKey, Long> stock = new HashMap<>();
        for (int i = 0; i <= levels; i++) {
            stock.put(BenchAEKey.of("L" + i), 1_000_000L);
        }
        for (int i = 2; i <= levels; i += 2) {
            stock.put(BenchAEKey.of("ALT" + i), 1_000_000L);
        }
        return stock;
    }

    @Test
    void deepChainFuzzyInputsNeverReportCraftableMissing() {
        int levels = 40; // long chain, linear leaf demand
        Map<AEKey, IPatternDetails> byOutput = deepLinearChain(levels);
        BenchAEKey target = BenchAEKey.of("N" + levels);

        // full leaf stock -> every request must be feasible, nothing missing, all crafted
        for (long amount : new long[]{1L, 5L, 100L}) {
            Map<BenchAEKey, Long> stock = fullLinearStock(levels);
            ICraftingPlan plan = runOnce(byOutput, target, amount, stock);
            assertEquals(0L, plan.missingItems().size(),
                    "full stock request " + amount + " must be feasible, missing=" + missing(plan));
            assertNoCraftableMissing(plan, byOutput, "full-stock-" + amount);
        }
    }

    @Test
    void deepChainPartialMidStockReusedVmNeverReportsCraftableMissing() {
        int levels = 40;
        Map<AEKey, IPatternDetails> byOutput = deepLinearChain(levels);
        BenchAEKey target = BenchAEKey.of("N" + levels);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : byOutput.values()) {
            PatternCompiler.compileIfAbsent(p);
        }
        IPatternDetails top = byOutput.get(target);
        CraftingBytecode req = PatternCompiler.compileRequest(top, 100);
        FakeBenchGrid grid = new FakeBenchGrid(new HashMap<>());
        CraftingVM vm = new CraftingVM(grid, byOutput::get);

        // Sequence of stocks: start with a fully-stocked mid N20 (so capture sees it
        // as stocked), then REMOVE it (so later requests must craft it).
        for (int round = 0; round < 3; round++) {
            Map<BenchAEKey, Long> stock = fullLinearStock(levels);
            if (round == 0) {
                stock.put(BenchAEKey.of("N20"), 5L); // mid stocked on first request only
            }
            ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));
            assertNoCraftableMissing(plan, byOutput,
                    "reused-round-" + round + " missing=" + missing(plan));
            assertEquals(0L, plan.missingItems().size(),
                    "round " + round + " must be feasible, missing=" + missing(plan));
        }
    }

    // ---------------------------------------------------------------------
    // Fixture: craftable primary with fuzzy substitute — the v1.10.5 class
    // but in a LONGER chain: product ← gray(fuzzy: gray, white) ; gray ← black
    // and product is itself an input to an even longer chain.
    // ---------------------------------------------------------------------
    @Test
    void longChainCraftablePrimaryWithFuzzySubstituteAlwaysCraftsPrimary() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey gray = BenchAEKey.of("gray_wool");
        BenchAEKey white = BenchAEKey.of("white_wool");
        BenchAEKey black = BenchAEKey.of("black_wool");
        // long chain on TOP: mega ← top2 ← top1 ← product
        BenchAEKey top1 = BenchAEKey.of("top1");
        BenchAEKey top2 = BenchAEKey.of("top2");
        BenchAEKey mega = BenchAEKey.of("mega");
        byOutput.put(top1, new BenchPatternDetails(top1, 1, List.of(
                BenchPatternDetails.InputSpec.of(product, 1),
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("extra1"), 1))));
        byOutput.put(top2, new BenchPatternDetails(top2, 1, List.of(
                BenchPatternDetails.InputSpec.of(top1, 1),
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("extra2"), 1))));
        byOutput.put(mega, new BenchPatternDetails(mega, 1, List.of(
                BenchPatternDetails.InputSpec.of(top2, 1),
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("extra3"), 1))));
        byOutput.put(product, new BenchPatternDetails(product, 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(gray, 1, white))));
        byOutput.put(gray, new BenchPatternDetails(gray, 1, List.of(
                BenchPatternDetails.InputSpec.of(black, 1))));

        // stock: black (for crafting gray) + extras, NO gray, NO white.
        // gray must be CRAFTED (not reported missing).
        for (long amount : new long[]{1L, 10L, 100L}) {
            Map<BenchAEKey, Long> stock = new HashMap<>();
            stock.put(BenchAEKey.of("extra1"), 1_000_000L);
            stock.put(BenchAEKey.of("extra2"), 1_000_000L);
            stock.put(BenchAEKey.of("extra3"), 1_000_000L);
            stock.put(black, 1_000_000L);
            ICraftingPlan plan = runOnce(byOutput, mega, amount, stock);
            assertEquals(0L, plan.missingItems().size(),
                    "amount " + amount + " must be feasible (gray craftable), missing=" + missing(plan));
            assertTrue(times(plan, "gray_wool") > 0,
                    "amount " + amount + " must craft gray_wool, crafted=" + crafted(plan));
            assertNoCraftableMissing(plan, byOutput, "long-fuzzy-" + amount);
        }
    }

    // ---------------------------------------------------------------------
    // Fixture: shared intermediate reached by MANY parents (wide DAG) where one
    // parent processes the intermediate before its parentCount is complete.
    // ---------------------------------------------------------------------
    @Test
    void wideSharedIntermediateNeverReportedMissing() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        BenchAEKey root = BenchAEKey.of("root");
        BenchAEKey shared = BenchAEKey.of("shared");
        BenchAEKey leaf = BenchAEKey.of("leaf");
        // root = shared + shared + leaf  (shared consumed twice -> demand 2*)
        byOutput.put(root, new BenchPatternDetails(root, 1, List.of(
                BenchPatternDetails.InputSpec.of(shared, 1),
                BenchPatternDetails.InputSpec.of(shared, 1),
                BenchPatternDetails.InputSpec.of(leaf, 1))));
        byOutput.put(shared, new BenchPatternDetails(shared, 1, List.of(
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("a1"), 1),
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("a2"), 1))));
        // three extra parents all pulling shared, so its parentCount spans many parents
        for (int i = 0; i < 3; i++) {
            BenchAEKey extra = BenchAEKey.of("extraRoot" + i);
            byOutput.put(extra, new BenchPatternDetails(extra, 1, List.of(
                    BenchPatternDetails.InputSpec.of(shared, 1),
                    BenchPatternDetails.InputSpec.of(BenchAEKey.of("e" + i + "_1"), 1))));
        }

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(leaf, 1_000_000L);
        stock.put(BenchAEKey.of("a1"), 1_000_000L);
        stock.put(BenchAEKey.of("a2"), 1_000_000L);
        for (int i = 0; i < 3; i++) {
            stock.put(BenchAEKey.of("e" + i + "_1"), 1_000_000L);
        }
        for (long amount : new long[]{1L, 50L}) {
            ICraftingPlan plan = runOnce(byOutput, root, amount, stock);
            assertEquals(0L, plan.missingItems().size(),
                    "amount " + amount + " must be feasible, missing=" + missing(plan));
            assertNoCraftableMissing(plan, byOutput, "wide-" + amount);
        }
    }
}

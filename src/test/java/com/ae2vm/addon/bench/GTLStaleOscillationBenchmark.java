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

import static org.junit.jupiter.api.Assertions.*;

/**
 * GTL Stale Oscillation Benchmark (VM-GTL branch, v1.12.x).
 *
 * Reproduces the exact GTL oscillation scenario from the 579MB log:
 *   1. Deep chain: OPV -> circuit_resonatic_hv -> circuit_resonatic_mv ->
 *      imprinted_resonatic_circuit_board -> raw_imprinted_resonatic_circuit_board ->
 *      magneto_resonatic_dust -> prasiolite_dust -> silicon_dust + oxygen.
 *   2. GTL patterns (via 超限演算阵列 / Overclocked Calculation Array) resolve
 *      (sub != null) but the VM still reports missing -- the pattern is synthetic
 *      and never produces extractable network items.
 *   3. Without oscillation guard: every execute() re-captures -> 40万次 CALL_BY_KEY
 *      on magneto_resonatic_dust alone -> 254万行 log -> 579MB log flood.
 *   4. Fix: recapturedInThisExecute Set blocks re-capture of the same key within
 *      one execute() call; AE2VMCrafting retry no longer clears the full
 *      bundleCache (only invalidates root pattern + recompile).
 *
 * Benchmarks:
 *   A) Oscillation guard: repeated execute() calls on one VM stay bounded.
 *   B) Missing-intermediate retry: order OPV -> missing, add pattern -> success.
 *   C) Deep-chain reuse performance sanity floor.
 *   D) GTL synthetic pattern with different request amounts.
 *   E) Cross-request determinism with a GTL synthetic pattern present.
 */
public class GTLStaleOscillationBenchmark {

    // ====================================================================
    // OPV chain keys (mirrors the 579MB-log chain used for reproduction)
    // ====================================================================
    private static final VariantKey OPV                = VariantKey.of("opv", "");
    private static final VariantKey CIRCUIT_HV          = VariantKey.of("circuit_resonatic_hv", "");
    private static final VariantKey CIRCUIT_MV          = VariantKey.of("circuit_resonatic_mv", "");
    private static final VariantKey IMPRINTED_BOARD     = VariantKey.of("imprinted_resonatic_circuit_board", "");
    private static final VariantKey RAW_IMPRINTED_BOARD = VariantKey.of("raw_imprinted_resonatic_circuit_board", "");
    private static final VariantKey MAGNETO_DUST        = VariantKey.of("magneto_resonatic_dust", "");
    private static final VariantKey PRASIOLITE_DUST     = VariantKey.of("prasiolite_dust", "");
    private static final VariantKey SILICON_DUST        = VariantKey.of("silicon_dust", "");
    private static final VariantKey OXYGEN              = VariantKey.of("oxygen", "");

    // GTL synthetic marker -- has a pattern, never extractable from stock.
    private static final VariantKey GTL_SYNTHETIC       = VariantKey.of("gtl_synthetic_flux", "");
    // Non-existent item required by GTL synthetic pattern -- has no pattern, no stock.
    // This simulates the 超限演算阵列: the GTL pattern resolves but the item can never
    // be extracted from the network, so the VM always reports it as missing.
    private static final VariantKey GTL_IMPOSSIBLE       = VariantKey.of("gtl_impossible_resource", "");

    // Additional scenario keys
    private static final VariantKey LIQUID_AIR          = VariantKey.of("liquid_ender_air", "");
    private static final VariantKey SMD_COSMIC          = VariantKey.of("smd_capacitor_cosmic", "");

    // ====================================================================
    // Pattern builders
    // ====================================================================

    // OPV = CIRCUIT_HV * 4 + LIQUID_AIR * 1000
    private static IPatternDetails pOpv() {
        return new VPattern(OPV, 1, List.of(
            new ExactInput(CIRCUIT_HV, 4),
            new ExactInput(LIQUID_AIR, 1000)
        ));
    }

    // CIRCUIT_HV = CIRCUIT_MV * 2 + SMD_COSMIC * 24
    private static IPatternDetails pCircuitHv() {
        return new VPattern(CIRCUIT_HV, 1, List.of(
            new ExactInput(CIRCUIT_MV, 2),
            new ExactInput(SMD_COSMIC, 24)
        ));
    }

    // CIRCUIT_MV = IMPRINTED_BOARD * 1
    private static IPatternDetails pCircuitMv() {
        return new VPattern(CIRCUIT_MV, 1, List.of(
            new ExactInput(IMPRINTED_BOARD, 1)
        ));
    }

    // IMPRINTED_BOARD = RAW_IMPRINTED_BOARD * 1
    private static IPatternDetails pImprintedBoard() {
        return new VPattern(IMPRINTED_BOARD, 1, List.of(
            new ExactInput(RAW_IMPRINTED_BOARD, 1)
        ));
    }

    // RAW_IMPRINTED_BOARD = MAGNETO_DUST * 1
    private static IPatternDetails pRawImprintedBoard() {
        return new VPattern(RAW_IMPRINTED_BOARD, 1, List.of(
            new ExactInput(MAGNETO_DUST, 1)
        ));
    }

    // MAGNETO_DUST = PRASIOLITE_DUST * 1 + GTL_SYNTHETIC * 1
    // (GTL-synthetic input is the oscillation trigger)
    private static IPatternDetails pMagnetoDust() {
        return new VPattern(MAGNETO_DUST, 1, List.of(
            new ExactInput(PRASIOLITE_DUST, 1),
            new ExactInput(GTL_SYNTHETIC, 1)
        ));
    }

    // PRASIOLITE_DUST = SILICON_DUST * 1 + OXYGEN * 1000
    private static IPatternDetails pPrasioliteDust() {
        return new VPattern(PRASIOLITE_DUST, 1, List.of(
            new ExactInput(SILICON_DUST, 1),
            new ExactInput(OXYGEN, 1000)
        ));
    }

    // Leaf patterns with no inputs (extractable from network)
    private static IPatternDetails pSiliconDust()    { return new VPattern(SILICON_DUST, 1, List.of()); }
    private static IPatternDetails pOxygen()         { return new VPattern(OXYGEN, 1, List.of()); }
    private static IPatternDetails pLiquidAir()      { return new VPattern(LIQUID_AIR, 1, List.of()); }
    private static IPatternDetails pSmdCosmic()      { return new VPattern(SMD_COSMIC, 1, List.of()); }

    /**
     * GTL synthetic pattern: resolves to a valid IPatternDetails but the produced
     * item is never physically extractable from the network. Simulates 超限演算阵列:
     * the CraftingService knows the pattern, but the output is synthetic -- it is
     * not storable, so the VM still ends up reporting it missing.
     */
    private static IPatternDetails pGtlSynthetic() {
        // Requires GTL_IMPOSSIBLE which has no pattern and no stock.
        // The pattern resolves (sub != null) but the item is always missing.
        // This triggers the oscillation: staleMissingRecheck sees the pattern
        // exists -> tries to re-capture -> re-captured bundle still missing.
        return new VPattern(GTL_SYNTHETIC, 1, List.of(
            new ExactInput(GTL_IMPOSSIBLE, 1)
        ));
    }

    // ====================================================================
    // SCENARIO A: GTL Oscillation Guard -- bounded repeated executes
    // ====================================================================
    @Test
    void gtlOscillationGuard_BoundedReCaptures() {
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(OPV, pOpv());
        patterns.put(CIRCUIT_HV, pCircuitHv());
        patterns.put(CIRCUIT_MV, pCircuitMv());
        patterns.put(IMPRINTED_BOARD, pImprintedBoard());
        patterns.put(RAW_IMPRINTED_BOARD, pRawImprintedBoard());
        patterns.put(MAGNETO_DUST, pMagnetoDust());
        patterns.put(PRASIOLITE_DUST, pPrasioliteDust());
        patterns.put(SILICON_DUST, pSiliconDust());
        patterns.put(OXYGEN, pOxygen());
        patterns.put(GTL_SYNTHETIC, pGtlSynthetic());

        // No GTL_SYNTHETIC in stock -- the oscillation trigger.
        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(SILICON_DUST, 64000L);
        stock.put(OXYGEN, 640000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OPV), 4);

        CraftingVM vm = new CraftingVM("gtl-oscillation", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });

        // Warmup captures all bundles.
        ICraftingPlan warmup = vm.execute(req, new StockSimState(stock));
        System.out.println("[gtl-oscillation] warmup missing=" + missing(warmup));
        assertTrue(hasMissing(warmup, GTL_IMPOSSIBLE),
            "Warmup: GTL_IMPOSSIBLE should be missing. missing=" + missing(warmup));

        int EXECUTIONS = 20;
        long[] times = new long[EXECUTIONS];
        String[] results = new String[EXECUTIONS];
        for (int i = 0; i < EXECUTIONS; i++) {
            long start = System.nanoTime();
            ICraftingPlan plan = vm.execute(req, new StockSimState(stock));
            times[i] = System.nanoTime() - start;
            results[i] = missing(plan).toString();

            double elapsedMs = times[i] / 1_000_000.0;
            assertTrue(elapsedMs < 5000.0,
                "Execute #" + i + " took " + elapsedMs + "ms -- oscillation likely present!");

            assertTrue(hasMissing(plan, GTL_IMPOSSIBLE),
                "Execute #" + i + ": GTL_IMPOSSIBLE missing. missing=" + missing(plan));
        }

        double avgMs = 0;
        for (long t : times) avgMs += t / 1_000_000.0;
        avgMs /= EXECUTIONS;

        for (int i = 1; i < EXECUTIONS; i++) {
            assertEquals(results[0], results[i],
                "Execute #" + i + " differs from #0 -- cross-request determinism broken!");
        }

        System.out.println("[gtl-oscillation] executions=" + EXECUTIONS
            + " avgMs=" + String.format("%.4f", avgMs)
            + " missing=" + results[0]);

        assertTrue(avgMs < 1000.0,
            "Avg reuse time " + avgMs + "ms exceeded 1000ms -- possible oscillation overhead");
    }

    // ====================================================================
    // SCENARIO B: Missing intermediate added after capture -- retry succeeds
    // ====================================================================
    @Test
    void gtlMissingIntermediateAddedAfterCapture() {
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(OPV, pOpv());
        patterns.put(CIRCUIT_HV, pCircuitHv());
        patterns.put(CIRCUIT_MV, pCircuitMv());
        patterns.put(IMPRINTED_BOARD, pImprintedBoard());
        patterns.put(RAW_IMPRINTED_BOARD, pRawImprintedBoard());
        patterns.put(MAGNETO_DUST, pMagnetoDust());
        patterns.put(PRASIOLITE_DUST, pPrasioliteDust());
        patterns.put(SILICON_DUST, pSiliconDust());
        patterns.put(OXYGEN, pOxygen());
        patterns.put(GTL_SYNTHETIC, pGtlSynthetic());
        patterns.put(SMD_COSMIC, pSmdCosmic());
        // NOTE: LIQUID_AIR pattern is ABSENT initially.

        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(SILICON_DUST, 64000L);
        stock.put(OXYGEN, 640000L);
        stock.put(SMD_COSMIC, 960L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (var p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OPV), 4);

        CraftingVM vm = new CraftingVM("gtl-missing-intermediate", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });

        // Step 1: order OPV -> LIQUID_AIR missing (no pattern yet).
        ICraftingPlan plan1 = vm.execute(req, new StockSimState(stock));
        assertTrue(hasMissing(plan1, LIQUID_AIR),
            "Step 1: LIQUID_AIR should be missing. missing=" + missing(plan1));

        // Step 2: user adds the intermediate pattern.
        PatternCompiler.clearCache();
        patterns.put(LIQUID_AIR, pLiquidAir());
        PatternCompiler.compileIfAbsent(patterns.get(LIQUID_AIR));
        assertNotNull(patterns.get(LIQUID_AIR));

        // Step 3: retry fix -- bump version, invalidate root, recompile, re-execute.
        PatternCompiler.bumpPatternVersion();
        PatternCompiler.invalidate(patterns.get(OPV));
        for (var p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        req = PatternCompiler.compileRequest(patterns.get(OPV), 4);

        ICraftingPlan plan2 = vm.execute(req, new StockSimState(stock));
        assertTrue(hasMissing(plan2, GTL_IMPOSSIBLE),
            "Step 3: GTL_IMPOSSIBLE missing. missing=" + missing(plan2));

        System.out.println("[gtl-missing-intermediate] step3 missing=" + missing(plan2));
    }

    // ====================================================================
    // SCENARIO C: Deep-chain reuse performance sanity floor
    // ====================================================================
    @Test
    void gtlDeepChainReusePerf() {
        int DEPTH = 12;
        int REQUESTS = 500;

        VariantKey[] OUT = new VariantKey[DEPTH];
        for (int i = 0; i < DEPTH; i++) OUT[i] = VariantKey.of("perf_out_" + i, "");
        VariantKey LEAF = VariantKey.of("perf_leaf", "");

        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        for (int i = 0; i < DEPTH - 1; i++) {
            patterns.put(OUT[i], simplePattern(OUT[i], List.of(new ExactInput(OUT[i + 1], 1))));
        }
        patterns.put(OUT[DEPTH - 1], simplePattern(OUT[DEPTH - 1], List.of(new ExactInput(LEAF, 1))));
        patterns.put(LEAF, simplePattern(LEAF, List.of()));

        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100_000L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (var p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OUT[0]), 1);

        CraftingVM vm = new CraftingVM("gtl-perf", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });

        ICraftingPlan warmup = vm.execute(req, new StockSimState(stock));
        assertEquals(0L, warmup.missingItems().size(), "warmup feasible");

        long start = System.nanoTime();
        for (int i = 0; i < REQUESTS; i++) {
            ICraftingPlan p = vm.execute(req, new StockSimState(stock));
            if (!p.missingItems().isEmpty()) {
                throw new AssertionError("request " + i + " should be feasible");
            }
        }
        long elapsedNs = System.nanoTime() - start;
        double avgMs = elapsedNs / 1_000_000.0 / REQUESTS;

        System.out.println("[gtl-perf] engine=ae2vm depth=" + DEPTH
            + " requests=" + REQUESTS
            + " avgMs=" + String.format("%.4f", avgMs)
            + " totalMs=" + String.format("%.2f", elapsedNs / 1_000_000.0));

        assertTrue(avgMs < 2.0,
            "deep-chain reuse avg " + avgMs + "ms exceeded 2ms sanity floor");
    }

    // ====================================================================
    // SCENARIO D: GTL synthetic pattern with different amounts
    // ====================================================================
    @Test
    void gtlSyntheticDifferentAmounts() {
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(OPV, pOpv());
        patterns.put(CIRCUIT_HV, pCircuitHv());
        patterns.put(CIRCUIT_MV, pCircuitMv());
        patterns.put(IMPRINTED_BOARD, pImprintedBoard());
        patterns.put(RAW_IMPRINTED_BOARD, pRawImprintedBoard());
        patterns.put(MAGNETO_DUST, pMagnetoDust());
        patterns.put(PRASIOLITE_DUST, pPrasioliteDust());
        patterns.put(SILICON_DUST, pSiliconDust());
        patterns.put(OXYGEN, pOxygen());
        patterns.put(GTL_SYNTHETIC, pGtlSynthetic());
        patterns.put(LIQUID_AIR, pLiquidAir());
        patterns.put(SMD_COSMIC, pSmdCosmic());

        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(SILICON_DUST, 64000L);
        stock.put(OXYGEN, 640000L);
        stock.put(LIQUID_AIR, 640000L);
        stock.put(SMD_COSMIC, 960L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (var p : patterns.values()) PatternCompiler.compileIfAbsent(p);

        CraftingVM vm = new CraftingVM("gtl-amounts", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });

        long[] amounts = {1, 4, 16, 64};
        for (long amt : amounts) {
            CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OPV), amt);
            ICraftingPlan plan = vm.execute(req, new StockSimState(stock));

            assertTrue(hasMissing(plan, GTL_IMPOSSIBLE),
                "Amount " + amt + ": GTL_IMPOSSIBLE missing. missing=" + missing(plan));

            long nonGtlMissing = 0;
            for (var e : plan.missingItems()) {
                if (!e.getKey().equals(GTL_IMPOSSIBLE)) nonGtlMissing += e.getLongValue();
            }
            assertEquals(0L, nonGtlMissing,
                "Amount " + amt + ": non-GTL items missing. missing=" + missing(plan));

            System.out.println("[gtl-amounts] amt=" + amt + " missing=" + missing(plan));
        }
        System.out.println("[gtl-amounts] All amounts passed");
    }

    // ====================================================================
    // SCENARIO E: Cross-request determinism with GTL pattern present
    // ====================================================================
    @Test
    void gtlCrossRequestDeterminism() {
        Map<VariantKey, IPatternDetails> patterns = new HashMap<>();
        patterns.put(OPV, pOpv());
        patterns.put(CIRCUIT_HV, pCircuitHv());
        patterns.put(CIRCUIT_MV, pCircuitMv());
        patterns.put(IMPRINTED_BOARD, pImprintedBoard());
        patterns.put(RAW_IMPRINTED_BOARD, pRawImprintedBoard());
        patterns.put(MAGNETO_DUST, pMagnetoDust());
        patterns.put(PRASIOLITE_DUST, pPrasioliteDust());
        patterns.put(SILICON_DUST, pSiliconDust());
        patterns.put(OXYGEN, pOxygen());
        patterns.put(GTL_SYNTHETIC, pGtlSynthetic());
        patterns.put(LIQUID_AIR, pLiquidAir());
        patterns.put(SMD_COSMIC, pSmdCosmic());

        Map<VariantKey, Long> stock = new HashMap<>();
        stock.put(SILICON_DUST, 64000L);
        stock.put(OXYGEN, 640000L);
        stock.put(LIQUID_AIR, 640000L);
        stock.put(SMD_COSMIC, 960L);

        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (var p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OPV), 4);

        CraftingVM vm = new CraftingVM("gtl-determinism", key -> {
            VariantKey vk = (VariantKey) key;
            return patterns.get(vk);
        });

        List<ICraftingPlan> plans = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            plans.add(vm.execute(req, new StockSimState(stock)));
        }

        for (int i = 1; i < plans.size(); i++) {
            assertEquals(
                missing(plans.get(0)).toString(),
                missing(plans.get(i)).toString(),
                "Plan " + i + " missing differs from plan 0");
        }

        for (int i = 0; i < plans.size(); i++) {
            assertTrue(hasMissing(plans.get(i), GTL_IMPOSSIBLE),
                "Plan " + i + ": GTL_IMPOSSIBLE should be missing");
        }

        System.out.println("[gtl-determinism] All 10 plans identical");
    }

    // ====================================================================
    // helpers
    // ====================================================================

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

    private static IPatternDetails simplePattern(VariantKey out, List<IPatternDetails.IInput> inputs) {
        return new VPattern(out, 1, inputs);
    }

    // ====================================================================
    // minimal pattern/sim helpers (same as GTLStaleExtractReproTest)
    // ====================================================================

    private static final class VPattern implements IPatternDetails {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;
        VPattern(VariantKey out, long amount, List<IPatternDetails.IInput> inputList) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, amount)};
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

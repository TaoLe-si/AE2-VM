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

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * (v1.11.9 PERF) Quantifies the cost of staleMissingRecheck (forward missing + reverse
 * removed-pattern) on a deep chain with heavy bundle reuse. The concern: does the
 * per-reuse stale check add measurable overhead to normal crafting?
 *
 * <p>Design: a 20-level chain, all patterns present, sufficient stock → every request is
 * feasible. We reuse ONE CraftingVM across MANY requests, so every request after the first
 * reuses the cached bundles and runs staleMissingRecheck on them. The per-execute staleMemo
 * means the FULL recursive subtree check runs only ONCE per bundle per execute; all
 * subsequent reuses of the same bundle reference are O(1) memo hits.
 *
 * <p>We measure the average time per request and assert it stays bounded (a sanity floor,
 * not a benchmark claim) — the real signal is that adding the reverse check + memo did not
 * regress the existing JIT reuse path (all 173 functional tests still pass, and this
 * measures the steady-state reuse cost).
 */
public class StaleRecheckPerfTest {

    @Test
    void deepChainReusePerf() {
        int DEPTH = 20;
        int REQUESTS = 2000;
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
        stock.put(LEAF, 100_000L); // ample stock → every request feasible

        // Compile all patterns.
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OUT[0]), 1);

        // ONE reused VM — bundleCache + staleMemo persist across requests.
        CraftingVM vm = new CraftingVM("perf", key -> {
            if (key instanceof VariantKey vk) return patterns.get(vk);
            return null;
        });

        // Warmup: first request captures all bundles (excluded from timing).
        ICraftingPlan warmup = vm.execute(req, new StockSimState(stock));
        assertEquals(0L, warmup.missingItems().size(), "warmup feasible");

        // Measure steady-state reuse (all bundles cached, stale recheck runs per reuse).
        long start = System.nanoTime();
        for (int i = 0; i < REQUESTS; i++) {
            ICraftingPlan p = vm.execute(req, new StockSimState(stock));
            if (!p.missingItems().isEmpty()) {
                throw new AssertionError("request " + i + " should be feasible");
            }
        }
        long elapsedNs = System.nanoTime() - start;
        double avgUs = elapsedNs / 1_000_000.0 / REQUESTS;

        // Print the measurement (visible in test output).
        System.out.println("[stale-recheck-perf] engine=ae2vm depth=" + DEPTH
                + " requests=" + REQUESTS
                + " avgMs=" + String.format("%.4f", avgUs)
                + " totalMs=" + String.format("%.2f", elapsedNs / 1_000_000.0));

        // Sanity floor (not a hard benchmark): deep-chain reuse with stale recheck must
        // stay well under ~5ms/request on any modern machine. This guards against a future
        // change that accidentally turns the O(1) memo into an O(N×M) full re-walk.
        assertEquals(true, avgUs < 5.0,
                "deep-chain reused request avg " + avgUs + "ms exceeded 5ms sanity floor — "
                + "stale recheck may have regressed to a full per-reuse subtree walk");
    }

    // ---- minimal pattern helpers (same as DeepChainJITTest) ----

    private static IPatternDetails simplePattern(VariantKey out, List<IPatternDetails.IInput> inputs) {
        return new VPattern(out, 1, inputs);
    }

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

package com.ae2vm.addon.bench;

import com.ae2vm.shim.api.crafting.IPatternDetails;
import com.ae2vm.shim.api.networking.crafting.ICraftingPlan;
import com.ae2vm.shim.api.stacks.AEItemKey;
import com.ae2vm.shim.api.stacks.AEKey;
import com.ae2vm.shim.api.stacks.GenericStack;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import net.minecraft.world.World;
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
        AEKey[] OUT = new AEKey[DEPTH];
        for (int i = 0; i < DEPTH; i++) OUT[i] = VariantKey.of("perf_out_" + i, "");
        AEKey LEAF = VariantKey.of("perf_leaf", "");

        Map<AEKey, IPatternDetails> patterns = new HashMap<>();
        for (int i = 0; i < DEPTH - 1; i++) {
            patterns.put(OUT[i], simplePattern(OUT[i], J8.list(new ExactInput(OUT[i + 1], 1))));
        }
        patterns.put(OUT[DEPTH - 1], simplePattern(OUT[DEPTH - 1], J8.list(new ExactInput(LEAF, 1))));
        patterns.put(LEAF, simplePattern(LEAF, J8.list()));

        Map<AEKey, Long> stock = new HashMap<>();
        stock.put(LEAF, 100_000L); // ample stock → every request feasible

        // Compile all patterns.
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (IPatternDetails p : patterns.values()) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(patterns.get(OUT[0]), 1);

        // ONE reused VM — bundleCache + staleMemo persist across requests.
        CraftingVM vm = new CraftingVM("perf", key -> {
            if (key instanceof AEKey) return patterns.get((key));
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

    private static IPatternDetails simplePattern(AEKey out, List<IPatternDetails.IInput> inputs) {
        return new VPattern(out, 1, inputs);
    }

    private static final class VPattern implements IPatternDetails, BenchPatternAccess {
        private final IPatternDetails.IInput[] inputs;
        private final GenericStack[] outputs;
        VPattern(AEKey out, long amount, List<IPatternDetails.IInput> inputList) {
            this.inputs = inputList.toArray(new IPatternDetails.IInput[0]);
            this.outputs = new GenericStack[]{new GenericStack(out, amount)};
        }
        @Override public GenericStack[] benchOutputs() { return outputs; }
        @Override public IPatternDetails.IInput[] getInputs() { return inputs; }
        public AEItemKey getDefinition() { return null; }
    }

    private static final class ExactInput implements IPatternDetails.IInput, BenchInputAccess {
        private final GenericStack[] possible;
        ExactInput(AEKey key, long amount) {
            this.possible = new GenericStack[]{new GenericStack(key, amount)};
        }
        @Override public GenericStack[] benchPossibleInputs() { return possible; }
        @Override public long getMultiplier() { return 1; }
        public boolean isValid(AEKey input, net.minecraft.world.World level) {
            return input.equals(possible[0].what());
        }
        @Override public AEKey benchContainerItem(AEKey template) { return null; }
    }

    private static final class StockSimState extends com.ae2vm.shim.crafting.inv.CraftingSimulationState
            implements com.ae2vm.shim.crafting.inv.CraftingSimulationStateAccessor {
        private final Map<AEKey, Long> stock;
        StockSimState(Map<AEKey, Long> stock) { this.stock = stock; }
@Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(
                appeng.api.storage.data.IAEStack input) {
            return simulateExtractParent(input, appeng.api.config.Actionable.SIMULATE);
        }

        /**
         * 与 AE2 v15 的 CraftingSimulationState.extract 同语义：沙箱是一份会被抽干的库存，
         * MODULATE 必须扣减（注入入账 + 抽取扣减同时成立，否则同一份库存会被再借一次）。
         */
        @Override
        protected appeng.api.storage.data.IAEStack simulateExtractParent(
                appeng.api.storage.data.IAEStack input, appeng.api.config.Actionable mode) {
            com.ae2vm.shim.api.stacks.AEKey k = asBenchKey(input);
            Long have = k == null ? null : stock.get(k);
            if (have == null || have.longValue() <= 0L) {
                return null;
            }
            long take = Math.min(input.getStackSize(), have.longValue());
            if (take <= 0L) {
                return null;
            }
            if (mode == appeng.api.config.Actionable.MODULATE) {
                stock.put(k, Long.valueOf(have.longValue() - take));
            }
            appeng.api.storage.data.IAEStack got = input.copy();
            got.setStackSize(take);
            return got;
        }

        @Override
        protected java.util.Collection<appeng.api.storage.data.IAEStack> findFuzzyParent(appeng.api.storage.data.IAEStack input) {
            com.ae2vm.shim.api.stacks.AEKey k = asBenchKey(input);
            java.util.List<appeng.api.storage.data.IAEStack> out =
                    new java.util.ArrayList<appeng.api.storage.data.IAEStack>();
            if (k == null) {
                return out;
            }
            for (java.util.Map.Entry<AEKey, Long> e : stock.entrySet()) {
                if (e.getValue() == null || e.getValue().longValue() <= 0L) {
                    continue;
                }
                if (e.getKey() != null && e.getKey().getItem() == k.getItem()) {
                    out.add(e.getKey().toStack(e.getValue().longValue()));
                }
            }
            return out;
        }

        /** IAEStack -> 可作为 stock 键的 AEItemKey（identity = 物品+damage，不含数量）。 */
        private static AEKey asBenchKey(appeng.api.storage.data.IAEStack stack) {
            if (!(stack instanceof appeng.api.storage.data.IAEItemStack)) {
                return null;
            }
            return com.ae2vm.shim.api.stacks.AEItemKey.wrap((appeng.api.storage.data.IAEItemStack) stack);
        }

        @Override
        public double getBytes() {
            try {
                java.lang.reflect.Field f = com.ae2vm.shim.crafting.inv.CraftingSimulationState.class.getDeclaredField("bytes");
                f.setAccessible(true);
                return f.getDouble(this);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
    }
}

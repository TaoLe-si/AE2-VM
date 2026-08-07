package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Investigates the user report (NAST 1.21.1): "合成 1 个/1b 缺失无法合成，但 2 个/100b
 * 正常" — requesting ONE of an item (e.g. singularity_generator_1m, core_1m) reports
 * a missing ingredient even though the ingredients are craftable / stocked, while
 * requesting 2+ succeeds. This is a 1-craft capture boundary bug (rootCraftTimes=1).
 */
public class QuantityOneBoundaryTest {

    /** X_i = X_{i-1} + X_{i-2} Fibonacci chain, leaves stocked. */
    private static final class FibFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();
        final int levels;

        FibFixture(int levels) {
            this.levels = levels;
            BenchAEKey[] keys = new BenchAEKey[levels];
            for (int i = 0; i < levels; i++) keys[i] = BenchAEKey.of("X" + i);
            for (int i = 2; i < levels; i++) {
                byOutput.put(keys[i], new BenchPatternDetails(keys[i], 1, List.of(
                        BenchPatternDetails.InputSpec.of(keys[i - 1], 1),
                        BenchPatternDetails.InputSpec.of(keys[i - 2], 1))));
            }
            stock.put(BenchAEKey.of("X0"), 1000L);
            stock.put(BenchAEKey.of("X1"), 1000L);
        }

        ICraftingPlan run(long amount) {
            PatternCompiler.clearCache();
            IPatternDetails top = byOutput.get(BenchAEKey.of("X" + (levels - 1)));
            PatternCompiler.compileIfAbsent(top);
            CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
            CraftingVM vm = new CraftingVM("q1-bench", byOutput::get);
            return vm.execute(req, new BenchSimulationState(stock));
        }
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    @Test
    void fibOneVsTwoNoFalseMissing() {
        FibFixture fx = new FibFixture(12);
        ICraftingPlan p1 = fx.run(1);
        ICraftingPlan p2 = fx.run(2);
        System.out.println("[Q1] fib12 x1 missing=" + missing(p1));
        System.out.println("[Q1] fib12 x2 missing=" + missing(p2));
        org.junit.jupiter.api.Assertions.assertTrue(p1.missingItems().isEmpty(),
                "request 1 must be feasible, missing=" + missing(p1));
        org.junit.jupiter.api.Assertions.assertTrue(p2.missingItems().isEmpty(),
                "request 2 must be feasible, missing=" + missing(p2));
    }

    /** Single step A <- B (B craftable from stocked C,D) + B stocked partially. */
    private static final class PartialStockFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();
        PartialStockFixture() {
            BenchAEKey A = BenchAEKey.of("A");
            BenchAEKey B = BenchAEKey.of("B");
            BenchAEKey C = BenchAEKey.of("C");
            BenchAEKey D = BenchAEKey.of("D");
            byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1))));
            byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                    BenchPatternDetails.InputSpec.of(C, 1),
                    BenchPatternDetails.InputSpec.of(D, 1))));
            stock.put(BenchAEKey.of("B"), 1L); // partial: 1 B stocked, rest crafted
            stock.put(BenchAEKey.of("C"), 32L);
            stock.put(BenchAEKey.of("D"), 32L);
        }
        ICraftingPlan run(long amount) {
            PatternCompiler.clearCache();
            IPatternDetails top = byOutput.get(BenchAEKey.of("A"));
            PatternCompiler.compileIfAbsent(top);
            CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
            CraftingVM vm = new CraftingVM("q1-bench", byOutput::get);
            return vm.execute(req, new BenchSimulationState(stock));
        }
    }

    @Test
    void partialStockOneVsTwo() {
        PartialStockFixture fx = new PartialStockFixture();
        ICraftingPlan p1 = fx.run(1);
        ICraftingPlan p2 = fx.run(2);
        System.out.println("[Q1] partial x1 missing=" + missing(p1));
        System.out.println("[Q1] partial x2 missing=" + missing(p2));
        org.junit.jupiter.api.Assertions.assertTrue(p1.missingItems().isEmpty(),
                "request 1 must be feasible, missing=" + missing(p1));
        org.junit.jupiter.api.Assertions.assertTrue(p2.missingItems().isEmpty(),
                "request 2 must be feasible, missing=" + missing(p2));
    }
}

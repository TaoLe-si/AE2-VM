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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the player report (1.20.1 server, vm 1.9.1): "合成一个物品或液体，
 * 一个或一b缺失物品无法合成，但换成2个或100b就可以" — requesting ONE item (or 1
 * bucket = 1000 mB of fluid) reports a missing ingredient even though ingredients are
 * craftable/stocked, while requesting 2+ (or 100b) succeeds. The boundary is
 * rootCraftTimes == 1 in the aggregation / stock-aware sub-craft path.
 */
public class FluidBucketBoundaryTest {

    /** A <- B + FLUID; FLUID <- C x1000 (produces 1000 mB/craft); C stocked. */
    private static final class FluidFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();

        FluidFixture() {
            BenchAEKey A = BenchAEKey.of("A");
            BenchAEKey B = BenchAEKey.of("B");
            BenchAEKey FLUID = BenchAEKey.of("fluid");
            BenchAEKey C = BenchAEKey.of("C");
            byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1),
                    BenchPatternDetails.InputSpec.of(FLUID, 1000)))); // 1 bucket fluid per craft
            byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                    BenchPatternDetails.InputSpec.of(C, 1))));
            byOutput.put(FLUID, new BenchPatternDetails(FLUID, 1000, List.of(
                    BenchPatternDetails.InputSpec.of(C, 1)))); // 1000 mB fluid from 1 C
            stock.put(BenchAEKey.of("C"), 10000L);
            stock.put(BenchAEKey.of("B"), 5L);
        }

        ICraftingPlan run(long amount) {
            PatternCompiler.clearCache();
            IPatternDetails top = byOutput.get(BenchAEKey.of("A"));
            PatternCompiler.compileIfAbsent(top);
            CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
            CraftingVM vm = new CraftingVM("fluid-bench", byOutput::get);
            return vm.execute(req, new BenchSimulationState(stock));
        }
    }

    /** A <- B + FLUID(1000) with FLUID NOT craftable but STOCKED partially (5000 mB). */
    private static final class FluidStockedFixture {
        final Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        final Map<BenchAEKey, Long> stock = new HashMap<>();

        FluidStockedFixture() {
            BenchAEKey A = BenchAEKey.of("A");
            BenchAEKey B = BenchAEKey.of("B");
            BenchAEKey FLUID = BenchAEKey.of("fluid");
            BenchAEKey C = BenchAEKey.of("C");
            byOutput.put(A, new BenchPatternDetails(A, 1, List.of(
                    BenchPatternDetails.InputSpec.of(B, 1),
                    BenchPatternDetails.InputSpec.of(FLUID, 1000))));
            byOutput.put(B, new BenchPatternDetails(B, 1, List.of(
                    BenchPatternDetails.InputSpec.of(C, 1))));
            // FLUID has no pattern → leaf, stock 5000 mB
            stock.put(BenchAEKey.of("FLUID"), 0L);
            stock.put(BenchAEKey.of("fluid"), 5000L);
            stock.put(BenchAEKey.of("B"), 5L);
            stock.put(BenchAEKey.of("C"), 10000L);
        }

        ICraftingPlan run(long amount) {
            PatternCompiler.clearCache();
            IPatternDetails top = byOutput.get(BenchAEKey.of("A"));
            PatternCompiler.compileIfAbsent(top);
            CraftingBytecode req = PatternCompiler.compileRequest(top, amount);
            CraftingVM vm = new CraftingVM("fluid-bench", byOutput::get);
            return vm.execute(req, new BenchSimulationState(stock));
        }
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    @Test
    void craftableFluidOneVsTwo() {
        FluidFixture fx = new FluidFixture();
        ICraftingPlan p1 = fx.run(1);
        ICraftingPlan p2 = fx.run(2);
        System.out.println("[FB] craftableFluid x1 missing=" + missing(p1));
        System.out.println("[FB] craftableFluid x2 missing=" + missing(p2));
        assertTrue(p1.missingItems().isEmpty(), "x1 must be feasible, missing=" + missing(p1));
        assertTrue(p2.missingItems().isEmpty(), "x2 must be feasible, missing=" + missing(p2));
    }

    @Test
    void stockedFluidOneVsTwo() {
        FluidStockedFixture fx = new FluidStockedFixture();
        ICraftingPlan p1 = fx.run(1);
        ICraftingPlan p2 = fx.run(2);
        System.out.println("[FB] stockedFluid x1 missing=" + missing(p1));
        System.out.println("[FB] stockedFluid x2 missing=" + missing(p2));
        assertTrue(p1.missingItems().isEmpty(), "x1 must be feasible, missing=" + missing(p1));
        assertTrue(p2.missingItems().isEmpty(), "x2 must be feasible, missing=" + missing(p2));
    }
}

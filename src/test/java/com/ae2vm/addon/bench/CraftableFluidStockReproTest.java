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
import java.util.function.Function;

/**
 * Exact reported scenario (GT Lite): the blank pattern's last step is an
 * assembler that consumes a FLUID which is itself produced by a sub-craft, and
 * the network has PARTIAL stock of that fluid. The plan must still schedule the
 * fluid sub-craft for the deficit AND mark the stocked fluid as network-used, so
 * the CPU can push the assembler pattern N times — including the LAST one.
 *
 *   BLANK (assembler, last) = BOARD x1 + FLUID x1000
 *   BOARD (machine)         = RAW x1
 *   FLUID (machine)         = WATER x1  -> produces 1000 FLUID per craft
 *
 * Network: WATER stock large, FLUID stock partial (0..3 crafts' worth).
 */
public class CraftableFluidStockReproTest {

    private static final long FLUID_PER_CRAFT = 1000L;

    @Test
    public void reproCraftableFluidWithStock() {
        for (long n : new long[] { 1L, 2L, 3L, 4L, 5L }) {
            for (long fluidStock : new long[] { 0L, 1000L, 2000L, 3000L, 10000L }) {
                runCase(n, fluidStock);
            }
        }
    }

    private static void runCase(long n, long fluidStock) {
        BenchAEKey BLANK = BenchAEKey.of("blank_pattern");
        BenchAEKey BOARD = BenchAEKey.of("circuit_board");
        BenchAEKey FLUID = BenchAEKey.of("fluid_x");
        BenchAEKey RAW = BenchAEKey.of("raw");
        BenchAEKey WATER = BenchAEKey.of("water");

        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(BLANK, new BenchPatternDetails(BLANK, 1, List.of(
                BenchPatternDetails.InputSpec.of(BOARD, 1),
                BenchPatternDetails.InputSpec.of(FLUID, FLUID_PER_CRAFT))));
        byOutput.put(BOARD, new BenchPatternDetails(BOARD, 1, List.of(
                BenchPatternDetails.InputSpec.of(RAW, 1))));
        byOutput.put(FLUID, new BenchPatternDetails(FLUID, FLUID_PER_CRAFT, List.of(
                BenchPatternDetails.InputSpec.of(WATER, 1))));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(RAW, 1_000_000L);
        stock.put(WATER, 1_000_000L);
        stock.put(FLUID, fluidStock);

        Function<AEKey, IPatternDetails> resolver = byOutput::get;

        PatternCompiler.clearCache();
        IPatternDetails top = byOutput.get(BLANK);
        PatternCompiler.compileIfAbsent(top);
        CraftingBytecode req = PatternCompiler.compileRequest(top, n);

        FakeBenchGrid grid = new FakeBenchGrid(new HashMap<>(stock));
        CraftingVM vm = new CraftingVM(grid, resolver);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));

        long blankTimes = plan.patternTimes().getOrDefault(top, 0L);
        long fluidCraft = timesFor(plan, FLUID);
        long fluidUsed = plan.usedItems().get(FLUID);
        long fluidAvailable = fluidUsed + fluidCraft * FLUID_PER_CRAFT;

        System.out.println("[CFLUID] n=" + n + " fluidStock=" + fluidStock
                + " -> blank=" + blankTimes
                + " fluidCraft=" + fluidCraft
                + " used[fluid]=" + fluidUsed
                + " available=" + fluidAvailable
                + " missing=" + plan.missingItems()
                + " sim=" + plan.simulation());

        // The assembler must run exactly n times.
        org.junit.jupiter.api.Assertions.assertEquals(n, blankTimes,
                "n=" + n + ": patternTimes[blank] off-by-one!");
        // Stock + crafted fluid must cover all n crafts.
        org.junit.jupiter.api.Assertions.assertTrue(fluidAvailable >= n * FLUID_PER_CRAFT,
                "n=" + n + " fluidStock=" + fluidStock
                        + ": only " + fluidAvailable + " fluid scheduled (used=" + fluidUsed
                        + " craft=" + fluidCraft + ") < " + (n * FLUID_PER_CRAFT)
                        + " -> last craft will NOT get its fluid!");
    }

    private static long timesFor(ICraftingPlan plan, BenchAEKey output) {
        for (var e : plan.patternTimes().entrySet()) {
            if (e.getKey() instanceof BenchPatternDetails d
                    && d.getPrimaryOutput() != null
                    && d.getPrimaryOutput().what().equals(output)) {
                return e.getValue();
            }
        }
        return 0L;
    }
}

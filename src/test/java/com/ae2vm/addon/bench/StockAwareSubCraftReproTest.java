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
 * Reproduces the reported bug through the REAL stock-aware sub-craft path
 * (v1.8.22) using a fake {@link IGrid} so {@code realStockOf} sees network stock.
 *
 * GT Lite blank pattern chain (last step = assembler with a fluid):
 *   BLANK (assembler, last) = BOARD x1 + FLUID x1000
 *   BOARD (machine)         = RAW x1
 *
 * The network has PARTIAL stock of the sub-item (BOARD) and/or the fluid.
 * Expectation: plan must schedule EXACTLY N BLANK crafts, N (or deficit) BOARD
 * crafts, and usedItems[FLUID] = 1000 * N (the CPU must receive the fluid for
 * EVERY craft, including the last one).
 */
public class StockAwareSubCraftReproTest {

    private static final long FLUID_PER_CRAFT = 1000L;

    @Test
    public void reproPartialSubItemStock() {
        for (long n : new long[] { 1L, 2L, 3L, 4L, 5L }) {
            for (long boardStock : new long[] { 0L, 1L, 2L, 3L, 4L, 5L, 10L }) {
                runCase("board-stock", n, boardStock, 1_000_000L);
            }
        }
    }

    @Test
    public void reproPartialFluidStock() {
        for (long n : new long[] { 1L, 2L, 3L, 4L, 5L }) {
            for (long fluidStock : new long[] { 0L, 999L, 1000L, 1500L, 2500L, 5000L, 100000L }) {
                runCase("fluid-stock", n, 0L, fluidStock);
            }
        }
    }

    private static void runCase(String tag, long n, long boardStock, long fluidStock) {
        BenchAEKey BLANK = BenchAEKey.of("blank_pattern");
        BenchAEKey BOARD = BenchAEKey.of("circuit_board");
        BenchAEKey FLUID = BenchAEKey.of("fluid_x");
        BenchAEKey RAW = BenchAEKey.of("raw");

        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(BLANK, new BenchPatternDetails(BLANK, 1, List.of(
                BenchPatternDetails.InputSpec.of(BOARD, 1),
                BenchPatternDetails.InputSpec.of(FLUID, FLUID_PER_CRAFT))));
        byOutput.put(BOARD, new BenchPatternDetails(BOARD, 1, List.of(
                BenchPatternDetails.InputSpec.of(RAW, 1))));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(RAW, 1_000_000L);
        stock.put(BOARD, boardStock);
        stock.put(FLUID, fluidStock);

        Function<AEKey, IPatternDetails> resolver = byOutput::get;

        PatternCompiler.clearCache();
        IPatternDetails top = byOutput.get(BLANK);
        PatternCompiler.compileIfAbsent(top);
        CraftingBytecode req = PatternCompiler.compileRequest(top, n);

        // IMPORTANT: use a real IGrid so realStockOf() sees the partial stock.
        FakeBenchGrid grid = new FakeBenchGrid(new HashMap<>(stock));
        CraftingVM vm = new CraftingVM(grid, resolver);
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(stock));

        long blankTimes = plan.patternTimes().getOrDefault(top, 0L);
        long boardTimes = timesFor(plan, BOARD);
        long fluidUsed = plan.usedItems().get(FLUID);
        long boardUsed = plan.usedItems().get(BOARD);

        var missingStr = new StringBuilder();
        for (var e : plan.missingItems()) {
            missingStr.append(" [").append(e.getLongValue()).append("x").append(e.getKey()).append("]");
        }

        System.out.println("[STOCK] " + tag + " n=" + n + " boardStock=" + boardStock
                + " fluidStock=" + fluidStock
                + " -> blank=" + blankTimes
                + " board=" + boardTimes
                + " used[fluid]=" + fluidUsed
                + " used[board]=" + boardUsed
                + " missing=" + missingStr
                + " sim=" + plan.simulation());

        // The assembler (last step) must run exactly n times.
        org.junit.jupiter.api.Assertions.assertEquals(n, blankTimes,
                tag + " n=" + n + " boardStock=" + boardStock + ": patternTimes[blank] off-by-one!");

        // Every craft of BLANK needs its fluid, so when the network has enough fluid
        // the plan must include 1000*N of it. "最后一份不发送物品" happens when
        // usedItems[fluid] < 1000*N despite enough stock.
        if (fluidStock >= n * FLUID_PER_CRAFT) {
            org.junit.jupiter.api.Assertions.assertTrue(fluidUsed >= n * FLUID_PER_CRAFT,
                    tag + " n=" + n + " boardStock=" + boardStock + " fluidStock=" + fluidStock
                            + ": used[fluid]=" + fluidUsed + " < " + (n * FLUID_PER_CRAFT)
                            + " -> CPU will NOT send fluid for the last craft!");
        }

        // The CPU must be able to push the assembler n times: used[board] (from
        // network) + crafted boards must cover the n crafts. The bug under-counted
        // used[board] when the network had board stock, starving the LAST craft.
        long boardAvailable = boardUsed + boardTimes; // each BOARD craft yields 1 board
        if (plan.missingItems().isEmpty()) {
            org.junit.jupiter.api.Assertions.assertTrue(boardAvailable >= n,
                    tag + " n=" + n + " boardStock=" + boardStock
                            + ": only " + boardAvailable + " boards scheduled (used=" + boardUsed
                            + " craft=" + boardTimes + ") < " + n
                            + " -> last craft will NOT get its board!");
        }
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

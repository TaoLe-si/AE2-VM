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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the UselessMod 翻倍样板 (smart-doubling) compatibility fix.
 *
 * <p>UselessMod's advanced alloy furnace exposes a VIRTUAL scaled pattern
 * ({@link ScaledBenchPatternDetails}, standing in for {@code ScaledProcessingPattern})
 * that wraps the real pattern and multiplies every input multiplier / output amount by
 * {@code operationsPerPush}. Because it is a runtime wrapper, it is NOT part of the
 * pattern-provider list that the VM's {@code updatePatterns} pass compiles — the
 * 2026-08-10 chat diagnosis: "翻倍后的样板没被识别到，没被编译成字节码。因为翻倍样板是虚拟的，
 * 所以没在样板更新的时候匹配到" / "所有翻倍现在都不兼容".
 *
 * <p>The fix makes {@link PatternCompiler} UNWRAP the virtual scaled wrapper at every
 * entry point ({@code compileIfAbsent}/{@code getCompiled}/{@code compileRequest}) and
 * compile the ORIGINAL pattern. Consequences:
 * <ul>
 *   <li>{@code outputPerCraft} stays the ORIGINAL per-craft amount (1), so
 *       {@code compileRequest} counts ORIGINAL crafts (ceil(request/1)) — mathematically
 *       equivalent consumption, and UselessMod re-applies smart-doubling at submit time
 *       because the plan's {@code patternTimes} keys are real (unscaled) patterns;</li>
 *   <li>{@code patternTimes} keys are the ORIGINAL {@link IPatternDetails} instances that
 *       AE2's CPU / {@code getProviders} / furnace {@code pushPattern} all recognise — no
 *       stall from a plan keyed on a virtual wrapper.</li>
 * </ul>
 */
public class ScaledPatternReproTest {

    // orange = 1 sand + 1 dye → 1 orange (original); scaled ×4 = 4 sand + 4 dye → 4 orange
    private static final BenchAEKey ORANGE = BenchAEKey.of("orange");
    private static final BenchAEKey SAND = BenchAEKey.of("sand");
    private static final BenchAEKey DYE = BenchAEKey.of("dye");

    private static BenchPatternDetails originalOrange() {
        return new BenchPatternDetails(ORANGE, 1, List.of(
                BenchPatternDetails.InputSpec.of(SAND, 1),
                BenchPatternDetails.InputSpec.of(DYE, 1)));
    }

    private static ICraftingPlan run(BenchAEKey target,
                                     Map<AEKey, IPatternDetails> byOutput,
                                     Map<BenchAEKey, Long> stock,
                                     long amount) {
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

    private static long used(ICraftingPlan plan, AEKey key) {
        return plan.usedItems().get(key);
    }

    private static long missing(ICraftingPlan plan, AEKey key) {
        return plan.missingItems().get(key);
    }

    /**
     * THE BUG: a scaled (virtual) pattern must be compiled as its ORIGINAL — the bytecode
     * must carry the original per-craft output (1), not the scaled amount (4). Otherwise
     * {@code compileRequest} counts scaled crafts and the plan is keyed on the virtual
     * wrapper UselessMod/AE2 cannot execute.
     */
    @Test
    void scaledPatternCompilesAsOriginalNotScaled() {
        BenchPatternDetails original = originalOrange();
        ScaledBenchPatternDetails scaled = new ScaledBenchPatternDetails(original, 4);

        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(scaled);

        CraftingBytecode bc = PatternCompiler.getCompiled(scaled);
        assertNotNull(bc, "scaled pattern must compile to bytecode (翻倍样板必须被编译成字节码)");
        assertEquals(1, bc.getOutputAmountPerCraft(),
                "scaled pattern must compile as the ORIGINAL (per-craft output = 1, not 4)");
    }

    /**
     * Nested scaled wrappers (×3 then ×2 = ×6) must recursively unwrap to the original.
     */
    @Test
    void nestedScaledPatternUnwrapsToOriginal() {
        BenchPatternDetails original = originalOrange();
        ScaledBenchPatternDetails inner = new ScaledBenchPatternDetails(original, 3);
        ScaledBenchPatternDetails outer = new ScaledBenchPatternDetails(inner, 2);

        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(outer);

        CraftingBytecode bc = PatternCompiler.getCompiled(outer);
        assertNotNull(bc, "nested scaled pattern must compile");
        assertEquals(1, bc.getOutputAmountPerCraft(),
                "nested scaled pattern must unwrap recursively to the original (output=1)");
    }

    /**
     * A scaled pattern must craft the right TOTAL consumption: request 8 orange via the
     * ×4 wrapper → 8 sand + 8 dye, feasible, no missing.
     */
    @Test
    void scaledPatternCraftsCorrectConsumption() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, new ScaledBenchPatternDetails(originalOrange(), 4));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 1000L);
        stock.put(DYE, 1000L);

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 8);
        assertTrue(plan.missingItems().isEmpty(),
                "scaled craft must be feasible, missing=" + plan.missingItems());
        assertEquals(8, used(plan, SAND), "must consume 8 sand for 8 orange");
        assertEquals(8, used(plan, DYE), "must consume 8 dye for 8 orange");
    }

    /**
     * Non-multiple request (3 orange): after unwrapping, the VM counts 3 ORIGINAL crafts
     * (consuming 3 sand + 3 dye). Before the fix it counted 1 scaled craft (4 sand + 4 dye),
     * i.e. it planned against the scaled output amount.
     */
    @Test
    void scaledPatternNonMultipleCountsOriginalCrafts() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, new ScaledBenchPatternDetails(originalOrange(), 4));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 1000L);
        stock.put(DYE, 1000L);

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 3);
        assertTrue(plan.missingItems().isEmpty(),
                "scaled non-multiple craft must be feasible, missing=" + plan.missingItems());
        assertEquals(3, used(plan, SAND),
                "must count 3 ORIGINAL crafts (3 sand), not 1 scaled craft (4 sand)");
        assertEquals(3, used(plan, DYE),
                "must count 3 ORIGINAL crafts (3 dye), not 1 scaled craft (4 dye)");
    }

    /**
     * THE PLAN KEY: after execution, {@code patternTimes} must be keyed on the ORIGINAL
     * pattern (a real AE2 {@link IPatternDetails} that CPU/getProviders/furnace recognise),
     * never on the virtual {@link ScaledBenchPatternDetails} wrapper.
     */
    @Test
    void scaledPatternTimesKeyIsOriginalPattern() {
        BenchPatternDetails original = originalOrange();
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, new ScaledBenchPatternDetails(original, 4));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 1000L);
        stock.put(DYE, 1000L);

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 8);
        assertTrue(plan.missingItems().isEmpty());
        assertEquals(1, plan.patternTimes().size(),
                "plan must fire exactly one pattern (the original orange pattern)");
        for (var entry : plan.patternTimes().entrySet()) {
            assertTrue(entry.getKey() instanceof BenchPatternDetails,
                    "patternTimes key must be the ORIGINAL pattern, got: " + entry.getKey().getClass().getName());
            assertEquals(ORANGE, entry.getKey().getOutputs().get(0).what());
        }
    }

    /**
     * A scaled pattern whose leaf input is missing must report the missing leaf with the
     * correct (original-unit) amount.
     */
    @Test
    void scaledPatternReportsMissingLeaf() {
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(ORANGE, new ScaledBenchPatternDetails(originalOrange(), 4));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(SAND, 1000L); // dye missing entirely

        ICraftingPlan plan = run(ORANGE, byOutput, stock, 8);
        assertEquals(8, missing(plan, DYE),
                "must report the missing dye leaf in original units (8 for request 8)");
    }
}

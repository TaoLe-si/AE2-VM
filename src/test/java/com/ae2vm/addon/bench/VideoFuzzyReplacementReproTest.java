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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the 2026-08-09 video bug ("看着还是像合成替换的问题"):
 * a large AE2 Lightning Tech craft and a simple steel-casing craft both got STUCK in
 * the Crafting CPU (zero progress → absurd ETA → status collapses). Disabling the VM
 * made it go away, so the VM's plan was not executable by AE2's CPU execution: some
 * pattern in {@code patternTimes} could never be pushed because its input was not
 * extractable.
 *
 * <p>ROOT CAUSE (fixed in v1.10.5): the fuzzy / item-replacement group is registered
 * GLOBALLY per key. Two places applied it to demand from EXACT slots (single possible
 * input, no replacement) that can only ever use their primary key at AE2 execution:
 * <ol>
 *   <li>the stock-aware aggregation consumed substitute-variant stock to satisfy ALL of
 *       a child's demand — an exact-slot parent then found no primary in the plan's
 *       usedItems and could never be pushed (craftable-child stall);</li>
 *   <li>the CALL_BY_KEY leaf availability check suppressed missing for an exact slot
 *       because an UNRELATED pattern registered the substitute group (leaf-child
 *       stall).</li>
 * </ol>
 * The fix tracks which demand comes from replacement-ENABLED slots (FUZZY_SLOT marker +
 * {@code fuzzyItemNeeds}/{@code fuzzyItemDemand}) and only lets substitute-variant
 * stock satisfy that portion; same-item NBT variants (processing default fuzzy) remain
 * usable by any slot.
 */
public class VideoFuzzyReplacementReproTest {

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

    private static long timesFor(ICraftingPlan plan, AEKey key) {
        for (var e : plan.patternTimes().entrySet()) {
            for (var gs : e.getKey().getOutputs()) {
                if (gs.what().equals(key)) {
                    return e.getValue();
                }
            }
        }
        return 0;
    }

    private static long used(ICraftingPlan plan, AEKey key) {
        return plan.usedItems().get(key);
    }

    private static long missing(ICraftingPlan plan, AEKey key) {
        return plan.missingItems().get(key);
    }

    /** Shared fixture keys. */
    private record Keys(BenchAEKey target, BenchAEKey exactComp, BenchAEKey fuzzyComp,
                        BenchAEKey gray, BenchAEKey white, BenchAEKey black) {
    }

    private static Keys keys() {
        return new Keys(
                BenchAEKey.of("target"), BenchAEKey.of("exact_comp"), BenchAEKey.of("fuzzy_comp"),
                BenchAEKey.of("gray"), BenchAEKey.of("white"), BenchAEKey.of("black"));
    }

    /**
     * THE VIDEO BUG (craftable child): an EXACT slot and a fuzzy slot both consume the
     * same craftable child (gray). The global fuzzy group used to let the aggregation
     * satisfy the EXACT slot with the substitute (white), so no gray was crafted and the
     * exact pattern could never be pushed at AE2 execution → the CPU stalled at zero
     * progress. The plan must craft gray for the exact slot and use white only for the
     * fuzzy slot.
     */
    @Test
    void exactSlotForcesCraftOfPrimaryEvenWhenSubstituteStocked() {
        Keys k = keys();
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(k.target(), new BenchPatternDetails(k.target(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.exactComp(), 1),
                BenchPatternDetails.InputSpec.of(k.fuzzyComp(), 1))));
        byOutput.put(k.exactComp(), new BenchPatternDetails(k.exactComp(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.gray(), 1))));
        byOutput.put(k.fuzzyComp(), new BenchPatternDetails(k.fuzzyComp(), 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(k.gray(), 1, k.white()))));
        byOutput.put(k.gray(), new BenchPatternDetails(k.gray(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.black(), 1))));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(k.white(), 1000L);  // enough to (wrongly) cover BOTH slots
        stock.put(k.black(), 100_000L);

        for (long amount : new long[] { 1L, 2L, 10L, 100L, 1000L }) {
            ICraftingPlan plan = run(k.target(), byOutput, stock, amount);
            assertTrue(plan.missingItems().isEmpty(),
                    "amount=" + amount + ": plan must be feasible, missing=" + plan.missingItems());
            // The EXACT slot's whole demand must be crafted as the primary gray…
            assertEquals(amount, timesFor(plan, k.gray()),
                    "amount=" + amount + ": gray must be crafted for the EXACT slot (no stall)");
            // …the FUZZY slot's demand uses the substitute white.
            assertEquals(amount, used(plan, k.white()),
                    "amount=" + amount + ": white must satisfy exactly the fuzzy slot");
            // The gray craft needs black.
            assertEquals(amount, used(plan, k.black()),
                    "amount=" + amount + ": black feeds the crafted gray");
        }
    }

    /**
     * Leaf variant: the child has NO pattern. The exact slot must report the primary as
     * MISSING (job refused, not silently feasible → stall). The fuzzy slot still uses the
     * substitute.
     */
    @Test
    void exactLeafSlotReportsMissingNotStall() {
        Keys k = keys();
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(k.target(), new BenchPatternDetails(k.target(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.exactComp(), 1),
                BenchPatternDetails.InputSpec.of(k.fuzzyComp(), 1))));
        byOutput.put(k.exactComp(), new BenchPatternDetails(k.exactComp(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.gray(), 1))));
        byOutput.put(k.fuzzyComp(), new BenchPatternDetails(k.fuzzyComp(), 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(k.gray(), 1, k.white()))));
        // NOTE: gray has NO pattern here (leaf)

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(k.white(), 1000L);

        for (long amount : new long[] { 1L, 10L, 100L }) {
            ICraftingPlan plan = run(k.target(), byOutput, stock, amount);
            // The exact slot cannot be satisfied (gray not stocked, not craftable) — the
            // plan must report the missing primary so the job is refused, NOT stall.
            assertEquals(amount, missing(plan, k.gray()),
                    "amount=" + amount + ": exact slot must report missing gray");
        }
    }

    /**
     * Baseline: a single FUZZY leaf slot (gray↔white, gray NOT craftable) is satisfied
     * by the stocked substitute white — no missing.
     */
    @Test
    void singleFuzzyLeafUsesSubstitute() {
        Keys k = keys();
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(k.target(), new BenchPatternDetails(k.target(), 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(k.gray(), 1, k.white()))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(k.white(), 1000L);

        ICraftingPlan plan = run(k.target(), byOutput, stock, 100);
        assertTrue(plan.missingItems().isEmpty(), "missing=" + plan.missingItems());
        assertEquals(100, used(plan, k.white()));
    }

    /**
     * Baseline: a single FUZZY craftable slot (gray craftable ← black, white=1 stocked)
     * uses the substitute for the 1 unit it covers, and crafts gray for the deficit —
     * the v1.9.13 behavior must be preserved.
     */
    @Test
    void singleFuzzyCraftableStillUsesSubstituteForDeficit() {
        Keys k = keys();
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(k.target(), new BenchPatternDetails(k.target(), 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(k.gray(), 1, k.white()))));
        byOutput.put(k.gray(), new BenchPatternDetails(k.gray(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.black(), 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(k.white(), 1L);
        stock.put(k.black(), 100_000L);

        ICraftingPlan plan = run(k.target(), byOutput, stock, 100);
        assertTrue(plan.missingItems().isEmpty(), "missing=" + plan.missingItems());
        assertEquals(1, used(plan, k.white()));
        assertEquals(99, timesFor(plan, k.gray()));
        assertEquals(99, used(plan, k.black()));
    }

    /**
     * Baseline: multiple FUZZY parents sharing one finite substitute pool — the pool must
     * be consumed once (shared), not once per parent, and the deficit crafted as primary.
     */
    @Test
    void sharedSubstitutePoolConsumedOnceAcrossFuzzyParents() {
        Keys k = keys();
        BenchAEKey leaf = BenchAEKey.of("leaf");
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(k.target(), new BenchPatternDetails(k.target(), 1, List.of(
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("compA"), 1),
                BenchPatternDetails.InputSpec.of(BenchAEKey.of("compB"), 1))));
        byOutput.put(BenchAEKey.of("compA"), new BenchPatternDetails(BenchAEKey.of("compA"), 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(k.gray(), 1, k.white()),
                BenchPatternDetails.InputSpec.of(leaf, 1))));
        byOutput.put(BenchAEKey.of("compB"), new BenchPatternDetails(BenchAEKey.of("compB"), 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(k.gray(), 1, k.white()),
                BenchPatternDetails.InputSpec.of(leaf, 1))));
        byOutput.put(k.gray(), new BenchPatternDetails(k.gray(), 1, List.of(
                BenchPatternDetails.InputSpec.of(k.black(), 1))));

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(k.white(), 1L);   // one substitute unit shared by BOTH parents
        stock.put(k.black(), 100_000L);
        stock.put(leaf, 100_000L);

        long amount = 100;
        ICraftingPlan plan = run(k.target(), byOutput, stock, amount);
        assertTrue(plan.missingItems().isEmpty(), "missing=" + plan.missingItems());
        assertEquals(1, used(plan, k.white()),
                "the single substitute unit must be consumed exactly once (shared)");
        assertEquals(2 * amount - 1, timesFor(plan, k.gray()),
                "the 2*amount−1 deficit must be crafted as gray");
        assertEquals(2 * amount - 1, used(plan, k.black()));
        assertFalse(plan.patternTimes().isEmpty());
    }
}

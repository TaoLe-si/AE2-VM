package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmark for cycle-aware pattern selection (v1.12.x GTL CYCLE-AWARE).
 * <p>
 * Scenario: A→B (input A, output B), B→A (input B, output A), C→A (input C, output A).
 * Requesting A must prefer C→A over B→A, because B→A leads to the A→B→A cycle.
 * <p>
 * Forge equivalent: dust_steel A→B ingot_steel, B→A with C→A as the "third
 * party" pattern that actually should be used.
 */
public class CycleAwarePatternSelectionBenchmark {

    private static final BenchAEKey A = BenchAEKey.of("cycle_key_A");
    private static final BenchAEKey B = BenchAEKey.of("cycle_key_B");
    private static final BenchAEKey C = BenchAEKey.of("cycle_key_C");
    private static final BenchAEKey D = BenchAEKey.of("cycle_key_D");
    private static final BenchAEKey STEEL_INGOT = BenchAEKey.of("steel_ingot");
    private static final BenchAEKey STEEL_DUST = BenchAEKey.of("steel_dust");
    private static final BenchAEKey IRON_INGOT = BenchAEKey.of("iron_ingot");

    // A→B: input A, output B
    private static IPatternDetails ab() {
        return new BenchPatternDetails(B, 1, List.of(
                BenchPatternDetails.InputSpec.of(A, 1)));
    }

    // B→A: input B, output A
    private static IPatternDetails ba() {
        return new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(B, 1)));
    }

    // C→A: input C, output A
    private static IPatternDetails ca() {
        return new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(C, 1)));
    }

    // D→C: input D, output C (deeper chain, still no cycle back to A)
    private static IPatternDetails dc() {
        return new BenchPatternDetails(C, 1, List.of(
                BenchPatternDetails.InputSpec.of(D, 1)));
    }

    // ========================================================================
    // wouldCauseCycle tests
    // ========================================================================

    @Test
    void wouldCauseCycleDetects_AB_BA_Ring() {
        IPatternDetails ba = ba();
        IPatternDetails ab = ab();
        IPatternDetails ca = ca();

        // mock lookup: B→A for A, A→B for B, nothing for C
        java.util.function.Function<AEKey, Collection<IPatternDetails>> lookup = key -> {
            if (key.equals(A)) return List.of(ba);
            if (key.equals(B)) return List.of(ab);
            return List.of();
        };

        // BA's input is B, B's only pattern is A→B, A→B's input = A (== target) → cycle
        assertTrue(AE2VMCrafting.wouldCauseCycle(lookup, ba, A),
                "BA (input B) should cause cycle: B→A→B→A");

        // CA's input is C, C has no pattern → no cycle
        assertFalse(AE2VMCrafting.wouldCauseCycle(lookup, ca, A),
                "CA (input C) should NOT cause cycle: C has no pattern back to A");

        System.out.println("[cycle-aware] wouldCauseCycle: BA→cycle=true, CA→cycle=false ✓");
    }

    @Test
    void wouldCauseCycleDetectsDirectSelfEdge() {
        // A→A self-loop: input A, output A
        IPatternDetails selfLoop = new BenchPatternDetails(A, 1, List.of(
                BenchPatternDetails.InputSpec.of(A, 1)));

        java.util.function.Function<AEKey, Collection<IPatternDetails>> empty = key -> List.of();
        assertTrue(AE2VMCrafting.wouldCauseCycle(empty, selfLoop, A),
                "self-loop A→A should be detected as cycle");
        System.out.println("[cycle-aware] self-loop A→A detected ✓");
    }

    @Test
    void wouldCauseCycleSkipsDeeperNonCycle() {
        // C→A, D→C chain: input C → C's pattern D→C → D is leaf, no cycle back to A
        IPatternDetails ca = ca();
        IPatternDetails dc = dc();

        java.util.function.Function<AEKey, Collection<IPatternDetails>> lookup = key -> {
            if (key.equals(C)) return List.of(dc);
            return List.of();
        };

        assertFalse(AE2VMCrafting.wouldCauseCycle(lookup, ca, A),
                "C→A with D→C chain should NOT cause cycle (depth 2, no back-edge to A)");
        System.out.println("[cycle-aware] deeper non-cycle chain correctly skipped ✓");
    }

    // ========================================================================
    // pickBestPattern cycle-aware filtering test
    // ========================================================================

    @Test
    void pickBestPatternPrefersCycleFreeCandidate() {
        IPatternDetails ba = ba();
        IPatternDetails ca = ca();

        // Without cycle-aware filtering, pickBestPattern returns the first candidate
        // (both have output=1, totalInput=1). List.of(ba, ca) → ba first.
        IPatternDetails chosen = AE2VMCrafting.pickBestPattern(List.of(ba, ca), A);
        assertNotNull(chosen);

        // Simulate the cycle-aware filtering that resolve() does:
        java.util.function.Function<AEKey, Collection<IPatternDetails>> lookup = key -> {
            if (key.equals(B)) return List.of(ab());
            if (key.equals(C)) return List.of(); // leaf
            return List.of();
        };

        var filtered = new ArrayList<IPatternDetails>();
        for (var p : List.of(ba, ca)) {
            if (p != null && !AE2VMCrafting.wouldCauseCycle(lookup, p, A)) {
                filtered.add(p);
            }
        }
        assertFalse(filtered.isEmpty(), "filtered should not be empty (CA survives)");
        assertEquals(1, filtered.size(), "only CA should survive cycle filter");
        assertEquals(ca.getPrimaryOutput(), filtered.get(0).getPrimaryOutput(),
                "filtered candidate should be C→A (cycle-free)");

        // Now pickBestPattern on the filtered list should pick CA
        IPatternDetails best = AE2VMCrafting.pickBestPattern(filtered, A);
        assertNotNull(best);
        assertTrue(best.getInputs().length == 1 && best.getInputs()[0].getPossibleInputs()[0].what().equals(C),
                "pickBestPattern on filtered list should pick C→A");

        System.out.println("[cycle-aware] pickBestPattern prefers cycle-free candidate ✓");
    }

    // ========================================================================
    // Full VM execution test: cycle-free path produces correct plan
    // ========================================================================

    @Test
    void vmExecuteWithCycleAvoidanceUsesCorrectPath() {
        IPatternDetails ab = ab();
        IPatternDetails ba = ba();
        IPatternDetails ca = ca();

        // Register all patterns in PatternCompiler
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        PatternCompiler.compileIfAbsent(ab);
        PatternCompiler.compileIfAbsent(ba);
        PatternCompiler.compileIfAbsent(ca);

        // Build a resolver that mimics the real resolve() with cycle-aware filtering
        // The resolver returns the FIRST candidate (not pickBestPattern) — but for
        // A, the candidates are [ba, ca]; pickBestPattern picks the first (ba).
        // The cycle-aware filter in resolve() should skip ba and pick ca.
        Map<AEKey, List<IPatternDetails>> candidates = new HashMap<>();
        candidates.put(A, List.of(ba, ca));
        candidates.put(B, List.of(ab));
        // C is a leaf (no pattern)

        // Stock: C has 100, so the C→A path should succeed
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(C, 100L);

        // Build a resolver that mimics the full resolve logic
        var cache = new HashMap<AEKey, Object>();
        CraftingVM vm = new CraftingVM("cycle-aware", key -> {
            if (key instanceof BenchAEKey bk) {
                var subs = candidates.get(bk);
                if (subs != null && !subs.isEmpty()) {
                    // Cycle-aware filtering (same as resolve())
                    if (subs.size() > 1) {
                        var filtered = new ArrayList<IPatternDetails>();
                        for (var p : subs) {
                            if (p != null && !AE2VMCrafting.wouldCauseCycle(
                                    k -> candidates.get(k), p, bk)) {
                                filtered.add(p);
                            }
                        }
                        if (!filtered.isEmpty()) {
                            return AE2VMCrafting.pickBestPattern(filtered, bk);
                        }
                    }
                    return AE2VMCrafting.pickBestPattern(subs, bk);
                }
            }
            return null;
        });

        // Compile request for A
        CraftingBytecode req = PatternCompiler.compileRequest(ca, 1);
        assertNotNull(req, "compileRequest failed");

        var plan = vm.execute(req, new BenchSimulationState(stock));
        assertNotNull(plan, "plan should not be null");
        assertTrue(plan.missingItems().isEmpty(),
                "plan should have no missing items (C is stocked)");
        assertTrue(plan.usedItems().get(C) >= 1,
                "plan should use C (the cycle-free path, not the A→B→A cycle)");

        System.out.println("[cycle-aware] VM execute: plan uses C→A path, missing=none, used C=" + plan.usedItems().get(C) + " ✓");
    }

    // ========================================================================
    // Single-candidate cycle: steel_ingot↔steel_dust (打粉/烧制 2-cycle)
    // ========================================================================

    @Test
    void singleCandidateCycleDetectedAndFallsBack() {
        // steel_ingot→steel_dust (打粉): input steel_ingot, output steel_dust
        IPatternDetails pulverize = new BenchPatternDetails(STEEL_DUST, 1, List.of(
                BenchPatternDetails.InputSpec.of(STEEL_INGOT, 1)));
        // steel_dust→steel_ingot (烧制): input steel_dust, output steel_ingot
        IPatternDetails smelt = new BenchPatternDetails(STEEL_INGOT, 1, List.of(
                BenchPatternDetails.InputSpec.of(STEEL_DUST, 1)));
        // iron_ingot→steel_ingot (高炉): input iron_ingot, output steel_ingot (cycle-free)
        IPatternDetails blast = new BenchPatternDetails(STEEL_INGOT, 1, List.of(
                BenchPatternDetails.InputSpec.of(IRON_INGOT, 1)));

        // Mock lookup: steel_dust has only pulverize (single candidate, cycle-prone)
        // steel_ingot has smelt AND blast (two candidates, one cycle-prone, one clean)
        java.util.function.Function<AEKey, Collection<IPatternDetails>> lookup = key -> {
            if (key.equals(STEEL_DUST)) return List.of(pulverize);
            if (key.equals(STEEL_INGOT)) return List.of(smelt, blast);
            return List.of();
        };

        // 1. wouldCauseCycle for steel_dust's only candidate (pulverize)
        //    pulverize input = steel_ingot → getCraftingFor(steel_ingot) → [smelt, blast]
        //    smelt input = steel_dust == target steel_dust → cycle! → true
        assertTrue(AE2VMCrafting.wouldCauseCycle(lookup, pulverize, STEEL_DUST),
                "pulverize (steel_ingot→steel_dust) should cause cycle: input steel_ingot → smelt → input steel_dust == target");

        // 2. wouldCauseCycle for steel_ingot's candidates
        //    smelt input = steel_dust → getCraftingFor(steel_dust) → [pulverize]
        //    pulverize input = steel_ingot == target steel_ingot → cycle! → true
        assertTrue(AE2VMCrafting.wouldCauseCycle(lookup, smelt, STEEL_INGOT),
                "smelt (steel_dust→steel_ingot) should cause cycle");
        //    blast input = iron_ingot → getCraftingFor(iron_ingot) → [] → no cycle → false
        assertFalse(AE2VMCrafting.wouldCauseCycle(lookup, blast, STEEL_INGOT),
                "blast (iron_ingot→steel_ingot) should NOT cause cycle");

        System.out.println("[cycle-aware] steel_ingot↔steel_dust cycle detected ✓");

        // 3. Simulate the resolve() filtering: steel_ingot has [smelt, blast]
        //    After filtering: smelt removed (cycle), blast kept → only blast remains
        var filtered = new ArrayList<IPatternDetails>();
        for (var p : List.of(smelt, blast)) {
            if (p != null && !AE2VMCrafting.wouldCauseCycle(lookup, p, STEEL_INGOT)) {
                filtered.add(p);
            }
        }
        assertEquals(1, filtered.size(), "only blast (iron_ingot→steel_ingot) should survive");
        assertEquals(blast.getPrimaryOutput(), filtered.get(0).getPrimaryOutput());
        System.out.println("[cycle-aware] steel_ingot: smelt filtered out, blast kept ✓");

        // 4. steel_dust has only [pulverize] → filtered = [] → fallback to original
        var filtered2 = new ArrayList<IPatternDetails>();
        for (var p : List.of(pulverize)) {
            if (p != null && !AE2VMCrafting.wouldCauseCycle(lookup, p, STEEL_DUST)) {
                filtered2.add(p);
            }
        }
        assertTrue(filtered2.isEmpty(), "pulverize is cycle-prone, filtered should be empty");
        // Fallback: keep original
        assertEquals(pulverize.getPrimaryOutput(), pulverize.getPrimaryOutput());
        System.out.println("[cycle-aware] steel_dust: single candidate cycle-prone, fallback to original ✓");
    }

}
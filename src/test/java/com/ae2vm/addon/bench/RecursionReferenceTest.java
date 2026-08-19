package com.ae2vm.addon.bench;

import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulated-scenario tests for the v1.10.3 RECURSION fix — the video/chat bug where a
 * self-referential recipe (output key == one of its own consumed inputs) was reported
 * missing / stuck ("A+B→2A是递归", "a-a这种催化剂能处理那，典型例子就是精华").
 *
 * <p>A self-referential pattern's own output offsets its own input, so the key is NOT a
 * per-craft consumption from stock — it behaves like a one-time seed plus an amplifier:
 * <ul>
 *   <li><b>recursion/amplifier</b> — {@code A + B -> 2A}: each craft nets +1 A (out 2 −
 *       in 1). A batch of {@code n} A needs one {@code A=1} seed + {@code n−1} B. With
 *       no {@code A} stocked the loop cannot be primed → exactly {@code A=1} is missing.
 *       (This is the "递归" the chat confirmed was missing in 1.10.2.)</li>
 *   <li><b>recursion/essence-catalyst</b> — {@code A + B -> A + C}: the catalyst A is
 *       both input and byproduct output, so it circulates forever (essence). A batch of
 *       {@code n} C needs one {@code A=1} seed + {@code n} B; without the seed exactly
 *       {@code A=1} is missing. (The "A-A催化剂 / 精华" case.)</li>
 * </ul>
 * The VM previously treated the self key as a per-craft consumption ({@code in × crafts})
 * and reported a false missing (amplifier: A=3 of 8; essence: A=7 of 8), even though a
 * single seed made the batch feasible. These tests drive the VM through the real
 * reference scenarios and assert the correct feasibility + missing domain/amount.
 */
public class RecursionReferenceTest {

    private final ReferencePlanner planner = new Ae2VmReferencePlanner();

    private static ReferenceScenario find(String idPrefix) {
        return ThunderboltReferenceScenarios.all().stream()
                .filter(s -> s.id().startsWith(idPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + idPrefix));
    }

    /** Replicates ReferenceScenario.validate (package-private there): feasible ⇔ missing empty. */
    private static boolean feasible(CraftPlan<String> plan) {
        return plan != null && plan.supported() && plan.feasible() && plan.missing().isEmpty();
    }

    /** Replicates the infeasible validation: reported ⊆ baseline domain and ≥ baseline. */
    private static boolean infeasibleMatches(CraftPlan<String> plan, List<Map<String, Long>> baselines) {
        if (plan == null || !plan.supported() || plan.feasible()) {
            return false;
        }
        for (Map<String, Long> baseline : baselines) {
            boolean domainOk = plan.missing().entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue() > 0)
                    .allMatch(e -> baseline.containsKey(e.getKey()));
            if (!domainOk) {
                continue;
            }
            boolean sufficient = baseline.entrySet().stream()
                    .allMatch(e -> plan.missing().getOrDefault(e.getKey(), 0L) >= e.getValue());
            if (sufficient) {
                return true;
            }
        }
        return false;
    }

    // ---- recursion/amplifier: A + B -> 2A, seed = 1 A, B = n-1 ----

    @Test
    void amplifierMinimumFeasible() throws Exception {
        // stock {A:1, B:7} — the 1-A seed primes the amplifier; 7 B net +7 A → 8 A. No missing.
        CraftPlan<String> plan = planner.plan(find("recursion/amplifier/minimum"));
        assertTrue(feasible(plan),
                "amplifier with A=1 seed + B=n-1 must be feasible, got " + plan.missing());
    }

    @Test
    void amplifierUnboundedFeasible() throws Exception {
        CraftPlan<String> plan = planner.plan(find("recursion/amplifier/unbounded"));
        assertTrue(feasible(plan),
                "amplifier with unbounded A/B must be feasible, got " + plan.missing());
    }

    @Test
    void amplifierStarvedMissingSeed() throws Exception {
        // stock {B:7} — no A seed → the amplifier cannot be primed; missing exactly A=1.
        CraftPlan<String> plan = planner.plan(find("recursion/amplifier/missing"));
        assertFalse(plan.feasible(), "starved amplifier must be infeasible, got missing=" + plan.missing());
        assertTrue(infeasibleMatches(plan, List.of(Map.of("A", 1L))),
                "starved amplifier must report A>=1 missing, got " + plan.missing());
    }

    // ---- recursion/essence-catalyst: A + B -> A + C, seed = 1 A, B = n ----

    @Test
    void essenceMinimumFeasible() throws Exception {
        // stock {A:1, B:8} — the 1-A essence seed circulates; 8 B → 8 C. No missing.
        CraftPlan<String> plan = planner.plan(find("recursion/essence-catalyst/minimum"));
        assertTrue(feasible(plan),
                "essence catalyst with A=1 seed + B=n must be feasible, got " + plan.missing());
    }

    @Test
    void essenceUnboundedFeasible() throws Exception {
        CraftPlan<String> plan = planner.plan(find("recursion/essence-catalyst/unbounded"));
        assertTrue(feasible(plan),
                "essence catalyst with unbounded A/B must be feasible, got " + plan.missing());
    }

    @Test
    void essenceStarvedMissingSeed() throws Exception {
        // stock {B:8} — no A seed → the essence cannot circulate; missing exactly A=1.
        CraftPlan<String> plan = planner.plan(find("recursion/essence-catalyst/missing"));
        assertFalse(plan.feasible(), "starved essence catalyst must be infeasible, got missing=" + plan.missing());
        assertTrue(infeasibleMatches(plan, List.of(Map.of("A", 1L))),
                "starved essence catalyst must report A>=1 missing, got " + plan.missing());
    }
}

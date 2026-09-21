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
 * Scenario tests for the v1.10.x CATALYST feedback-loop fix (GTL greenhouse-style
 * returned/container inputs are already covered by {@link ProcessingDefaultFuzzyTest}
 * and the {@code catalyst/returned-seed} bench scenarios).
 *
 * <p>A catalyst feedback loop produces a BYPRODUCT that feeds back into its own recipe:
 * <ul>
 *   <li><b>raw-feedback-loop</b> — {@code A -> 2B, 2B + C -> E + D, D -> A}: a BALANCED
 *       catalyst cycle. One {@code A} seed circulates forever (1 A → 2 B → E + D → A), so
 *       a batch of {@code n} E consumes only {@code n} C plus ONE {@code A} seed. With no
 *       {@code A} stocked the loop cannot be primed → exactly {@code A=1} is missing.</li>
 *   <li><b>lossy-feedback-loop</b> — {@code 3 A -> 2 B, 2 B -> D + 2 A}: a DECREASING loop.
 *       Each {@code D} nets −1 {@code A} and retains a 2-{@code A} startup state, so a batch
 *       of {@code n} D needs {@code n + 2} {@code A}. With only {@code n} {@code A} stocked,
 *       exactly {@code A=2} (the startup state) is missing.</li>
 * </ul>
 * The VM previously reported the byproduct ({@code D} / the deficit {@code A}) as a missing
 * leaf, which made the feasible modes report {@code FALSE_POSITIVE} missing and the starved
 * modes report the WRONG missing domain. This test drives the VM through the real reference
 * scenarios and asserts the correct feasibility + missing domain/amount.
 */
public class CatalystFeedbackLoopTest {

    private final ReferencePlanner planner = new Ae2VmReferencePlanner();

    private static ReferenceScenario find(String idPrefix) {
        return ThunderboltReferenceScenarios.all().stream()
                .filter(s -> s.id().startsWith(idPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + idPrefix));
    }

    /** Replicates ReferenceScenario.validate (package-private there): feasible ⇔ missing empty. */
    private static boolean feasible(CraftPlan<String> plan) {
        return plan.supported() && plan.feasible() && plan.missing().isEmpty();
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

    // ---- raw-feedback-loop: balanced catalyst cycle, seed = 1 A ----

    @Test
    void rawLoopMinimumFeasible() throws Exception {
        // stock {A:1, C:8} — the 1-A seed primes the loop; 8 C consumed; no missing.
        CraftPlan<String> plan = planner.plan(find("catalyst/raw-feedback-loop/minimum"));
        assertTrue(feasible(plan),
                "balanced catalyst cycle with A=1 seed must be feasible, got " + plan.missing());
    }

    @Test
    void rawLoopUnboundedFeasible() throws Exception {
        CraftPlan<String> plan = planner.plan(find("catalyst/raw-feedback-loop/unbounded"));
        assertTrue(feasible(plan),
                "balanced catalyst cycle with unbounded A must be feasible, got " + plan.missing());
    }

    @Test
    void rawLoopStarvedMissingSeed() throws Exception {
        // stock {C:8} — no A seed → the loop cannot be primed; missing exactly A=1.
        CraftPlan<String> plan = planner.plan(find("catalyst/raw-feedback-loop/missing"));
        assertFalse(plan.feasible(), "starved balanced cycle must be infeasible, got missing=" + plan.missing());
        assertTrue(infeasibleMatches(plan, List.of(Map.of("A", 1L))),
                "starved balanced cycle must report A>=1 missing, got " + plan.missing());
    }

    // ---- lossy-feedback-loop: decreasing loop, needs amount+2 A ----

    @Test
    void lossyLoopMinimumFeasible() throws Exception {
        // stock {A:10} = 8 net + 2 startup → feasible, no missing.
        CraftPlan<String> plan = planner.plan(find("catalyst/lossy-feedback-loop/minimum"));
        assertTrue(feasible(plan),
                "lossy cycle with A=amount+2 must be feasible, got " + plan.missing());
    }

    @Test
    void lossyLoopUnboundedFeasible() throws Exception {
        CraftPlan<String> plan = planner.plan(find("catalyst/lossy-feedback-loop/unbounded"));
        assertTrue(feasible(plan),
                "lossy cycle with unbounded A must be feasible, got " + plan.missing());
    }

    @Test
    void lossyLoopStarvedMissingStartup() throws Exception {
        // stock {A:8} — only 8 of the 10 needed → missing exactly the 2-A startup state.
        CraftPlan<String> plan = planner.plan(find("catalyst/lossy-feedback-loop/missing"));
        assertFalse(plan.feasible(), "starved lossy cycle must be infeasible, got missing=" + plan.missing());
        assertTrue(infeasibleMatches(plan, List.of(Map.of("A", 2L))),
                "starved lossy cycle must report A>=2 missing, got " + plan.missing());
    }
}

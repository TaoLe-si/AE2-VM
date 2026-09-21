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
 * Scenario tests for the v1.10.x DURABILITY (finite-use tool) support: a durability
 * tool is a returned input that degrades — one {@code amount}-sized unit survives
 * {@code uses} firings, so a batch of {@code times} firings needs
 * {@code amount × ceil(times/uses)} tools (the reference's "成环差分" closed form).
 *
 * <p>The reference {@code durability/finite-use-chain} scenario: {@code product(1 raw +
 * 1 finiteUse tool, 100 uses)} × 10,000. 10,000 firings need {@code ceil(10000/100) = 100}
 * tools — NOT 10,000 (the old "consumed per craft" model) and NOT 1 (a catalyst seed).
 * The VM previously demanded 9900 false-missing tools in the feasible MINIMUM mode; this
 * test pins the correct feasibility + missing domain/amount.
 */
public class DurabilityToolTest {

    private final ReferencePlanner planner = new Ae2VmReferencePlanner();

    private static ReferenceScenario find(String idPrefix) {
        return ThunderboltReferenceScenarios.all().stream()
                .filter(s -> s.id().startsWith(idPrefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no scenario " + idPrefix));
    }

    private static boolean feasible(CraftPlan<String> plan) {
        return plan.supported() && plan.feasible() && plan.missing().isEmpty();
    }

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

    @Test
    void durabilityMinimumFeasible() throws Exception {
        // stock {tool: 100, raw: 10_000} — 100 tools × 100 uses = 10_000 firings → feasible.
        CraftPlan<String> plan = planner.plan(find("durability/finite-use-chain/minimum"));
        assertTrue(feasible(plan),
                "durability with exactly ceil(amount/uses) tools must be feasible, got " + plan.missing());
    }

    @Test
    void durabilityUnboundedFeasible() throws Exception {
        CraftPlan<String> plan = planner.plan(find("durability/finite-use-chain/unbounded"));
        assertTrue(feasible(plan),
                "durability with unbounded tools must be feasible, got " + plan.missing());
    }

    @Test
    void durabilityStarvedMissingOneTool() throws Exception {
        // stock {tool: 99, raw: 10_000} — one tool short of the 100 needed.
        CraftPlan<String> plan = planner.plan(find("durability/finite-use-chain/missing"));
        assertFalse(plan.feasible(), "durability one tool short must be infeasible, got " + plan.missing());
        assertTrue(infeasibleMatches(plan, List.of(Map.of("tool", 1L))),
                "durability one tool short must report tool>=1 missing, got " + plan.missing());
    }
}

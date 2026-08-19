package com.ae2vm.addon.bench;

import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Diagnostic: runs every scenario and prints detailed plan (firings / used / missing)
 * for those that do NOT validate, so we can see exactly what the VM produces for each
 * capability family before deciding the fix.
 */
public class FalsePositiveDiagnosticTest {

    private final ReferencePlanner planner = new Ae2VmReferencePlanner();

    @Test
    void dumpAllFalsePositives() throws Exception {
        for (ReferenceScenario s : ThunderboltReferenceScenarios.all()) {
            CraftPlan<String> plan = planner.plan(s);
            if (plan == null) {
                System.out.println("=== " + s.id() + " => NULL PLAN");
                continue;
            }
            // Replicate ReferenceScenario.validate (package-private there):
            boolean valid = plan.supported() && plan.feasible() == s.expectedFeasible();
            if (s.expectedFeasible()) {
                valid = valid && plan.missing().isEmpty();
            } else {
                boolean any = false;
                for (Map<String, Long> baseline : s.minimalMissing()) {
                    boolean domainOk = plan.missing().entrySet().stream()
                            .filter(e -> e.getValue() != null && e.getValue() > 0)
                            .allMatch(e -> baseline.containsKey(e.getKey()));
                    if (!domainOk) continue;
                    boolean sufficient = baseline.entrySet().stream()
                            .allMatch(e -> plan.missing().getOrDefault(e.getKey(), 0L) >= e.getValue());
                    if (sufficient) { any = true; break; }
                }
                valid = valid && any;
            }
            if (valid) {
                continue; // supported — skip
            }
            System.out.println("=== " + s.id()
                    + " target=" + s.target() + "x" + s.amount()
                    + " mode=" + s.materialMode() + " scale=" + s.scale()
                    + " expectedFeasible=" + s.expectedFeasible()
                    + " minimalMissing=" + s.minimalMissing());
            System.out.println("    feasible=" + plan.feasible()
                    + " missing=" + plan.missing()
                    + " used=" + plan.usedStock()
                    + " gross=" + plan.grossDemand());
            System.out.println("    firings:");
            for (Map.Entry<CraftPattern<String>, Long> e : plan.firings().entrySet()) {
                var p = e.getKey();
                System.out.println("      " + p.output() + "x" + p.outputAmount()
                        + " <- " + p.inputs() + " * " + e.getValue());
            }
        }
    }
}

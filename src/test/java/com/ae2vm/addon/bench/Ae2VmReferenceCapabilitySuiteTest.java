package com.ae2vm.addon.bench;

import com.moakiee.thunderbolt.core.planner.reference.ReferenceCapabilityRunner;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceSupportStatus;
import com.moakiee.thunderbolt.core.planner.reference.ThunderboltReferenceScenarios;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;

/**
 * Runs the AE2 VM engine through the full Thunderbolt reference capability suite
 * (11 graph families, 33 material cases, 1s deadline / 100ms grace per case).
 *
 * <p>Prints one {@code [reference-capability]} row per case (same format as
 * Thunderbolt's own suite) so the VM's computation speed and capability surface can
 * be compared against {@code CraftPlannerV2}. Unlike Thunderbolt's suite this does
 * NOT assert {@code SUPPORTED} — the point is measurement: the VM lacks catalyst /
 * durability / reusable-stock concepts, so those families are expected to classify
 * as {@code FALSE_POSITIVE} rather than {@code SUPPORTED}.
 */
public class Ae2VmReferenceCapabilitySuiteTest {

    private static final ReferenceCapabilityRunner RUNNER = new ReferenceCapabilityRunner(
            Duration.ofSeconds(1), Duration.ofMillis(100));

    private static final ReferencePlanner AE2_VM = new Ae2VmReferencePlanner();

    /** Accumulates per-scenario outcomes so the trailing summary test can report aggregates. */
    private static final ConcurrentLinkedQueue<com.moakiee.thunderbolt.core.planner.reference.ReferenceRunResult> RESULTS =
            new ConcurrentLinkedQueue<>();

    @TestFactory
    Stream<DynamicTest> referenceCapabilities() {
        List<ReferenceScenario> scenarios = ThunderboltReferenceScenarios.all();
        List<DynamicTest> tests = new ArrayList<>(scenarios.size() + 1);
        for (ReferenceScenario scenario : scenarios) {
            tests.add(DynamicTest.dynamicTest(scenario.id(), () -> runOne(scenario)));
        }
        // Trailing summary: prints aggregate status counts + total elapsed across all 33 cases.
        tests.add(DynamicTest.dynamicTest("summary", () -> printSummary()));
        return tests.stream();
    }

    private static void runOne(ReferenceScenario scenario) {
        var result = RUNNER.run(AE2_VM, scenario);
        RESULTS.add(result);
        System.out.println("[reference-capability] engine=ae2vm id=" + scenario.id()
                + " capability=" + scenario.capability()
                + " mode=" + scenario.materialMode()
                + " scale=" + scenario.scale()
                + " status=" + result.status()
                + " missingOverhead=" + result.missingOverhead()
                + " missing=" + (result.plan() == null ? null : result.plan().missing())
                + " elapsedMs=" + String.format("%.3f", result.elapsedNanos() / 1_000_000.0D));
        if (result.failure() != null && result.status() == ReferenceSupportStatus.ENGINE_ERROR) {
            result.failure().printStackTrace(System.out);
        }
    }

    private static void printSummary() {
        Map<ReferenceSupportStatus, Integer> counts = new EnumMap<>(ReferenceSupportStatus.class);
        double totalElapsedMs = 0.0D;
        for (var result : RESULTS) {
            counts.merge(result.status(), 1, Integer::sum);
            totalElapsedMs += result.elapsedNanos() / 1_000_000.0D;
        }
        System.out.println("[reference-capability] engine=ae2vm SUMMARY cases=" + RESULTS.size()
                + " supported=" + counts.getOrDefault(ReferenceSupportStatus.SUPPORTED, 0)
                + " falsePositive=" + counts.getOrDefault(ReferenceSupportStatus.FALSE_POSITIVE, 0)
                + " engineError=" + counts.getOrDefault(ReferenceSupportStatus.ENGINE_ERROR, 0)
                + " timeout=" + (counts.getOrDefault(ReferenceSupportStatus.ENGINE_TIMEOUT, 0)
                        + counts.getOrDefault(ReferenceSupportStatus.NON_COOPERATIVE_TIMEOUT, 0))
                + " totalElapsedMs=" + String.format("%.1f", totalElapsedMs)
                + " (NOTE: first case includes JVM/class-load warmup)");
    }
}

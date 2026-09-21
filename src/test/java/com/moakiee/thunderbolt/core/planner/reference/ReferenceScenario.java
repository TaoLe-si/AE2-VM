package com.moakiee.thunderbolt.core.planner.reference;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftPlan;

/** One graph, inventory mode and expected semantic result. */
public record ReferenceScenario(
        String id,
        ReferenceCapability capability,
        ReferenceMaterialMode materialMode,
        int scale,
        CraftGraph<String> graph,
        String target,
        long amount,
        boolean expectedFeasible,
        List<Map<String, Long>> minimalMissing,
        Map<String, Double> missingWeights,
        Predicate<CraftPlan<String>> additionalValidator) {

    public ReferenceScenario {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(materialMode, "materialMode");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(target, "target");
        if (scale < 0 || amount <= 0) {
            throw new IllegalArgumentException("scale must be non-negative and amount positive");
        }
        var normalizedMissing = new ArrayList<Map<String, Long>>();
        if (minimalMissing != null) {
            for (var candidate : minimalMissing) {
                var clean = candidate.entrySet().stream()
                        .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, Map.Entry::getValue));
                normalizedMissing.add(clean);
            }
        }
        minimalMissing = List.copyOf(normalizedMissing);
        missingWeights = missingWeights == null ? Map.of() : Map.copyOf(missingWeights);
        additionalValidator = additionalValidator == null ? ignored -> true : additionalValidator;
        if (expectedFeasible && !minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("feasible scenarios cannot define missing baselines");
        }
        if (!expectedFeasible && minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("infeasible scenarios need a missing baseline");
        }
    }

    Validation validate(CraftPlan<String> plan) {
        if (plan == null || !plan.supported() || plan.feasible() != expectedFeasible
                || !additionalValidator.test(plan)) {
            return Validation.invalid();
        }
        if (expectedFeasible) {
            return plan.missing().isEmpty() ? Validation.valid(1.0D) : Validation.invalid();
        }

        double bestOverhead = Double.POSITIVE_INFINITY;
        for (var baseline : minimalMissing) {
            if (!sameMissingDomain(plan.missing(), baseline)) {
                continue;
            }
            boolean sufficient = true;
            for (var entry : baseline.entrySet()) {
                if (plan.missing().getOrDefault(entry.getKey(), 0L) < entry.getValue()) {
                    sufficient = false;
                    break;
                }
            }
            if (!sufficient) {
                continue;
            }
            double minimumCost = weightedCost(baseline);
            double reportedCost = weightedCost(plan.missing());
            bestOverhead = Math.min(bestOverhead, reportedCost / minimumCost);
        }
        return Double.isFinite(bestOverhead)
                ? Validation.valid(bestOverhead)
                : Validation.invalid();
    }

    private boolean sameMissingDomain(Map<String, Long> reported, Map<String, Long> baseline) {
        return reported.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .allMatch(entry -> baseline.containsKey(entry.getKey()));
    }

    private double weightedCost(Map<String, Long> missing) {
        double total = 0.0D;
        for (var entry : missing.entrySet()) {
            if (entry.getValue() > 0) {
                total += entry.getValue() * missingWeights.getOrDefault(entry.getKey(), 1.0D);
            }
        }
        return total;
    }

    record Validation(boolean valid, double missingOverhead) {
        static Validation valid(double overhead) {
            return new Validation(true, overhead);
        }

        static Validation invalid() {
            return new Validation(false, Double.NaN);
        }
    }
}

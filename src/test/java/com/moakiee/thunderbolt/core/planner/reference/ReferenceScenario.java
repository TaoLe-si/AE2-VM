package com.moakiee.thunderbolt.core.planner.reference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftPlan;

/** One graph, inventory mode and expected semantic result. */
public final class ReferenceScenario {

    private final String id;
    private final ReferenceCapability capability;
    private final ReferenceMaterialMode materialMode;
    private final int scale;
    private final CraftGraph<String> graph;
    private final String target;
    private final long amount;
    private final boolean expectedFeasible;
    private final List<Map<String, Long>> minimalMissing;
    private final Map<String, Double> missingWeights;
    private final Predicate<CraftPlan<String>> additionalValidator;

    public ReferenceScenario(
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
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(materialMode, "materialMode");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(target, "target");
        if (scale < 0 || amount <= 0) {
            throw new IllegalArgumentException("scale must be non-negative and amount positive");
        }
        List<Map<String, Long>> normalizedMissing = new ArrayList<Map<String, Long>>();
        if (minimalMissing != null) {
            for (Map<String, Long> candidate : minimalMissing) {
                Map<String, Long> clean = new LinkedHashMap<String, Long>();
                for (Map.Entry<String, Long> entry : candidate.entrySet()) {
                    if (entry.getValue() != null && entry.getValue() > 0) {
                        clean.put(entry.getKey(), entry.getValue());
                    }
                }
                normalizedMissing.add(Collections.unmodifiableMap(clean));
            }
        }
        this.id = id;
        this.capability = capability;
        this.materialMode = materialMode;
        this.scale = scale;
        this.graph = graph;
        this.target = target;
        this.amount = amount;
        this.expectedFeasible = expectedFeasible;
        this.minimalMissing = Collections.unmodifiableList(normalizedMissing);
        this.missingWeights = missingWeights == null
                ? Collections.<String, Double>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Double>(missingWeights));
        this.additionalValidator = additionalValidator == null ? ignored -> true : additionalValidator;
        if (expectedFeasible && !this.minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("feasible scenarios cannot define missing baselines");
        }
        if (!expectedFeasible && this.minimalMissing.isEmpty()) {
            throw new IllegalArgumentException("infeasible scenarios need a missing baseline");
        }
    }

    public String id() {
        return this.id;
    }

    public ReferenceCapability capability() {
        return this.capability;
    }

    public ReferenceMaterialMode materialMode() {
        return this.materialMode;
    }

    public int scale() {
        return this.scale;
    }

    public CraftGraph<String> graph() {
        return this.graph;
    }

    public String target() {
        return this.target;
    }

    public long amount() {
        return this.amount;
    }

    public boolean expectedFeasible() {
        return this.expectedFeasible;
    }

    public List<Map<String, Long>> minimalMissing() {
        return this.minimalMissing;
    }

    public Map<String, Double> missingWeights() {
        return this.missingWeights;
    }

    public Predicate<CraftPlan<String>> additionalValidator() {
        return this.additionalValidator;
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
        for (Map<String, Long> baseline : minimalMissing) {
            if (!sameMissingDomain(plan.missing(), baseline)) {
                continue;
            }
            boolean sufficient = true;
            for (Map.Entry<String, Long> entry : baseline.entrySet()) {
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
        for (Map.Entry<String, Long> entry : missing.entrySet()) {
            if (entry.getValue() > 0) {
                total += entry.getValue() * missingWeights.getOrDefault(entry.getKey(), 1.0D);
            }
        }
        return total;
    }

    @Override
    public String toString() {
        return "ReferenceScenario[id=" + this.id + ", capability=" + this.capability
                + ", materialMode=" + this.materialMode + ", scale=" + this.scale
                + ", target=" + this.target + ", amount=" + this.amount + "]";
    }

    static final class Validation {

        private final boolean valid;
        private final double missingOverhead;

        Validation(boolean valid, double missingOverhead) {
            this.valid = valid;
            this.missingOverhead = missingOverhead;
        }

        static Validation valid(double overhead) {
            return new Validation(true, overhead);
        }

        static Validation invalid() {
            return new Validation(false, Double.NaN);
        }

        boolean valid() {
            return this.valid;
        }

        double missingOverhead() {
            return this.missingOverhead;
        }
    }
}

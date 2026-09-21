package com.moakiee.thunderbolt.core.planner.reference;

import com.moakiee.thunderbolt.core.planner.CraftPlan;

/** Auditable outcome for one reference scenario. */
public final class ReferenceRunResult {

    private final ReferenceScenario scenario;
    private final ReferenceSupportStatus status;
    private final long elapsedNanos;
    private final double missingOverhead;
    private final CraftPlan<String> plan;
    private final Throwable failure;

    public ReferenceRunResult(ReferenceScenario scenario,
                              ReferenceSupportStatus status,
                              long elapsedNanos,
                              double missingOverhead,
                              CraftPlan<String> plan,
                              Throwable failure) {
        this.scenario = scenario;
        this.status = status;
        this.elapsedNanos = elapsedNanos;
        this.missingOverhead = missingOverhead;
        this.plan = plan;
        this.failure = failure;
    }

    public ReferenceScenario scenario() {
        return this.scenario;
    }

    public ReferenceSupportStatus status() {
        return this.status;
    }

    public long elapsedNanos() {
        return this.elapsedNanos;
    }

    public double missingOverhead() {
        return this.missingOverhead;
    }

    public CraftPlan<String> plan() {
        return this.plan;
    }

    public Throwable failure() {
        return this.failure;
    }

    public boolean supported() {
        return this.status == ReferenceSupportStatus.SUPPORTED;
    }

    @Override
    public String toString() {
        return "ReferenceRunResult[scenario=" + (this.scenario == null ? null : this.scenario.id())
                + ", status=" + this.status
                + ", elapsedNanos=" + this.elapsedNanos
                + ", missingOverhead=" + this.missingOverhead
                + ", plan=" + this.plan
                + ", failure=" + this.failure + "]";
    }
}

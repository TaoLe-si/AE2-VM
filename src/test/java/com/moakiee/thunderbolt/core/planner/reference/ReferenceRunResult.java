package com.moakiee.thunderbolt.core.planner.reference;

import org.jetbrains.annotations.Nullable;

import com.moakiee.thunderbolt.core.planner.CraftPlan;

/** Auditable outcome for one reference scenario. */
public record ReferenceRunResult(
        ReferenceScenario scenario,
        ReferenceSupportStatus status,
        long elapsedNanos,
        double missingOverhead,
        @Nullable CraftPlan<String> plan,
        @Nullable Throwable failure) {

    public boolean supported() {
        return status == ReferenceSupportStatus.SUPPORTED;
    }
}

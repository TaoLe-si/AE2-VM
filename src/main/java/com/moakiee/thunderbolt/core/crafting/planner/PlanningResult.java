package com.moakiee.thunderbolt.core.crafting.planner;

/** A crafting plan together with diagnostics gathered during that exact planning run. */
public record PlanningResult<K>(CraftPlan<K> plan, PlanningDiagnostics diagnostics) {
}

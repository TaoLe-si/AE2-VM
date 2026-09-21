package com.moakiee.thunderbolt.core.planner.reference;

import com.moakiee.thunderbolt.core.planner.CraftPlan;

/** Minimal production-shaped adapter used by an engine author to run the reference suite. */
public interface ReferencePlanner {
    boolean check(ReferenceScenario scenario) throws Exception;

    CraftPlan<String> plan(ReferenceScenario scenario) throws Exception;
}

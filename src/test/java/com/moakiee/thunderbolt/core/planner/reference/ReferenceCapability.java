package com.moakiee.thunderbolt.core.planner.reference;

/** Author-facing reference capability families. */
public enum ReferenceCapability {
    SINGLE_DAG,
    MULTI_DAG,
    CYCLE_CUTTING,
    CATALYST,
    DURABILITY_CHAIN,
    FUZZY_VARIANT,
    /** Self-referential recipes: a pattern whose own output is also one of its own
     *  consumed inputs (amplifier A+B→2A, essence-catalyst A+B→A+C). The pattern
     *  primes with a one-time seed and the self-production offsets the self-consumption. */
    RECURSION
}

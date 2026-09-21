package com.moakiee.thunderbolt.core.planner.reference;

/** Observable production-path classification; only SUPPORTED is an actual capability claim. */
public enum ReferenceSupportStatus {
    SUPPORTED,
    CHECK_REJECTED,
    ATTEMPT_DECLINED,
    FALSE_NEGATIVE,
    ENGINE_ERROR,
    ENGINE_TIMEOUT,
    NON_COOPERATIVE_TIMEOUT,
    FALSE_POSITIVE
}

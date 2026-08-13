package com.ae2vm.addon.api;

import java.util.Map;

/**
 * rv4 shim for AE2 15.x's {@code ICraftingPlan}: the computed plan result.
 */
public interface ICraftingPlan {

    GenericStack finalOutput();

    long bytes();

    boolean simulation();

    boolean multiplePaths();

    KeyCounter usedItems();

    KeyCounter emittedItems();

    KeyCounter missingItems();

    Map<IPatternDetails, Long> patternTimes();
}

package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/** One planned reusable input resolved through a pattern-specific matching route. */
public record ReusableStockRouteKey<K>(ReusableStockSource source, K plannedKey) {
    public ReusableStockRouteKey {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plannedKey, "plannedKey");
    }
}

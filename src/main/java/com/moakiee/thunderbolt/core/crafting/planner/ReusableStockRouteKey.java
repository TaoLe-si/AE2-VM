package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import java.util.Objects;

/** One planned reusable input resolved through a pattern-specific matching route. */
public record ReusableStockRouteKey<K>(ReusableStockSource source, K plannedKey) {
    public ReusableStockRouteKey {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plannedKey, "plannedKey");
    }
}

package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.Objects;

/** Exact physical variant assigned to one logical reusable-stock route. */
public record ReusableStockAllocationKey<K>(ReusableStockRouteKey<K> route, K actualKey) {
    public ReusableStockAllocationKey {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(actualKey, "actualKey");
    }
}

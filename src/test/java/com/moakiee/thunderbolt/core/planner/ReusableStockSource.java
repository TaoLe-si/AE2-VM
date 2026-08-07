package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/**
 * Routes a reusable input through one physical host inventory and one logical execution pool.
 *
 * <p>Several safe single-seed loops may share {@link #poolScope()}; deadlock-prone multi-seed loops
 * use separate pool scopes while still competing for the same {@link #storageScope()} inventory.
 */
public record ReusableStockSource(Object storageScope, Object poolScope, Object routingScope) {
    public ReusableStockSource(Object storageScope, Object poolScope) {
        this(storageScope, poolScope, poolScope);
    }

    public ReusableStockSource {
        Objects.requireNonNull(storageScope, "storageScope");
        Objects.requireNonNull(poolScope, "poolScope");
        Objects.requireNonNull(routingScope, "routingScope");
    }
}

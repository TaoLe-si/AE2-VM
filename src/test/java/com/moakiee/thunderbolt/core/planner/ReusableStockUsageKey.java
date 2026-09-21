package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/** One private-stock borrow, retaining both its physical host and logical execution pool. */
public record ReusableStockUsageKey<K>(
        Object storageScope,
        Object poolScope,
        Object routingScope,
        K key,
        K actualKey) {
    public ReusableStockUsageKey(Object storageScope, Object poolScope, K key) {
        this(storageScope, poolScope, poolScope, key, key);
    }

    public ReusableStockUsageKey {
        Objects.requireNonNull(storageScope, "storageScope");
        Objects.requireNonNull(poolScope, "poolScope");
        Objects.requireNonNull(routingScope, "routingScope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(actualKey, "actualKey");
    }
}

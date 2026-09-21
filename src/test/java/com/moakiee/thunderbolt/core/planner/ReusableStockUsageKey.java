package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/** One private-stock borrow, retaining both its physical host and logical execution pool. */
public final class ReusableStockUsageKey<K> {

    private final Object storageScope;
    private final Object poolScope;
    private final Object routingScope;
    private final K key;
    private final K actualKey;

    public ReusableStockUsageKey(Object storageScope, Object poolScope, Object routingScope,
                                 K key, K actualKey) {
        Objects.requireNonNull(storageScope, "storageScope");
        Objects.requireNonNull(poolScope, "poolScope");
        Objects.requireNonNull(routingScope, "routingScope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(actualKey, "actualKey");
        this.storageScope = storageScope;
        this.poolScope = poolScope;
        this.routingScope = routingScope;
        this.key = key;
        this.actualKey = actualKey;
    }

    public ReusableStockUsageKey(Object storageScope, Object poolScope, K key) {
        this(storageScope, poolScope, poolScope, key, key);
    }

    public Object storageScope() {
        return this.storageScope;
    }

    public Object poolScope() {
        return this.poolScope;
    }

    public Object routingScope() {
        return this.routingScope;
    }

    public K key() {
        return this.key;
    }

    public K actualKey() {
        return this.actualKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReusableStockUsageKey)) {
            return false;
        }
        ReusableStockUsageKey<?> other = (ReusableStockUsageKey<?>) o;
        return this.storageScope.equals(other.storageScope)
                && this.poolScope.equals(other.poolScope)
                && this.routingScope.equals(other.routingScope)
                && this.key.equals(other.key)
                && this.actualKey.equals(other.actualKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.storageScope, this.poolScope, this.routingScope, this.key, this.actualKey);
    }

    @Override
    public String toString() {
        return "ReusableStockUsageKey[storageScope=" + this.storageScope
                + ", poolScope=" + this.poolScope
                + ", routingScope=" + this.routingScope
                + ", key=" + this.key
                + ", actualKey=" + this.actualKey + "]";
    }
}

package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/** Identifies one concrete key inside a host-owned reusable-stock scope. */
public final class ReusableStockKey<K> {

    private final Object scope;
    private final K key;

    public ReusableStockKey(Object scope, K key) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        this.scope = scope;
        this.key = key;
    }

    public Object scope() {
        return this.scope;
    }

    public K key() {
        return this.key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReusableStockKey)) {
            return false;
        }
        ReusableStockKey<?> other = (ReusableStockKey<?>) o;
        return this.scope.equals(other.scope) && this.key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.scope, this.key);
    }

    @Override
    public String toString() {
        return "ReusableStockKey[scope=" + this.scope + ", key=" + this.key + "]";
    }
}

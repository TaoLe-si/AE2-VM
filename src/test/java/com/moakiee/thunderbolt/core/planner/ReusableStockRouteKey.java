package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/** One planned reusable input resolved through a pattern-specific matching route. */
public final class ReusableStockRouteKey<K> {

    private final ReusableStockSource source;
    private final K plannedKey;

    public ReusableStockRouteKey(ReusableStockSource source, K plannedKey) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(plannedKey, "plannedKey");
        this.source = source;
        this.plannedKey = plannedKey;
    }

    public ReusableStockSource source() {
        return this.source;
    }

    public K plannedKey() {
        return this.plannedKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReusableStockRouteKey)) {
            return false;
        }
        ReusableStockRouteKey<?> other = (ReusableStockRouteKey<?>) o;
        return this.source.equals(other.source) && this.plannedKey.equals(other.plannedKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.source, this.plannedKey);
    }

    @Override
    public String toString() {
        return "ReusableStockRouteKey[source=" + this.source + ", plannedKey=" + this.plannedKey + "]";
    }
}

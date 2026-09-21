package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/**
 * Routes a reusable input through one physical host inventory and one logical execution pool.
 *
 * <p>Several safe single-seed loops may share {@link #poolScope()}; deadlock-prone multi-seed loops
 * use separate pool scopes while still competing for the same {@link #storageScope()} inventory.
 */
public final class ReusableStockSource {

    private final Object storageScope;
    private final Object poolScope;
    private final Object routingScope;

    public ReusableStockSource(Object storageScope, Object poolScope, Object routingScope) {
        Objects.requireNonNull(storageScope, "storageScope");
        Objects.requireNonNull(poolScope, "poolScope");
        Objects.requireNonNull(routingScope, "routingScope");
        this.storageScope = storageScope;
        this.poolScope = poolScope;
        this.routingScope = routingScope;
    }

    public ReusableStockSource(Object storageScope, Object poolScope) {
        this(storageScope, poolScope, poolScope);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReusableStockSource)) {
            return false;
        }
        ReusableStockSource other = (ReusableStockSource) o;
        return this.storageScope.equals(other.storageScope)
                && this.poolScope.equals(other.poolScope)
                && this.routingScope.equals(other.routingScope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.storageScope, this.poolScope, this.routingScope);
    }

    @Override
    public String toString() {
        return "ReusableStockSource[storageScope=" + this.storageScope
                + ", poolScope=" + this.poolScope
                + ", routingScope=" + this.routingScope + "]";
    }
}

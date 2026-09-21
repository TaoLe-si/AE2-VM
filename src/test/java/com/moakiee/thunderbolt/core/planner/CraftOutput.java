package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/**
 * An additional (non-primary) output of a {@link CraftPattern}: a byproduct.
 *
 * <p>The primary output is modeled directly on {@link CraftPattern} ({@code output}/{@code outputAmount}).
 * Every other item a pattern yields per firing is a byproduct. The v2 planner feeds byproducts into a
 * shared pool so they can opportunistically satisfy other demands (sibling needs) before anything is
 * crafted from scratch — mirroring AE2's optimistic reuse, but in closed form.
 *
 * @param <K> item key type
 */
public final class CraftOutput<K> {

    private final K key;
    private final long amount;

    public CraftOutput(K key, long amount) {
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("output amount must be > 0, was " + amount);
        }
        this.key = key;
        this.amount = amount;
    }

    public K key() {
        return this.key;
    }

    public long amount() {
        return this.amount;
    }

    public static <K> CraftOutput<K> of(K key, long amount) {
        return new CraftOutput<>(key, amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CraftOutput)) {
            return false;
        }
        CraftOutput<?> other = (CraftOutput<?>) o;
        return this.amount == other.amount && this.key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.key, this.amount);
    }

    @Override
    public String toString() {
        return "CraftOutput[key=" + this.key + ", amount=" + this.amount + "]";
    }
}

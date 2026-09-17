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
 * @param key    the byproduct item
 * @param amount how many are produced per single firing of the pattern
 * @param <K>    item key type
 */
public record CraftOutput<K>(K key, long amount) {

    public CraftOutput {
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("output amount must be > 0, was " + amount);
        }
    }

    public static <K> CraftOutput<K> of(K key, long amount) {
        return new CraftOutput<>(key, amount);
    }
}

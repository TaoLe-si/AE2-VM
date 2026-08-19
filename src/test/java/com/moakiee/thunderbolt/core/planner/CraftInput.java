package com.moakiee.thunderbolt.core.planner;

import java.util.Objects;

/**
 * One input slot of a {@link CraftPattern}.
 *
 * <p>Three flavours, all handled in closed form (no per-firing loop):
 * <ul>
 *   <li><b>normal</b> ({@link #of}): consumed every firing → a batch of {@code times} needs
 *       {@code amount * times}.</li>
 *   <li><b>catalyst / container</b> ({@link #returned}, {@code uses = }{@link #INFINITE_USES}): handed
 *       back unchanged and reused indefinitely → a whole batch needs only {@code amount} as a seed.
 *       Mirrors AE2's {@code limitQty}.</li>
 *   <li><b>finite-use catalyst</b> ({@link #finiteUse}): a degrading tool like {@code 1·A(n) + 1·B →
 *       1·C + A(n-1)}. One full unit survives {@code uses} firings, so the chain {@code A(n)→…→A(0)}
 *       is solved once and then reduced to the closed form {@code amount·ceil(times/uses)} units
 *       consumed for {@code times} firings — the "成环差分" reduction.</li>
 * </ul>
 *
 * @param key       the input item
 * @param amount    how many are consumed per single firing (per tool-set for finite-use)
 * @param returned  {@code true} for catalyst/container/finite-use inputs (reused, not consumed 1:1)
 * @param uses      firings a single {@code amount}-sized unit survives; {@link #INFINITE_USES} for a
 *                  true catalyst, {@code n} for a durability-{@code n} tool. Ignored when not returned.
 * @param remainder for a container input (e.g. a filled bucket consumed, leaving an empty bucket), the
 *                  <em>different</em> item handed back per consumed unit; {@code null} otherwise. The
 *                  input is consumed normally and {@code amount} of {@code remainder} are produced per
 *                  firing as a byproduct, so refilling it back forms a cycle the planner resolves.
 * @param <K>       item key type (e.g. AE2's AEKey, or String in tests)
 */
public record CraftInput<K>(K key, long amount, boolean returned, long uses, K remainder,
                            ReusableStockSource reusableStockSource) {

    /** A true catalyst survives unlimited firings (one seed serves the whole batch). */
    public static final long INFINITE_USES = Long.MAX_VALUE;

    public CraftInput {
        Objects.requireNonNull(key, "key");
        if (amount <= 0) {
            throw new IllegalArgumentException("input amount must be > 0, was " + amount);
        }
        if (returned && uses <= 0) {
            throw new IllegalArgumentException("returned input uses must be > 0, was " + uses);
        }
        if (reusableStockSource != null
                && (!returned || uses != INFINITE_USES || remainder != null)) {
            throw new IllegalArgumentException(
                    "host-owned reusable stock requires an unchanged, infinitely reusable input");
        }
    }

    public static <K> CraftInput<K> of(K key, long amount) {
        return new CraftInput<>(key, amount, false, INFINITE_USES, null, null);
    }

    public static <K> CraftInput<K> returned(K key, long amount) {
        return new CraftInput<>(key, amount, true, INFINITE_USES, null, null);
    }

    /** A catalyst whose initial seed is borrowed from a host-private reusable-stock scope. */
    public static <K> CraftInput<K> returnedFrom(
            K key, long amount, ReusableStockSource source) {
        return new CraftInput<>(key, amount, true, INFINITE_USES, null,
                Objects.requireNonNull(source, "source"));
    }

    /** A degrading tool/finite catalyst: one {@code amount}-sized unit survives {@code uses} firings. */
    public static <K> CraftInput<K> finiteUse(K key, long amount, long uses) {
        return new CraftInput<>(key, amount, true, uses, null, null);
    }

    /**
     * A container input: {@code amount} of {@code key} are consumed per firing and the same count of
     * {@code remainder} (a different item, e.g. the empty bucket) is handed back as a byproduct.
     */
    public static <K> CraftInput<K> consumedReturning(K key, long amount, K remainder) {
        return new CraftInput<>(key, amount, false, INFINITE_USES,
                Objects.requireNonNull(remainder), null);
    }

    /** Units of {@link #key} consumed to fire the pattern {@code times} times (closed form). */
    public long unitsFor(long times) {
        if (!returned) {
            return Sat.mul(amount, times);
        }
        long unit = uses == INFINITE_USES ? 1L : Sat.ceilDiv(times, uses);
        return Sat.mul(amount, unit);
    }

    /** Max firings supportable if {@code available} units of {@link #key} are on hand (capacity bound). */
    public long firingsFrom(long available) {
        long perUnit = available / amount; // whole units usable
        if (!returned) {
            return perUnit;
        }
        return Sat.mul(perUnit, uses); // each unit yields `uses` firings (INFINITE_USES saturates)
    }
}

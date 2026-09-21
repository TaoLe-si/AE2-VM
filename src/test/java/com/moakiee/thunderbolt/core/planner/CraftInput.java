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
 * @param <K> item key type (e.g. AE2's AEKey, or String in tests)
 */
public final class CraftInput<K> {

    /** A true catalyst survives unlimited firings (one seed serves the whole batch). */
    public static final long INFINITE_USES = Long.MAX_VALUE;

    private final K key;
    private final long amount;
    private final boolean returned;
    private final long uses;
    private final K remainder;
    private final ReusableStockSource reusableStockSource;

    public CraftInput(K key, long amount, boolean returned, long uses, K remainder,
                      ReusableStockSource reusableStockSource) {
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
        this.key = key;
        this.amount = amount;
        this.returned = returned;
        this.uses = uses;
        this.remainder = remainder;
        this.reusableStockSource = reusableStockSource;
    }

    public K key() {
        return this.key;
    }

    public long amount() {
        return this.amount;
    }

    public boolean returned() {
        return this.returned;
    }

    public long uses() {
        return this.uses;
    }

    public K remainder() {
        return this.remainder;
    }

    public ReusableStockSource reusableStockSource() {
        return this.reusableStockSource;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CraftInput)) {
            return false;
        }
        CraftInput<?> other = (CraftInput<?>) o;
        return this.amount == other.amount
                && this.returned == other.returned
                && this.uses == other.uses
                && Objects.equals(this.key, other.key)
                && Objects.equals(this.remainder, other.remainder)
                && Objects.equals(this.reusableStockSource, other.reusableStockSource);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.key, this.amount, this.returned, this.uses, this.remainder,
                this.reusableStockSource);
    }

    @Override
    public String toString() {
        return "CraftInput[key=" + this.key
                + ", amount=" + this.amount
                + ", returned=" + this.returned
                + ", uses=" + this.uses
                + ", remainder=" + this.remainder
                + ", reusableStockSource=" + this.reusableStockSource + "]";
    }
}

package com.moakiee.thunderbolt.core.planner;

/**
 * Saturating non-negative {@code long} arithmetic.
 *
 * <p>Crafting amounts can grow geometrically (≈ q·m^depth) and overflow 64 bits long before any
 * realistic inventory could satisfy them. Instead of wrapping (which would silently corrupt a plan),
 * all amounts clamp to {@link #SAT}. Anything at {@code SAT} is treated as "more than any storage can
 * provide" downstream, so it surfaces as a missing/infeasible result rather than a wrong plan.
 */
public final class Sat {

    /** Sentinel for "effectively infinite". Kept well below {@link Long#MAX_VALUE} so sums stay finite. */
    public static final long SAT = Long.MAX_VALUE / 4;

    private Sat() {
    }

    public static boolean isSaturated(long value) {
        return value >= SAT;
    }

    public static long add(long a, long b) {
        long r = a + b;
        return (r >= SAT || r < 0) ? SAT : r;
    }

    public static long mul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        if (a >= SAT || b >= SAT || a > SAT / b) {
            return SAT;
        }
        long r = a * b;
        return (r >= SAT || r < 0) ? SAT : r;
    }

    /** Ceiling division for non-negative inputs; saturates. {@code div} must be {@code > 0}. */
    public static long ceilDiv(long value, long div) {
        if (value >= SAT) {
            return SAT;
        }
        if (value == 0) {
            return 0;
        }
        // (value - 1) / div + 1 == ceil(value / div) without the "+ div" that could overflow for a
        // huge divisor (value - 1 < SAT, so no addition ever wraps).
        return (value - 1) / div + 1;
    }
}

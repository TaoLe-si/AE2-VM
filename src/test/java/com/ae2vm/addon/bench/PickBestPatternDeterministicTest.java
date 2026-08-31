package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.api.AE2VMCrafting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * (v1.15.x DETERMINISTIC) Verifies that {@link AE2VMCrafting#pickBestPattern}
 * returns the SAME pattern regardless of iteration order of the input
 * collection. Cold start vs warm path can hit {@code getCraftingFor} in
 * different orders (provider map updates, lastAccess bookkeeping); without
 * a stable tiebreak, two candidates with identical per-craft output and
 * identical input sum would be picked differently — breaking the JIT
 * fast-path equivalence check and confusing the user.
 *
 * <p>Uses {@link BenchAEKey} + {@link BenchPatternDetails} to build two
 * candidate patterns with identical outputs/inputs, then shuffles the input
 * collection many times and asserts the same winner.</p>
 */
class PickBestPatternDeterministicTest {

    @Test
    void pickBestPattern_isStableAcrossIterationOrders() {
        BenchAEKey target = BenchAEKey.of("gtceu:cosmicneutronium");
        // Two candidates with identical primary output (1000) and identical
        // total input (1+1 = 2). Without a stable tiebreak, iteration order
        // would determine which one wins.
        IPatternDetails p1 = pat(target, 1000L, List.of(
                in(BenchAEKey.of("kubejs:plasma_a"), 1L),
                in(BenchAEKey.of("kubejs:plasma_b"), 1L)));
        IPatternDetails p2 = pat(target, 1000L, List.of(
                in(BenchAEKey.of("kubejs:plasma_a"), 1L),
                in(BenchAEKey.of("kubejs:plasma_b"), 1L)));

        // Original order
        IPatternDetails winner = AE2VMCrafting.pickBestPattern(Arrays.asList(p1, p2), target);
        // Reversed order
        IPatternDetails winnerReversed = AE2VMCrafting.pickBestPattern(Arrays.asList(p2, p1), target);
        assertSame(winner, winnerReversed,
                "pickBestPattern must be deterministic across iteration order");

        // Shuffled (a few times — the JDK Collections.shuffle uses a stable RNG seed)
        for (int seed = 0; seed < 8; seed++) {
            List<IPatternDetails> pool = new ArrayList<>(Arrays.asList(p1, p2));
            Collections.shuffle(pool, new java.util.Random(seed));
            IPatternDetails w = AE2VMCrafting.pickBestPattern(pool, target);
            assertSame(winner, w,
                    "shuffle seed=" + seed + " produced different winner");
        }
    }

    @Test
    void pickBestPattern_smallerOutputAlwaysWins() {
        BenchAEKey target = BenchAEKey.of("gtceu:dust");
        // p_small: out=1 (prefer this)
        IPatternDetails pSmall = pat(target, 1L, List.of(in(BenchAEKey.of("gtceu:ingot"), 1L)));
        // p_big: out=144 (worse — needs more sub-crafts)
        IPatternDetails pBig = pat(target, 144L, List.of(in(BenchAEKey.of("gtceu:chunk"), 1L)));
        assertSame(pSmall, AE2VMCrafting.pickBestPattern(Arrays.asList(pSmall, pBig), target));
        assertSame(pSmall, AE2VMCrafting.pickBestPattern(Arrays.asList(pBig, pSmall), target));
    }

    // ---------- helpers ----------
    private static IPatternDetails pat(BenchAEKey out, long outCount, List<BenchPatternDetails.InputSpec> ins) {
        return new BenchPatternDetails(out, outCount, ins);
    }

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }
}
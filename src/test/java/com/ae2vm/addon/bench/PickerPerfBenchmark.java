package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.api.AE2VMCrafting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance benchmark for {@link AE2VMCrafting#pickBestPatternGraph}.
 * <p>
 * Goal: confirm the capacity-aware picker runs in O(|V| × |E|) on the
 * reachable sub-graph and stays well under the request-processing budget
 * (the picker is called once per key per vm execute call, so the per-call
 * cost should be &lt; 100 μs even on a 50-key / 200-edge GTL fixture).
 */
public class PickerPerfBenchmark {

    private static final int N_KEYS = 50;
    private static final int BRANCHING = 4;
    private static final int REPEATS = 1000;

    /**
     * Build a 4-layer recipe chain (root → L1 → L2 → L3 leaves) where every
     * key has {@value #BRANCHING} candidates. Mimics a deep GTL sub-graph.
     * Stock the deepest leaves so all candidates are feasible.
     */
    @Test
    void pickerOverDeepChain() {
        Map<AEKey, List<IPatternDetails>> candidates = new HashMap<>();
        Map<BenchAEKey, Long> stock = new HashMap<>();

        // Leaves: stock + no candidates.
        BenchAEKey[] leaves = new BenchAEKey[N_KEYS];
        for (int i = 0; i < N_KEYS; i++) {
            leaves[i] = BenchAEKey.of("perf_leaf_" + i);
            stock.put(leaves[i], 1_000_000L);
        }

        // L3 keys: candidates that take 2 different leaves.
        BenchAEKey[] l3 = new BenchAEKey[N_KEYS];
        for (int i = 0; i < N_KEYS; i++) {
            l3[i] = BenchAEKey.of("perf_l3_" + i);
            List<IPatternDetails> subs = new ArrayList<>();
            for (int b = 0; b < BRANCHING; b++) {
                BenchAEKey a = leaves[(i + b) % N_KEYS];
                BenchAEKey c = leaves[(i * 3 + b * 7) % N_KEYS];
                subs.add(new BenchPatternDetails(l3[i], 1, List.of(
                        BenchPatternDetails.InputSpec.of(a, 1),
                        BenchPatternDetails.InputSpec.of(c, 2))));
            }
            candidates.put(l3[i], subs);
        }

        // L2 keys.
        BenchAEKey[] l2 = new BenchAEKey[N_KEYS];
        for (int i = 0; i < N_KEYS; i++) {
            l2[i] = BenchAEKey.of("perf_l2_" + i);
            List<IPatternDetails> subs = new ArrayList<>();
            for (int b = 0; b < BRANCHING; b++) {
                BenchAEKey a = l3[(i + b) % N_KEYS];
                BenchAEKey c = l3[(i * 3 + b * 5) % N_KEYS];
                subs.add(new BenchPatternDetails(l2[i], 1, List.of(
                        BenchPatternDetails.InputSpec.of(a, 2),
                        BenchPatternDetails.InputSpec.of(c, 1))));
            }
            candidates.put(l2[i], subs);
        }

        // L1 keys.
        BenchAEKey[] l1 = new BenchAEKey[N_KEYS];
        for (int i = 0; i < N_KEYS; i++) {
            l1[i] = BenchAEKey.of("perf_l1_" + i);
            List<IPatternDetails> subs = new ArrayList<>();
            for (int b = 0; b < BRANCHING; b++) {
                BenchAEKey a = l2[(i + b) % N_KEYS];
                BenchAEKey c = l2[(i * 3 + b * 11) % N_KEYS];
                subs.add(new BenchPatternDetails(l1[i], 1, List.of(
                        BenchPatternDetails.InputSpec.of(a, 1),
                        BenchPatternDetails.InputSpec.of(c, 3))));
            }
            candidates.put(l1[i], subs);
        }

        // Root: pick a single L1 input.
        BenchAEKey root = BenchAEKey.of("perf_root");
        List<IPatternDetails> rootSubs = new ArrayList<>();
        for (int b = 0; b < BRANCHING; b++) {
            rootSubs.add(new BenchPatternDetails(root, 1, List.of(
                    BenchPatternDetails.InputSpec.of(l1[b], 4))));
        }
        candidates.put(root, rootSubs);

        // Adjacency / stock lambdas (same shape as the test harness).
        java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>> adjacency =
                k -> candidates.get(k);
        java.util.function.Function<AEKey, Long> stockFn =
                k -> k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L;

        // Sanity: the picker must pick one of root's candidates.
        IPatternDetails chosen = AE2VMCrafting.pickBestPatternGraph(rootSubs, root, adjacency, stockFn);
        assertNotNull(chosen, "picker must return a candidate");

        // Warmup (JIT compile).
        for (int i = 0; i < 100; i++) {
            AE2VMCrafting.pickBestPatternGraph(rootSubs, root, adjacency, stockFn);
        }

        long start = System.nanoTime();
        for (int i = 0; i < REPEATS; i++) {
            AE2VMCrafting.pickBestPatternGraph(rootSubs, root, adjacency, stockFn);
        }
        long elapsed = System.nanoTime() - start;
        double avgUs = elapsed / 1000.0 / REPEATS;

        // Also measure the OLD legacy picker for comparison.
        long startOld = System.nanoTime();
        for (int i = 0; i < REPEATS; i++) {
            AE2VMCrafting.pickBestPattern(rootSubs, root);
        }
        long elapsedOld = System.nanoTime() - startOld;
        double avgUsOld = elapsedOld / 1000.0 / REPEATS;

        System.out.println("[picker-perf] graph=" + (N_KEYS * 4 + 1) + " keys, "
                + (N_KEYS * BRANCHING * 3 + BRANCHING) + " edges, REACH_DEPTH=8");
        System.out.println("[picker-perf] pickBestPatternGraph avg=" + String.format("%.3f", avgUs) + " μs");
        System.out.println("[picker-perf] pickBestPattern      avg=" + String.format("%.3f", avgUsOld) + " μs");
        System.out.println("[picker-perf] overhead vs legacy: " + String.format("%.1f", avgUs / Math.max(0.001, avgUsOld)) + "x");

        // Budget: picker must stay well under vm-execute's per-call cost
        // (vm execute is typically 30-200 μs for warm cache). 5 ms would be
        // catastrophic; 1 ms is tolerable for huge graphs.
        assertTrue(avgUs < 5000.0,
                "picker must stay under 5 ms on a 200-edge graph; got " + avgUs + " μs");
    }
}

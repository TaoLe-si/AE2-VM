package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Bounded, overflow-free enumeration of the "best" combinations across per-slot option lists.
 *
 * <p>Used by the hard-fuzzy (OR) expansion: each input slot accepts several concrete substitutes, and a
 * recipe is the cartesian product of one choice per slot. That product can be astronomically large (many
 * slots over big tags), so instead of either enumerating it all (hang / Long overflow) or dropping the
 * whole recipe when it overruns a budget (false negative for something craftable), we keep only the best
 * {@code limit} combinations.
 *
 * <p>"Best" = lowest rank-sum, where each slot's options are assumed pre-sorted best-first (e.g. most
 * available substitute at index 0). Starting from the all-best vector we pop the lowest rank-sum index
 * vector and push its single-step neighbors; since every neighbor's rank-sum is strictly larger, the
 * first {@code limit} popped vectors are exactly the {@code limit} cheapest combinations. Work is bounded
 * by {@code O(limit * slots * log(limit * slots))} regardless of the true product size.
 */
public final class BoundedCombinations {

    private BoundedCombinations() {
    }

    /**
     * @param slots per-slot option lists, each ordered best-first
     * @param limit maximum number of combinations to return (must be &gt; 0 to yield anything)
     * @return up to {@code limit} combinations, lowest rank-sum first; empty if any slot has no options
     */
    public static <T> List<List<T>> bestFirst(List<List<T>> slots, int limit) {
        List<List<T>> out = new ArrayList<>();
        if (limit <= 0) {
            return out;
        }
        int n = slots.size();
        for (List<T> slot : slots) {
            if (slot.isEmpty()) {
                return out; // an unsatisfiable slot => no combination exists
            }
        }
        int[] start = new int[n + 1]; // [0..n-1] = per-slot option index, [n] = rank sum (priority)
        PriorityQueue<int[]> heap = new PriorityQueue<>(Comparator.comparingInt(a -> a[n]));
        Set<String> visited = new HashSet<>();
        heap.add(start);
        visited.add(key(start, n));
        while (!heap.isEmpty() && out.size() < limit) {
            int[] cur = heap.poll();
            List<T> combo = new ArrayList<>(n);
            for (int s = 0; s < n; s++) {
                combo.add(slots.get(s).get(cur[s]));
            }
            out.add(combo);
            for (int s = 0; s < n; s++) {
                if (cur[s] + 1 < slots.get(s).size()) {
                    int[] next = cur.clone();
                    next[s]++;
                    next[n]++;
                    String id = key(next, n);
                    if (visited.add(id)) {
                        heap.add(next);
                    }
                }
            }
        }
        return out;
    }

    private static String key(int[] idx, int n) {
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            sb.append(idx[i]).append(',');
        }
        return sb.toString();
    }
}

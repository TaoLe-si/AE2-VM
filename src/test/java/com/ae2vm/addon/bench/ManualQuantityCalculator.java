package com.ae2vm.addon.bench;

import appeng.api.stacks.AEKey;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * (v1.14.x GTL TOOL) Manual quantity calculator — ground truth for the VM.
 *
 * <p>Input: the item to synthesize ({@code root} × {@code rootAmount}) plus the
 * recipe table (each recipe: output key, output amount per craft, per-craft input
 * needs). Optionally the network stock snapshot (stock-aware: consumed first,
 * only the deficit is crafted). Output:
 * <ul>
 *   <li>{@link Result#demand()} — TOTAL units demanded per key (same semantics as
 *       the VM's aggregated itemDemand: every unit every downstream recipe needs,
 *       before stock);</li>
 *   <li>{@link Result#crafts()} — craft counts per OUTPUT KEY (same semantics as
 *       the VM's patternTimes: {@code ceil((demand - stock) / outputPerCraft)});</li>
 *   <li>{@link Result#consumed()} — stock units consumed per key;</li>
 *   <li>{@link Result#missing()} — shortfall of keys with NO recipe (leaf), in
 *       stock-aware mode.</li>
 * </ul>
 *
 * <p>The propagation is a demand-delta worklist: re-processing a key only pushes
 * the INCREASE of its craft count to its inputs, so DAGs converge and cycles
 * terminate (a cyclic key's demand grows by a fixed point; use the plain-DAG
 * mode or the VM's own ring tests for cycle semantics).
 */
public final class ManualQuantityCalculator {

    /** A recipe: {@code inputs → outAmt × out}. */
    public record Recipe(AEKey out, long outAmt, Map<AEKey, Long> inputs) {
        public static Recipe of(AEKey out, long outAmt, Map<AEKey, Long> inputs) {
            return new Recipe(out, outAmt, new LinkedHashMap<>(inputs));
        }
    }

    public record Result(
            Map<AEKey, Long> demand,
            Map<AEKey, Long> crafts,
            Map<AEKey, Long> consumed,
            Map<AEKey, Long> missing) {

        public static Result empty() {
            return new Result(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        }

        /** Keys the recipe table declares as produced (have a recipe). */
        public Set<AEKey> producibleKeys() {
            Set<AEKey> s = new HashSet<>();
            for (Map.Entry<AEKey, Long> e : crafts.entrySet()) s.add(e.getKey());
            return s;
        }
    }

    /**
     * Compute total quantities for {@code root × rootAmount}.
     *
     * @param recipes recipe table (output key → recipe); keys without a recipe are leaves
     * @param root    the item to synthesize
     * @param rootAmount how many units of the root
     * @param stock   optional live network stock (null/empty = no stock; every unit is crafted)
     */
    public static Result calculate(Map<AEKey, Recipe> recipes, AEKey root, long rootAmount, Map<AEKey, Long> stock) {
        Map<AEKey, Long> demand = new HashMap<>();      // total units demanded (before stock)
        Map<AEKey, Long> crafts = new HashMap<>();      // craft counts per output key
        Map<AEKey, Long> consumed = new HashMap<>();    // stock units consumed
        Map<AEKey, Long> pending = new HashMap<>();     // demand delta still to propagate
        Deque<AEKey> queue = new ArrayDeque<>();

        demand.put(root, rootAmount);
        pending.put(root, rootAmount);
        queue.add(root);

        // worklist: pop a key, apply its pending demand delta, recompute its craft
        // count; only the INCREASE is pushed down to inputs.
        while (!queue.isEmpty()) {
            AEKey k = queue.poll();
            Long delta = pending.remove(k);
            if (delta == null || delta <= 0) continue;
            Recipe r = recipes.get(k);
            if (r == null) continue; // leaf: shortfall handled in the final pass

            long d = demand.getOrDefault(k, 0L);
            long stockAvail = stock == null ? 0L : stock.getOrDefault(k, 0L);
            long toCraft = Math.max(0L, d - stockAvail);
            long oldCrafts = crafts.getOrDefault(k, 0L);
            long newCrafts = toCraft == 0 ? 0L : (toCraft + r.outAmt() - 1) / r.outAmt();
            if (newCrafts <= oldCrafts) continue;
            long extra = newCrafts - oldCrafts;
            crafts.put(k, newCrafts);
            for (Map.Entry<AEKey, Long> in : r.inputs().entrySet()) {
                long add = in.getValue() * extra;
                demand.merge(in.getKey(), add, Long::sum);
                pending.merge(in.getKey(), add, Long::sum);
                queue.add(in.getKey());
            }
        }

        // stock consumption (exact units, per key: min(stock, demand))
        if (stock != null) {
            for (Map.Entry<AEKey, Long> e : demand.entrySet()) {
                long s = stock.getOrDefault(e.getKey(), 0L);
                if (s > 0) consumed.put(e.getKey(), Math.min(s, e.getValue()));
            }
        }

        // leaf shortfall: demanded keys without a recipe, beyond their stock
        Map<AEKey, Long> missing = new HashMap<>();
        for (Map.Entry<AEKey, Long> e : demand.entrySet()) {
            if (recipes.containsKey(e.getKey())) continue;
            long s = stock == null ? 0L : stock.getOrDefault(e.getKey(), 0L);
            long m = e.getValue() - s;
            if (m > 0) missing.put(e.getKey(), m);
        }

        return new Result(demand, crafts, consumed, missing);
    }

    private ManualQuantityCalculator() {}
}

package com.moakiee.thunderbolt.core.planner;

import com.ae2vm.addon.bench.J8;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot the planner runs against: patterns indexed by their output item, plus a
 * read-only inventory snapshot. The AE2 adapter builds this from {@code ICraftingService} +
 * the network storage snapshot; tests build it directly with {@code String} keys.
 *
 * @param <K> item key type
 */
public final class CraftGraph<K> {

    private final Map<K, List<CraftPattern<K>>> patternsByOutput;
    private final Map<K, Long> stock;
    private final Map<ReusableStockKey<K>, Long> reusableStock;
    private final Map<ReusableStockRouteKey<K>, List<K>> reusableStockRoutes;

    private CraftGraph(Map<K, List<CraftPattern<K>>> patternsByOutput, Map<K, Long> stock,
                       Map<ReusableStockKey<K>, Long> reusableStock,
                       Map<ReusableStockRouteKey<K>, List<K>> reusableStockRoutes) {
        this.patternsByOutput = patternsByOutput;
        this.stock = stock;
        this.reusableStock = reusableStock;
        this.reusableStockRoutes = reusableStockRoutes;
    }

    /** Patterns whose primary output is {@code key}, in caller-defined preference order. */
    public List<CraftPattern<K>> patternsFor(K key) {
        return patternsByOutput.getOrDefault(key, J8.list());
    }

    /** Available amount of {@code key} in the snapshot (0 if none). */
    public long stock(K key) {
        Long v = stock.get(key);
        return v == null ? 0L : Math.max(0L, v);
    }

    /** Host-private stock is invisible to ordinary demands and is addressed by reusable inputs only. */
    public long reusableStock(Object scope, K key) {
        Long value = reusableStock.get(new ReusableStockKey<>(scope, key));
        return value == null ? 0L : Math.max(0L, value);
    }

    /** Total physical stock accepted by this exact pattern route (capacity estimate only). */
    public long reusableStock(ReusableStockSource source, K plannedKey) {
        long total = 0L;
        for (K actual : reusableStockCandidates(source, plannedKey)) {
            PlanningCancellation.check();
            total = Sat.add(total, reusableStock(source.storageScope(), actual));
        }
        return total;
    }

    public List<K> reusableStockCandidates(ReusableStockSource source, K plannedKey) {
        ReusableStockRouteKey<K> route = new ReusableStockRouteKey<K>(source, plannedKey);
        List<K> candidates = reusableStockRoutes.get(route);
        return candidates != null ? candidates : J8.list(plannedKey);
    }

    Map<ReusableStockKey<K>, Long> reusableStock() {
        return reusableStock;
    }

    public static <K> Builder<K> builder() {
        return new Builder<>();
    }

    public static final class Builder<K> {
        private final Map<K, List<CraftPattern<K>>> patterns = new HashMap<>();
        private final Map<K, Long> stock = new HashMap<>();
        private final Map<ReusableStockKey<K>, Long> reusableStock = new HashMap<>();
        private final Map<ReusableStockRouteKey<K>, LinkedHashSet<K>> reusableStockRoutes =
                new HashMap<>();

        /** Adds a pattern; patterns for the same output keep insertion order (= preference order). */
        public Builder<K> pattern(CraftPattern<K> pattern) {
            patterns.computeIfAbsent(pattern.output(), k -> new ArrayList<>()).add(pattern);
            return this;
        }

        public Builder<K> pattern(K output, long outAmount, List<CraftInput<K>> inputs) {
            return pattern(new CraftPattern<>(output, outAmount, inputs, null));
        }

        public Builder<K> pattern(K output, long outAmount, List<CraftInput<K>> inputs,
                                  List<CraftOutput<K>> byproducts) {
            return pattern(new CraftPattern<>(output, outAmount, inputs, byproducts, null));
        }

        public Builder<K> stock(K key, long amount) {
            // Saturating: a durability carrier's aggregate uses can already sit at the saturation
            // cap; a plain Long::sum could overflow negative and stock() would clamp it to zero,
            // turning a huge supply into a false shortfall.
            stock.merge(key, amount, Sat::add);
            return this;
        }

        /**
         * Publishes one snapshot of a physical host inventory. Repeated patterns from the same host
         * report the same inventory, so snapshots merge by maximum rather than being double-counted.
         */
        public Builder<K> reusableStock(Object scope, K key, long amount) {
            if (amount > 0) {
                reusableStock.merge(new ReusableStockKey<>(scope, key), amount, Math::max);
            }
            return this;
        }

        /** Registers the concrete physical variants accepted by one pattern-specific seed route. */
        public Builder<K> reusableStockRoute(
                ReusableStockSource source, K plannedKey, Iterable<? extends K> actualVariants) {
            ReusableStockRouteKey<K> route = new ReusableStockRouteKey<K>(source, plannedKey);
            LinkedHashSet<K> accepted = reusableStockRoutes.computeIfAbsent(route,
                    ignored -> new LinkedHashSet<K>());
            for (K actual : actualVariants) {
                PlanningCancellation.check();
                if (actual != null) accepted.add(actual);
            }
            return this;
        }

        public CraftGraph<K> build() {
            Map<K, List<CraftPattern<K>>> frozen = new HashMap<>();
            for (Map.Entry<K, List<CraftPattern<K>>> entry : patterns.entrySet()) {
                PlanningCancellation.check();
                frozen.put(entry.getKey(), J8.copyOf(entry.getValue()));
            }
            Map<ReusableStockRouteKey<K>, List<K>> frozenRoutes =
                    new HashMap<ReusableStockRouteKey<K>, List<K>>();
            for (Map.Entry<ReusableStockRouteKey<K>, LinkedHashSet<K>> entry
                    : reusableStockRoutes.entrySet()) {
                PlanningCancellation.check();
                frozenRoutes.put(entry.getKey(), J8.copyOf(entry.getValue()));
            }
            return new CraftGraph<>(frozen, J8.mapCopyOf(stock), J8.mapCopyOf(reusableStock),
                    J8.mapCopyOf(frozenRoutes));
        }
    }
}

package com.moakiee.thunderbolt.core.planner.reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import com.moakiee.thunderbolt.core.planner.CraftOutput;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.ReusableStockSource;

/** Canonical offline cases from the author-facing reference standard. */
public final class ThunderboltReferenceScenarios {
    private static final long UNBOUNDED_STOCK = 1_000_000_000_000L;

    private ThunderboltReferenceScenarios() {
    }

    public static List<ReferenceScenario> all() {
        var result = new ArrayList<ReferenceScenario>();
        addDispersedSingleDag(result);
        addFibonacciSingleDag(result, 32);
        addGreedyTrapMultiDag(result, 64);
        addFibonacciMultiDag(result, 12);
        addConversionCycle(result);
        addSelfGrowthCycle(result, 2);
        addCatalyst(result, 1_000);
        addRawCatalystLoop(result, 8);
        addLossyFeedbackLoop(result, 8);
        addDurability(result, 100, 10_000);
        addFuzzyVariant(result, 1_000);
        addRecursionAmplifier(result, 8);
        addRecursionEssenceCatalyst(result, 8);
        return List.copyOf(result);
    }

    private static void addDispersedSingleDag(List<ReferenceScenario> out) {
        Map<String, Long> minimum = Map.of("D", 4L, "E", 4L, "F", 4L, "G", 4L);
        Map<String, Long> starved = Map.of("D", 2L, "E", 4L, "F", 4L, "G", 3L);
        Map<String, Long> missing = Map.of("D", 2L, "G", 1L);
        addThreeModes(out, "single-dag/dispersed", ReferenceCapability.SINGLE_DAG, 3,
                "A", 4, minimum, starved, List.of(missing),
                ThunderboltReferenceScenarios::dispersedSingleDag);
    }

    private static CraftGraph<String> dispersedSingleDag(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("A", 1, List.of(CraftInput.of("B", 1), CraftInput.of("C", 1)))
                .pattern("B", 1, List.of(CraftInput.of("D", 1), CraftInput.of("E", 1)))
                .pattern("C", 1, List.of(CraftInput.of("F", 1), CraftInput.of("G", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addFibonacciSingleDag(List<ReferenceScenario> out, int depth) {
        Map<String, Long> minimum = fibonacciSingleDemand(depth);
        Map<String, Long> starved = new LinkedHashMap<>(minimum);
        starved.compute("X0", (key, value) -> value - 3L);
        starved.compute("X1", (key, value) -> value - 5L);
        addThreeModes(out, "single-dag/fibonacci", ReferenceCapability.SINGLE_DAG, depth,
                "X" + depth, 1, minimum, starved, List.of(Map.of("X0", 3L, "X1", 5L)),
                stock -> fibonacciSingleDag(depth, stock));
    }

    private static CraftGraph<String> fibonacciSingleDag(int depth, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder();
        for (int i = 2; i <= depth; i++) {
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 1), 1),
                    CraftInput.of("X" + (i - 2), 1)));
        }
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static Map<String, Long> fibonacciSingleDemand(int depth) {
        Map<String, Long> x0 = Map.of("X0", 1L);
        Map<String, Long> x1 = Map.of("X1", 1L);
        for (int i = 2; i <= depth; i++) {
            var next = addDemand(x0, x1);
            x0 = x1;
            x1 = next;
        }
        return x1;
    }

    private static void addGreedyTrapMultiDag(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("R", (long) amount, "S", (long) amount);
        Map<String, Long> starved = Map.of("R", (long) amount);
        List<Map<String, Long>> missing = List.of(
                Map.of("R", (long) amount), Map.of("S", (long) amount));
        addThreeModes(out, "multi-dag/greedy-trap", ReferenceCapability.MULTI_DAG, amount,
                "Z", amount, minimum, starved, missing,
                ThunderboltReferenceScenarios::greedyTrapMultiDag);
    }

    private static CraftGraph<String> greedyTrapMultiDag(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("Z", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)))
                .pattern("A", 1, List.of(CraftInput.of("R", 1)))
                // R is deliberately registered before S: consuming it for B is a local greedy trap.
                .pattern("B", 1, List.of(CraftInput.of("R", 1)))
                .pattern("B", 1, List.of(CraftInput.of("S", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addFibonacciMultiDag(List<ReferenceScenario> out, int depth) {
        List<Map<String, Long>> frontier = fibonacciMultiMinimumFrontier(depth);
        Map<String, Long> minimum = frontier.get(0);
        addThreeModes(out, "multi-dag/fibonacci", ReferenceCapability.MULTI_DAG, depth,
                "X" + depth, 1, minimum, Map.of(), frontier,
                stock -> fibonacciMultiDag(depth, stock));
    }

    private static CraftGraph<String> fibonacciMultiDag(int depth, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder();
        for (int i = 3; i <= depth; i++) {
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 1), 1),
                    CraftInput.of("X" + (i - 2), 1)));
            builder.pattern("X" + i, 1, List.of(
                    CraftInput.of("X" + (i - 2), 1),
                    CraftInput.of("X" + (i - 3), 1)));
        }
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static List<Map<String, Long>> fibonacciMultiMinimumFrontier(int depth) {
        var options = new ArrayList<List<Map<String, Long>>>();
        options.add(List.of(Map.of("X0", 1L)));
        options.add(List.of(Map.of("X1", 1L)));
        options.add(List.of(Map.of("X2", 1L)));
        for (int i = 3; i <= depth; i++) {
            var candidates = new ArrayList<Map<String, Long>>();
            combine(options.get(i - 1), options.get(i - 2), candidates);
            combine(options.get(i - 2), options.get(i - 3), candidates);
            long minimumCost = candidates.stream().mapToLong(ThunderboltReferenceScenarios::total).min()
                    .orElseThrow();
            options.add(candidates.stream()
                    .filter(candidate -> total(candidate) == minimumCost)
                    .distinct()
                    .sorted(Comparator.comparing(Map::toString))
                    .toList());
        }
        return options.get(depth);
    }

    private static void combine(
            List<Map<String, Long>> left,
            List<Map<String, Long>> right,
            List<Map<String, Long>> destination) {
        for (var a : left) {
            for (var b : right) {
                destination.add(addDemand(a, b));
            }
        }
    }

    private static void addConversionCycle(List<ReferenceScenario> out) {
        addThreeModes(out, "cycle/conversion-ring", ReferenceCapability.CYCLE_CUTTING, 3,
                "T", 1, Map.of("A", 2L), Map.of("A", 1L),
                List.of(Map.of("A", 1L), Map.of("C", 1L)),
                ThunderboltReferenceScenarios::conversionCycle);
        // (v1.15.x GTL 1:1) Iron-dust ↔ iron-ingot: a by-product-free SCC where every
        // orientation is a 1:1 exchange. The ring is value-conserving (1 ingot = 1 dust)
        // and a stocked dust can satisfy an ingot demand without firing any pattern.
        // The previous 9:1 / 1:9 ring above never reached this shape (uneven ratios
        // produce non-trivial exchange values); the GTL bedrock_drill bug — missing
        // iron_ingot when iron_dust is stocked — is exactly this 1:1 case.
        addThreeModes(out, "cycle/conversion-ring-1to1", ReferenceCapability.CYCLE_CUTTING, 3,
                "T", 1, Map.of("D", 3L), Map.of("D", 1L),
                List.of(Map.of("D", 2L)),
                ThunderboltReferenceScenarios::conversionCycle1to1);
    }

    private static CraftGraph<String> conversionCycle(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("A", 1), CraftInput.of("C", 1)))
                .pattern("A", 1, List.of(CraftInput.of("B", 9)))
                .pattern("B", 9, List.of(CraftInput.of("A", 1)))
                .pattern("B", 1, List.of(CraftInput.of("C", 9)))
                .pattern("C", 9, List.of(CraftInput.of("B", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    /** 1:1 pure conversion: D ↔ E smelt/pulverize, external demand on E through T. */
    private static CraftGraph<String> conversionCycle1to1(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("T", 1, List.of(CraftInput.of("D", 1), CraftInput.of("E", 1)))
                .pattern("D", 1, List.of(CraftInput.of("E", 1)))
                .pattern("E", 1, List.of(CraftInput.of("D", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addSelfGrowthCycle(List<ReferenceScenario> out, int amount) {
        Predicate<CraftPlan<String>> cutWithoutFiring = plan -> plan.firings().isEmpty();
        addScenario(out, "cycle/self-growth-cut", ReferenceCapability.CYCLE_CUTTING,
                ReferenceMaterialMode.MISSING, amount, selfGrowthCycle(Map.of()),
                "A", amount, false, List.of(Map.of("A", (long) amount)), cutWithoutFiring);
        addScenario(out, "cycle/self-growth-cut", ReferenceCapability.CYCLE_CUTTING,
                ReferenceMaterialMode.MINIMUM, amount, selfGrowthCycle(Map.of("A", 1L)),
                "A", amount, false, List.of(Map.of("A", amount - 1L)), cutWithoutFiring);
        addScenario(out, "cycle/self-growth-cut", ReferenceCapability.CYCLE_CUTTING,
                ReferenceMaterialMode.UNBOUNDED, amount,
                selfGrowthCycle(Map.of("A", UNBOUNDED_STOCK)),
                "A", amount, true, List.of(), cutWithoutFiring);
    }

    /** Completion is optional, but an unseeded A->2A loop must never invent its first A. */
    private static CraftGraph<String> selfGrowthCycle(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("A", 2, List.of(CraftInput.of("A", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    /**
     * (v1.10.3 RECURSION) Seeded self-amplifier: {@code A + B -> 2A}. Unlike the
     * unseeded A->2A loop this has an EXTERNAL seed ({@code B}), so it is a legitimate
     * recipe — each craft nets +1 {@code A} (out 2 − in 1), so with a one-time
     * {@code A=1} seed and {@code amount−1} {@code B} a batch of {@code amount} A is
     * craftable. Without the {@code A} seed the loop cannot be primed → exactly
     * {@code A=1} is missing.
     */
    private static void addRecursionAmplifier(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("A", 1L, "B", (long) amount - 1L);
        Map<String, Long> starved = Map.of("B", (long) amount - 1L);
        addThreeModes(out, "recursion/amplifier", ReferenceCapability.RECURSION, amount,
                "A", amount, minimum, starved, List.of(Map.of("A", 1L)),
                ThunderboltReferenceScenarios::recursionAmplifier);
    }

    /** Amplifier: consume one A + one B, return two A (net +1 A per craft). */
    private static CraftGraph<String> recursionAmplifier(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("A", 2, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    /**
     * (v1.10.3 RECURSION) A-A catalyst (essence): {@code A + B -> A + C}. The catalyst
     * {@code A} is BOTH a consumed input and a produced byproduct, so it circulates
     * forever: a batch of {@code amount} C needs one {@code A=1} seed + {@code amount}
     * {@code B}. Without the {@code A} seed exactly {@code A=1} is missing. This is the
     * Mystical-Agriculture-style essence recipe (essence stays, {@code B} turns into C).
     */
    private static void addRecursionEssenceCatalyst(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("A", 1L, "B", (long) amount);
        Map<String, Long> starved = Map.of("B", (long) amount);
        addThreeModes(out, "recursion/essence-catalyst", ReferenceCapability.RECURSION, amount,
                "C", amount, minimum, starved, List.of(Map.of("A", 1L)),
                ThunderboltReferenceScenarios::recursionEssenceCatalyst);
    }

    /** Essence catalyst: consume one A + one B, return A (byproduct) + C. */
    private static CraftGraph<String> recursionEssenceCatalyst(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("C", 1, List.of(CraftInput.of("A", 1), CraftInput.of("B", 1)),
                        List.of(CraftOutput.of("A", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addCatalyst(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("A", 1L, "C", (long) amount);
        Map<String, Long> starved = Map.of("C", (long) amount);
        addThreeModes(out, "catalyst/returned-seed", ReferenceCapability.CATALYST, amount,
                "E", amount, minimum, starved, List.of(Map.of("A", 1L)),
                ThunderboltReferenceScenarios::catalyst);
    }

    /** Direct unchanged-catalyst notation: one ordinary network A is returned after every firing. */
    private static CraftGraph<String> catalyst(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("E", 1, List.of(
                        CraftInput.returned("A", 1),
                        CraftInput.of("C", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addRawCatalystLoop(List<ReferenceScenario> out, int amount) {
        Map<String, Long> minimum = Map.of("A", 1L, "C", (long) amount);
        Map<String, Long> starved = Map.of("C", (long) amount);
        addThreeModes(out, "catalyst/raw-feedback-loop", ReferenceCapability.CATALYST, amount,
                "E", amount, minimum, starved, List.of(Map.of("A", 1L)),
                ThunderboltReferenceScenarios::rawCatalystLoop);
    }

    /** Ordinary balanced catalyst cycle: A->2B, 2B+C->E+D, D->A. */
    private static CraftGraph<String> rawCatalystLoop(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("B", 2, List.of(CraftInput.of("A", 1)))
                .pattern("E", 1,
                        List.of(CraftInput.of("B", 2), CraftInput.of("C", 1)),
                        List.of(CraftOutput.of("D", 1)))
                .pattern("A", 1, List.of(CraftInput.of("D", 1)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addLossyFeedbackLoop(List<ReferenceScenario> out, int amount) {
        long minimumA = amount + 2L;
        addThreeModes(out, "catalyst/lossy-feedback-loop", ReferenceCapability.CATALYST, amount,
                "D", amount, Map.of("A", minimumA), Map.of("A", (long) amount),
                List.of(Map.of("A", 2L)), ThunderboltReferenceScenarios::lossyFeedbackLoop);
    }

    /** Ordinary decreasing feedback: each D consumes one A net and retains a two-A startup state. */
    private static CraftGraph<String> lossyFeedbackLoop(Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("B", 2, List.of(CraftInput.of("A", 3)))
                .pattern("D", 1, List.of(CraftInput.of("B", 2)),
                        List.of(CraftOutput.of("A", 2)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addDurability(List<ReferenceScenario> out, int uses, int amount) {
        long tools = (amount + uses - 1L) / uses;
        Map<String, Long> minimum = Map.of("tool", tools, "raw", (long) amount);
        Map<String, Long> starved = Map.of("tool", tools - 1L, "raw", (long) amount);
        addThreeModes(out, "durability/finite-use-chain", ReferenceCapability.DURABILITY_CHAIN,
                uses, "product", amount, minimum, starved, List.of(Map.of("tool", 1L)),
                stock -> durability(uses, stock));
    }

    private static CraftGraph<String> durability(int uses, Map<String, Long> stock) {
        var builder = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(
                        CraftInput.of("raw", 1), CraftInput.finiteUse("tool", 1, uses)));
        stock.forEach(builder::stock);
        return builder.build();
    }

    private static void addFuzzyVariant(List<ReferenceScenario> out, int amount) {
        var source = new ReusableStockSource("host", "fuzzy-reference");
        addScenario(out, "fuzzy/variant-route", ReferenceCapability.FUZZY_VARIANT,
                ReferenceMaterialMode.MISSING, amount, fuzzyVariant(source, Map.of()),
                "product", amount, false, List.of(Map.of("logical_tool", 1L)));
        addScenario(out, "fuzzy/variant-route", ReferenceCapability.FUZZY_VARIANT,
                ReferenceMaterialMode.MINIMUM, amount,
                fuzzyVariant(source, Map.of("damaged_tool", 1L)),
                "product", amount, true, List.of());
        addScenario(out, "fuzzy/variant-route", ReferenceCapability.FUZZY_VARIANT,
                ReferenceMaterialMode.UNBOUNDED, amount,
                fuzzyVariant(source, Map.of("damaged_tool", UNBOUNDED_STOCK)),
                "product", amount, true, List.of());
    }

    private static CraftGraph<String> fuzzyVariant(
            ReusableStockSource source, Map<String, Long> reusableStock) {
        var builder = CraftGraph.<String>builder()
                .pattern("product", 1, List.of(CraftInput.returnedFrom("logical_tool", 1, source)))
                .reusableStockRoute(source, "logical_tool", List.of("logical_tool", "damaged_tool"));
        reusableStock.forEach((key, amount) -> builder.reusableStock("host", key, amount));
        return builder.build();
    }

    private static void addThreeModes(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            int scale,
            String target,
            long amount,
            Map<String, Long> minimumStock,
            Map<String, Long> starvedStock,
            List<Map<String, Long>> minimalMissing,
            GraphFactory factory) {
        addScenario(out, id, capability, ReferenceMaterialMode.MISSING, scale,
                factory.build(starvedStock), target, amount, false, minimalMissing);
        addScenario(out, id, capability, ReferenceMaterialMode.MINIMUM, scale,
                factory.build(minimumStock), target, amount, true, List.of());
        var unbounded = new HashMap<String, Long>();
        minimumStock.keySet().forEach(key -> unbounded.put(key, UNBOUNDED_STOCK));
        addScenario(out, id, capability, ReferenceMaterialMode.UNBOUNDED, scale,
                factory.build(unbounded), target, amount, true, List.of());
    }

    private static void addScenario(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            ReferenceMaterialMode mode,
            int scale,
            CraftGraph<String> graph,
            String target,
            long amount,
            boolean feasible,
            List<Map<String, Long>> missing) {
        addScenario(out, id, capability, mode, scale, graph, target, amount,
                feasible, missing, ignored -> true);
    }

    private static void addScenario(
            List<ReferenceScenario> out,
            String id,
            ReferenceCapability capability,
            ReferenceMaterialMode mode,
            int scale,
            CraftGraph<String> graph,
            String target,
            long amount,
            boolean feasible,
            List<Map<String, Long>> missing,
            Predicate<CraftPlan<String>> additionalValidator) {
        out.add(new ReferenceScenario(
                id + "/" + mode.name().toLowerCase(), capability, mode, scale,
                graph, target, amount, feasible, missing, Map.of(), additionalValidator));
    }

    private static Map<String, Long> addDemand(Map<String, Long> left, Map<String, Long> right) {
        var result = new LinkedHashMap<String, Long>();
        left.forEach((key, value) -> result.merge(key, value, Math::addExact));
        right.forEach((key, value) -> result.merge(key, value, Math::addExact));
        return Map.copyOf(result);
    }

    private static long total(Map<String, Long> values) {
        return values.values().stream().mapToLong(Long::longValue).sum();
    }

    @FunctionalInterface
    private interface GraphFactory {
        CraftGraph<String> build(Map<String, Long> stock);
    }
}

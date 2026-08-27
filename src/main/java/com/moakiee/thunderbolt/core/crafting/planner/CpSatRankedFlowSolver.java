package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

/**
 * Compact CP-SAT execution certificate for ordinary conversion graphs.
 *
 * <p>Each pattern has one long firing variable and one activation Boolean in the bridge. Active
 * dependencies must point from a lower item rank to a higher output rank, so the selected support
 * is a DAG after every proven ratio-conservative strict conversion SCC is contracted. Such an SCC
 * shares one rank and gets an explicit, amount-independent family of grouped startup-prefix
 * certificates. Aggregate balances plus the selected prefix therefore have a concrete replay; no
 * request-sized horizon, per-firing Boolean, no-good enumeration or speculative cycle relaxation is
 * involved.</p>
 *
 * <p>Unchanged infinite-use catalysts are admitted as presence conditions. Finite-use tools use an
 * exact {@code ceil(firings / lifetime)} integer variable, and independent host-private fuzzy seed
 * routes are represented without exposing their inventory to ordinary demands. Proven non-growing
 * byproduct feedback SCCs are contracted into Petri-net macros: CP-SAT chooses the aggregate firing
 * vector, then selects one executable startup-prefix certificate for that fixed vector. Container
 * remainders are canonical ordinary byproduct post-arcs; durability degradation is normalized by the
 * production adapter into a finite carrier chain. Host-private reusable inputs use an exact sparse
 * route-to-physical-variant allocation matrix, so fuzzy routes may overlap without double-spending
 * one physical stack.</p>
 */
public final class CpSatRankedFlowSolver<K> {
    private static final int MAX_ITEMS = Math.max(
            8, Integer.getInteger("thunderbolt.maxCpSatRankItems", 256));
    private static final int MAX_PATTERNS = Math.max(
            4, Integer.getInteger("thunderbolt.maxCpSatRankPatterns", 192));

    public enum Status {
        SOLVED,
        INFEASIBLE,
        UNSUPPORTED,
        UNKNOWN,
        INVALID
    }

    public record Result<K>(Status status, CraftPlan<K> plan, long branches) {
        static <K> Result<K> status(Status status) {
            return new Result<>(status, null, 0L);
        }
    }

    private final CraftGraph<K> graph;
    private final K target;
    private final long targetAmount;

    private CpSatRankedFlowSolver(CraftGraph<K> graph, K target, long targetAmount) {
        this.graph = graph;
        this.target = target;
        this.targetAmount = targetAmount;
    }

    public static <K> Result<K> solve(CraftGraph<K> graph, K target, long targetAmount) {
        if (!CpSatRuntime.isAvailable()) return Result.status(Status.UNSUPPORTED);
        if (targetAmount <= 0L || Sat.isSaturated(targetAmount)) {
            return Result.status(Status.UNSUPPORTED);
        }
        return new CpSatRankedFlowSolver<>(graph, target, targetAmount).solve();
    }

    private Result<K> solve() {
        Compilation<K> compilation = compile();
        if (compilation == null) return Result.status(Status.UNSUPPORTED);
        long remainingNanos = PlanningCancellation.remainingNanos(Long.MAX_VALUE);
        if (remainingNanos <= 0L) return Result.status(Status.UNKNOWN);

        long[] raw;
        try {
            raw = CpSatRuntime.solveRankedPlan(
                    compilation.consumed,
                    compilation.produced,
                    compilation.catalysts,
                    compilation.finiteUseAmounts,
                    compilation.finiteUseLifetimes,
                    compilation.outputItems,
                    compilation.primaryOutputItems,
                    compilation.primaryOutputAmounts,
                    compilation.rankGroups,
                    cycleRecipes(compilation.conversionCycles),
                    cycleInputItems(compilation.conversionCycles),
                    cycleInputAmounts(compilation.conversionCycles),
                    cyclePrimitiveFirings(compilation.conversionCycles),
                    compilation.stocks,
                    compilation.reusableCatalysts,
                    reusableItems(compilation.reusableGroups),
                    compilation.reusableCandidatePhysicals,
                    compilation.reusablePhysicalStocks,
                    compilation.itemDistances,
                    compilation.targetItem,
                    targetAmount,
                    compilation.firingUpperBounds,
                    remainingNanos / 1_000_000_000.0D);
        } catch (RuntimeException | LinkageError failure) {
            return Result.status(Status.INVALID);
        }
        if (raw.length < 2) return Result.status(Status.INVALID);
        Status status = switch ((int) raw[0]) {
            case 0 -> Status.SOLVED;
            case 1 -> Status.INFEASIBLE;
            case 2 -> Status.INVALID;
            default -> Status.UNKNOWN;
        };
        long branches = Math.max(0L, raw[1]);
        if (status != Status.SOLVED) return new Result<>(status, null, branches);

        int recipeCount = compilation.patterns.size();
        int itemCount = compilation.items.size();
        int cycleCount = compilation.conversionCycles.size();
        int reusableCount = compilation.reusableGroups.size();
        if (raw.length != 2 + recipeCount + itemCount + cycleCount + itemCount
                + reusableCount) {
            return Result.status(Status.INVALID);
        }
        long[] firings = new long[recipeCount];
        long[] ranks = new long[itemCount];
        int[] cycleStarts = new int[cycleCount];
        long[] missing = new long[itemCount];
        long[] reusableMissing = new long[reusableCount];
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            long quantity = raw[2 + recipe];
            if (quantity < 0L || quantity > compilation.firingUpperBounds[recipe]) {
                return Result.status(Status.INVALID);
            }
            firings[recipe] = quantity;
        }
        System.arraycopy(raw, 2 + recipeCount, ranks, 0, itemCount);
        int cycleOffset = 2 + recipeCount + itemCount;
        for (int cycle = 0; cycle < cycleCount; cycle++) {
            long start = raw[cycleOffset + cycle];
            if (start < 0L || start >= compilation.conversionCycles.get(cycle).recipes.length) {
                return Result.status(Status.INVALID);
            }
            cycleStarts[cycle] = (int) start;
        }
        int missingOffset = cycleOffset + cycleCount;
        for (int item = 0; item < itemCount; item++) {
            long quantity = raw[missingOffset + item];
            if (quantity < 0L || Sat.isSaturated(quantity)) {
                return Result.status(Status.INVALID);
            }
            missing[item] = quantity;
        }
        int reusableMissingOffset = missingOffset + itemCount;
        for (int group = 0; group < reusableCount; group++) {
            long quantity = raw[reusableMissingOffset + group];
            if (quantity < 0L || Sat.isSaturated(quantity)) {
                return Result.status(Status.INVALID);
            }
            reusableMissing[group] = quantity;
        }
        CraftPlan<K> plan = replay(
                compilation, firings, ranks, cycleStarts, missing, reusableMissing);
        return plan == null
                ? Result.status(Status.INVALID)
                : new Result<>(Status.SOLVED, plan, branches);
    }

    private Compilation<K> compile() {
        LinkedHashSet<K> reachableOutputs = new LinkedHashSet<>();
        LinkedHashSet<K> allItems = new LinkedHashSet<>();
        List<CraftPattern<K>> patterns = new ArrayList<>();
        Set<CraftPattern<K>> seenPatterns = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<K, Integer> distances = new LinkedHashMap<>();
        Deque<K> queue = new ArrayDeque<>();
        reachableOutputs.add(target);
        allItems.add(target);
        distances.put(target, 0);
        queue.add(target);
        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            K output = queue.removeFirst();
            int inputDistance = distances.get(output) + 1;
            for (CraftPattern<K> pattern : graph.patternsFor(output)) {
                if (seenPatterns.add(pattern)) patterns.add(pattern);
                allItems.add(pattern.output());
                for (CraftOutput<K> byproduct : pattern.byproducts()) {
                    allItems.add(byproduct.key());
                }
                for (CraftInput<K> input : pattern.inputs()) {
                    allItems.add(input.key());
                    Integer previousDistance = distances.get(input.key());
                    if (previousDistance == null || inputDistance < previousDistance) {
                        distances.put(input.key(), inputDistance);
                    }
                    if (reachableOutputs.add(input.key())) queue.addLast(input.key());
                }
            }
            if (patterns.size() > MAX_PATTERNS || allItems.size() > MAX_ITEMS) return null;
        }
        if (patterns.isEmpty()) return null;

        List<K> items = List.copyOf(allItems);
        Map<K, Integer> itemIndex = new LinkedHashMap<>();
        for (int item = 0; item < items.size(); item++) itemIndex.put(items.get(item), item);
        int recipeCount = patterns.size();
        int itemCount = items.size();
        Map<ReusableStockRouteKey<K>, ReusableGroup<K>> reusableByRoute =
                new LinkedHashMap<>();
        for (CraftPattern<K> pattern : patterns) {
            for (CraftInput<K> input : pattern.inputs()) {
                if (input.reusableStockSource() == null) continue;
                ReusableStockRouteKey<K> route = new ReusableStockRouteKey<>(
                        input.reusableStockSource(), input.key());
                reusableByRoute.computeIfAbsent(
                        route,
                        ignored -> reusableGroup(
                                graph, input.reusableStockSource(), input.key(),
                                itemIndex.get(input.key())));
            }
        }
        List<ReusableGroup<K>> reusableGroups = List.copyOf(reusableByRoute.values());
        Map<ReusableStockRouteKey<K>, Integer> reusableGroupIndex = new LinkedHashMap<>();
        for (int group = 0; group < reusableGroups.size(); group++) {
            reusableGroupIndex.put(reusableGroups.get(group).route, group);
        }
        long[][] consumed = new long[recipeCount][itemCount];
        long[][] produced = new long[recipeCount][itemCount];
        long[][] catalysts = new long[recipeCount][itemCount];
        long[][] reusableCatalysts = new long[recipeCount][reusableGroups.size()];
        long[][] finiteUseAmounts = new long[recipeCount][itemCount];
        long[][] finiteUseLifetimes = new long[recipeCount][itemCount];
        int[] outputItems = new int[recipeCount];
        int[] primaryOutputItems = new int[recipeCount];
        long[] primaryOutputAmounts = new long[recipeCount];
        long[] upperBounds = new long[recipeCount];
        boolean[] consumedSomewhere = new boolean[itemCount];
        boolean[] catalystSomewhere = new boolean[itemCount];
        boolean[] ordinaryCatalystSomewhere = new boolean[itemCount];
        boolean[] reusableSomewhere = new boolean[itemCount];

        for (int recipe = 0; recipe < recipeCount; recipe++) {
            CraftPattern<K> pattern = patterns.get(recipe);
            int outputItem = itemIndex.get(pattern.output());
            outputItems[recipe] = outputItem;
            primaryOutputItems[recipe] = outputItem;
            primaryOutputAmounts[recipe] = pattern.outputAmount();
            if (!add(produced[recipe], outputItem, pattern.outputAmount())) return null;
            for (CraftOutput<K> byproduct : pattern.byproducts()) {
                Integer item = itemIndex.get(byproduct.key());
                if (item == null || !add(produced[recipe], item, byproduct.amount())) return null;
            }
            for (CraftInput<K> input : pattern.inputs()) {
                int item = itemIndex.get(input.key());
                if (input.returned()) {
                    if (input.uses() == CraftInput.INFINITE_USES) {
                        catalystSomewhere[item] = true;
                        if (input.reusableStockSource() == null) {
                            if (!add(catalysts[recipe], item, input.amount())) return null;
                            ordinaryCatalystSomewhere[item] = true;
                        } else {
                            reusableSomewhere[item] = true;
                            Integer group = reusableGroupIndex.get(new ReusableStockRouteKey<>(
                                    input.reusableStockSource(), input.key()));
                            if (group == null
                                    || !add(reusableCatalysts[recipe], group, input.amount())) {
                                return null;
                            }
                        }
                    } else {
                        if (finiteUseAmounts[recipe][item] != 0L) return null;
                        finiteUseAmounts[recipe][item] = input.amount();
                        finiteUseLifetimes[recipe][item] = input.uses();
                        consumedSomewhere[item] = true;
                    }
                } else {
                    if (!add(consumed[recipe], item, input.amount())) return null;
                    consumedSomewhere[item] = true;
                }
            }
        }

        int targetItem = itemIndex.get(target);
        for (int item = 0; item < itemCount; item++) {
            if (reusableSomewhere[item]
                    && (ordinaryCatalystSomewhere[item]
                            || consumedSomewhere[item]
                            || item == targetItem
                            || graph.stock(items.get(item)) > 0L)) {
                return null;
            }
        }

        for (ReusableGroup<K> group : reusableGroups) {
            int item = group.item;
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                if (produced[recipe][item] > 0L) return null;
            }
        }

        Map<ReusableStockKey<K>, Integer> reusablePhysicalIndex = new LinkedHashMap<>();
        long candidateEdges = 0L;
        for (ReusableGroup<K> group : reusableGroups) {
            candidateEdges = Sat.add(candidateEdges, group.actualKeys.size());
            if (Sat.isSaturated(candidateEdges)
                    || candidateEdges > ReusableStockMatcher.MAX_MATCH_PAIRS) {
                return null;
            }
            for (K actual : group.actualKeys) {
                reusablePhysicalIndex.computeIfAbsent(
                        new ReusableStockKey<>(group.source.storageScope(), actual),
                        ignored -> reusablePhysicalIndex.size());
            }
        }
        long potentialMatchPairs = (long) reusableGroups.size()
                * (long) reusablePhysicalIndex.size();
        if (potentialMatchPairs > ReusableStockMatcher.MAX_MATCH_PAIRS) return null;
        long[] reusablePhysicalStocks = new long[reusablePhysicalIndex.size()];
        for (Map.Entry<ReusableStockKey<K>, Integer> entry : reusablePhysicalIndex.entrySet()) {
            reusablePhysicalStocks[entry.getValue()] = graph.reusableStock(
                    entry.getKey().scope(), entry.getKey().key());
        }
        int[][] reusableCandidatePhysicals = new int[reusableGroups.size()][];
        for (int group = 0; group < reusableGroups.size(); group++) {
            List<K> candidates = reusableGroups.get(group).actualKeys;
            int[] physicals = new int[candidates.size()];
            for (int candidate = 0; candidate < candidates.size(); candidate++) {
                Integer physical = reusablePhysicalIndex.get(new ReusableStockKey<>(
                        reusableGroups.get(group).source.storageScope(),
                        candidates.get(candidate)));
                if (physical == null) return null;
                physicals[candidate] = physical;
            }
            reusableCandidatePhysicals[group] = physicals;
        }

        long domainCap = Math.max(1L, Sat.SAT / Math.max(1, recipeCount));
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            long maxCoefficient = 1L;
            for (int item = 0; item < itemCount; item++) {
                maxCoefficient = Math.max(maxCoefficient, consumed[recipe][item]);
                maxCoefficient = Math.max(maxCoefficient, produced[recipe][item]);
            }
            // Any one item row can contain every recipe variable. Give each variable at most an
            // equal share of the safe signed-long coefficient budget; bounding by coefficient or
            // recipe count separately is insufficient when a newly added valid inequality combines
            // both. This still leaves enormous long-scale domains while satisfying CP-SAT's exact
            // overflow validator.
            long coefficientShare = Sat.SAT / maxCoefficient / Math.max(1, recipeCount);
            upperBounds[recipe] = Math.max(1L, Math.min(domainCap, coefficientShare));
        }

        long[] stocks = new long[itemCount];
        int[] itemDistances = new int[itemCount];
        for (int item = 0; item < itemCount; item++) {
            stocks[item] = graph.stock(items.get(item));
        }
        for (int item = 0; item < itemCount; item++) {
            itemDistances[item] = distances.getOrDefault(items.get(item), Integer.MAX_VALUE);
        }

        Map<K, List<CraftPattern<K>>> patternsByOutput = new LinkedHashMap<>();
        for (CraftPattern<K> pattern : patterns) {
            patternsByOutput.computeIfAbsent(pattern.output(), ignored -> new ArrayList<>())
                    .add(pattern);
        }
        Map<CraftPattern<K>, Integer> recipeIndex = new IdentityHashMap<>();
        for (int recipe = 0; recipe < recipeCount; recipe++) {
            recipeIndex.put(patterns.get(recipe), recipe);
        }
        CycleAnalysis<K> cycleAnalysis = CycleAnalysis.analyze(graph, target);
        ConservativeFeedbackAnalysis.Analysis<K> feedback =
                ConservativeFeedbackAnalysis.analyzeAll(items, patternsByOutput);
        List<ConversionCycle<K>> conversionCycles = new ArrayList<>();
        List<FeedbackMacro<K>> feedbackMacros = new ArrayList<>();
        Set<K> coveredCycleStates = new LinkedHashSet<>();
        boolean[] cycleRecipe = new boolean[recipeCount];
        for (ConservativeFeedbackAnalysis.Component<K> component : feedback.components()) {
            K representative = component.stateOrder().get(0);
            CycleAnalysis.Kind kind = cycleAnalysis.kindOf(representative);
            PrimitiveCycle primitive = null;
            if ((kind == CycleAnalysis.Kind.PURE_CONVERSION
                            || kind == CycleAnalysis.Kind.CATALYZED_CONVERSION)
                    && cycleAnalysis.membersOf(representative).equals(component.states())) {
                primitive = primitiveCycle(component, recipeIndex, itemIndex);
            }
            if (primitive != null) {
                if (overlaps(component.states(), primitive.recipes,
                        coveredCycleStates, cycleRecipe)) {
                    return null;
                }
                coveredCycleStates.addAll(component.states());
                for (int recipe : primitive.recipes) cycleRecipe[recipe] = true;
                FeedbackMacro<K> replayMacro = feedbackMacro(
                        component, recipeIndex, itemIndex);
                if (replayMacro == null) return null;
                conversionCycles.add(new ConversionCycle<>(
                        component.states(),
                        primitive.recipes,
                        primitive.inputItems,
                        primitive.inputAmounts,
                        primitive.firings,
                        replayMacro,
                        -1));
                continue;
            }

            FeedbackMacro<K> macro = feedbackMacro(component, recipeIndex, itemIndex);
            if (macro == null
                    || overlaps(component.states(), macro.recipes,
                            coveredCycleStates, cycleRecipe)) {
                return null;
            }
            coveredCycleStates.addAll(component.states());
            for (int recipe : macro.recipes) cycleRecipe[recipe] = true;
            feedbackMacros.add(macro);
        }
        for (ConservativeFeedbackAnalysis.FallbackComponent<K> component
                : feedback.fallbacks()) {
            FeedbackMacro<K> macro = feedbackMacro(component, recipeIndex, itemIndex);
            if (macro == null
                    || overlaps(component.states(), macro.recipes,
                            coveredCycleStates, cycleRecipe)) {
                return null;
            }
            coveredCycleStates.addAll(component.states());
            for (int recipe : macro.recipes) cycleRecipe[recipe] = true;
            feedbackMacros.add(macro);
        }
        int[] rankGroups = new int[itemCount];
        Arrays.fill(rankGroups, -1);
        List<ConversionCycle<K>> rankedCycles = new ArrayList<>(conversionCycles.size());
        int nextGroup = 0;
        for (ConversionCycle<K> cycle : conversionCycles) {
            int group = nextGroup++;
            for (K state : cycle.states) {
                int item = itemIndex.get(state);
                if (rankGroups[item] >= 0) return null;
                rankGroups[item] = group;
            }
            for (int offset = 0; offset < cycle.replayMacro.recipes.length; offset++) {
                outputItems[cycle.replayMacro.recipes[offset]] =
                        cycle.replayMacro.outputItems[offset];
            }
            rankedCycles.add(cycle.withRankGroup(group));
        }
        List<FeedbackMacro<K>> rankedFeedback = new ArrayList<>(feedbackMacros.size());
        for (FeedbackMacro<K> macro : feedbackMacros) {
            int group = nextGroup++;
            for (K state : macro.states) {
                int item = itemIndex.get(state);
                if (rankGroups[item] >= 0) return null;
                rankGroups[item] = group;
            }
            for (int offset = 0; offset < macro.recipes.length; offset++) {
                outputItems[macro.recipes[offset]] = macro.outputItems[offset];
            }
            rankedFeedback.add(macro.withRankGroup(group));
        }
        for (int item = 0; item < itemCount; item++) {
            if (rankGroups[item] < 0) rankGroups[item] = nextGroup++;
        }
        int[] conversionCycleByRecipe = new int[recipeCount];
        Arrays.fill(conversionCycleByRecipe, -1);
        for (int cycle = 0; cycle < rankedCycles.size(); cycle++) {
            for (int recipe : rankedCycles.get(cycle).recipes) {
                if (conversionCycleByRecipe[recipe] >= 0) return null;
                conversionCycleByRecipe[recipe] = cycle;
            }
        }
        int[] feedbackMacroByRecipe = new int[recipeCount];
        Arrays.fill(feedbackMacroByRecipe, -1);
        for (int macro = 0; macro < rankedFeedback.size(); macro++) {
            for (int recipe : rankedFeedback.get(macro).recipes) {
                if (feedbackMacroByRecipe[recipe] >= 0
                        || conversionCycleByRecipe[recipe] >= 0) {
                    return null;
                }
                feedbackMacroByRecipe[recipe] = macro;
            }
        }
        // A conserved item that is also consumed needs a physical order. It is safe only when every
        // catalytic read belongs to one proven feedback macro containing that state: the macro runs
        // at the state's rank, while any ordinary consumer is forced to a later output rank.
        for (int item = 0; item < itemCount; item++) {
            if (!catalystSomewhere[item] || (!consumedSomewhere[item] && item != targetItem)) {
                continue;
            }
            int owner = -1;
            for (int recipe = 0; recipe < recipeCount; recipe++) {
                if (catalysts[recipe][item] <= 0L) continue;
                int macro = feedbackMacroByRecipe[recipe];
                if (macro < 0 || !rankedFeedback.get(macro).states.contains(items.get(item))
                        || (owner >= 0 && owner != macro)) {
                    return null;
                }
                owner = macro;
            }
            if (owner < 0) return null;
        }
        return new Compilation<>(
                items,
                List.copyOf(patterns),
                consumed,
                produced,
                catalysts,
                finiteUseAmounts,
                finiteUseLifetimes,
                reusableCatalysts,
                outputItems,
                primaryOutputItems,
                primaryOutputAmounts,
                rankGroups,
                List.copyOf(rankedCycles),
                conversionCycleByRecipe,
                List.copyOf(rankedFeedback),
                feedbackMacroByRecipe,
                stocks,
                reusableGroups,
                reusableCandidatePhysicals,
                reusablePhysicalStocks,
                itemDistances,
                upperBounds,
                targetItem);
    }

    private CraftPlan<K> replay(
            Compilation<K> c,
            long[] quantities,
            long[] ranks,
            int[] cycleStarts,
            long[] missingSupply,
            long[] reusableMissing) {
        if (ranks.length != c.items.size()
                || cycleStarts.length != c.conversionCycles.size()
                || missingSupply.length != c.items.size()
                || reusableMissing.length != c.reusableGroups.size()) {
            return null;
        }
        Map<Integer, Long> rankByGroup = new LinkedHashMap<>();
        for (int recipe = 0; recipe < c.patterns.size(); recipe++) {
            if (quantities[recipe] == 0L) continue;
            int output = c.outputItems[recipe];
            if (ranks[output] < 0L || ranks[output] >= c.items.size()) return null;
            for (int item = 0; item < c.items.size(); item++) {
                if ((c.consumed[recipe][item] > 0L
                                || c.catalysts[recipe][item] > 0L
                                || hasReusableCatalyst(c, recipe, item)
                                || c.finiteUseAmounts[recipe][item] > 0L)
                        && c.rankGroups[item] != c.rankGroups[output]) {
                    if (ranks[item] >= ranks[output]) return null;
                } else if ((c.consumed[recipe][item] > 0L
                                || c.catalysts[recipe][item] > 0L
                                || hasReusableCatalyst(c, recipe, item)
                                || c.finiteUseAmounts[recipe][item] > 0L)
                        && c.conversionCycleByRecipe[recipe] < 0
                        && c.feedbackMacroByRecipe[recipe] < 0) {
                    return null;
                }
                if (c.produced[recipe][item] > 0L
                        && c.rankGroups[item] != c.rankGroups[output]
                        && ranks[output] >= ranks[item]) {
                    return null;
                }
            }
        }
        for (int item = 0; item < c.items.size(); item++) {
            if (ranks[item] < 0L || ranks[item] >= c.items.size()) return null;
            Long previous = rankByGroup.putIfAbsent(c.rankGroups[item], ranks[item]);
            if (previous != null && previous.longValue() != ranks[item]) return null;
        }
        List<Integer> groups = new ArrayList<>(rankByGroup.keySet());
        groups.sort(Comparator
                .comparingLong((Integer group) -> rankByGroup.get(group))
                .thenComparingInt(Integer::intValue));

        long[] reusableNeeds = reusableNeeds(c, quantities);
        Map<ReusableStockRouteKey<K>, Long> reusableDemand = new LinkedHashMap<>();
        long[] reusableMissingByItem = new long[c.items.size()];
        boolean[] reusableItems = new boolean[c.items.size()];
        for (int group = 0; group < c.reusableGroups.size(); group++) {
            long need = reusableNeeds[group];
            long shortage = reusableMissing[group];
            reusableItems[c.reusableGroups.get(group).item] = true;
            if (shortage > need
                    || !add(reusableMissingByItem, c.reusableGroups.get(group).item, shortage)) {
                return null;
            }
            long assigned = need - shortage;
            if (assigned > 0L) {
                reusableDemand.put(c.reusableGroups.get(group).route, assigned);
            }
        }
        for (int item = 0; item < c.items.size(); item++) {
            if (reusableItems[item] && reusableMissingByItem[item] != missingSupply[item]) {
                return null;
            }
        }
        Map<ReusableStockKey<K>, Long> reusableAvailable = new LinkedHashMap<>();
        for (ReusableGroup<K> group : c.reusableGroups) {
            for (K actual : group.actualKeys) {
                ReusableStockKey<K> physical = new ReusableStockKey<>(
                        group.source.storageScope(), actual);
                reusableAvailable.putIfAbsent(
                        physical, graph.reusableStock(physical.scope(), physical.key()));
            }
        }
        ReusableStockMatcher.Result<K> reusableAllocation = ReusableStockMatcher.allocate(
                reusableAvailable,
                reusableDemand,
                route -> graph.reusableStockCandidates(route.source(), route.plannedKey()));
        if (!reusableAllocation.feasible()) return null;

        int itemCount = c.items.size();
        long[] original = c.stocks.clone();
        long[] virtual = missingSupply.clone();
        for (ReusableGroup<K> group : c.reusableGroups) virtual[group.item] = 0L;
        long[] generated = new long[itemCount];
        long[] used = new long[itemCount];
        long[] reservedCatalystStock = new long[itemCount];
        long[] reservedCatalystMissing = new long[itemCount];
        long[] replayMissing = new long[itemCount];
        long[] gross = new long[itemCount];
        gross[c.targetItem] = targetAmount;
        for (int group = 0; group < c.reusableGroups.size(); group++) {
            if (!add(gross, c.reusableGroups.get(group).item, reusableNeeds[group])) return null;
        }
        Map<CraftPattern<K>, Long> firingMap = new IdentityHashMap<>();
        boolean[] executed = new boolean[c.patterns.size()];

        for (int group : groups) {
            for (int recipe = 0; recipe < c.patterns.size(); recipe++) {
                if (quantities[recipe] == 0L
                        || c.conversionCycleByRecipe[recipe] >= 0
                        || c.feedbackMacroByRecipe[recipe] >= 0
                        || c.rankGroups[c.outputItems[recipe]] != group) {
                    continue;
                }
                if (!executePattern(
                        c, recipe, quantities[recipe], original, virtual, generated, used,
                        reservedCatalystStock, reservedCatalystMissing, gross, firingMap)) {
                    return null;
                }
                executed[recipe] = true;
            }

            for (int cycle = 0; cycle < c.conversionCycles.size(); cycle++) {
                ConversionCycle<K> conversion = c.conversionCycles.get(cycle);
                if (conversion.rankGroup != group) continue;
                if (hasPositive(conversion.recipes, quantities)
                        && !executeFeedbackMacro(
                                c, conversion.replayMacro, quantities,
                                original, virtual, generated, used,
                                reservedCatalystStock, reservedCatalystMissing, replayMissing,
                                gross, firingMap, executed)) {
                    return null;
                }
            }

            for (FeedbackMacro<K> macro : c.feedbackMacros) {
                if (macro.rankGroup != group || !hasPositive(macro.recipes, quantities)) continue;
                if (!executeFeedbackMacro(
                        c, macro, quantities, original, virtual, generated, used,
                        reservedCatalystStock, reservedCatalystMissing, replayMissing,
                        gross, firingMap, executed)) {
                    return null;
                }
            }
        }
        for (int recipe = 0; recipe < c.patterns.size(); recipe++) {
            if (quantities[recipe] > 0L && !executed[recipe]) return null;
        }
        if (!draw(original, virtual, generated, used, c.targetItem, targetAmount)) return null;

        Map<K, Long> usedStock = new LinkedHashMap<>();
        Map<ReusableStockUsageKey<K>, Long> usedReusableStock = new LinkedHashMap<>();
        for (Map.Entry<ReusableStockAllocationKey<K>, Long> entry
                : reusableAllocation.allocation().entrySet()) {
            ReusableStockRouteKey<K> route = entry.getKey().route();
            ReusableStockSource source = route.source();
            ReusableStockUsageKey<K> usage = new ReusableStockUsageKey<>(
                    source.storageScope(), source.poolScope(), source.routingScope(),
                    route.plannedKey(), entry.getKey().actualKey());
            long next = Sat.add(usedReusableStock.getOrDefault(usage, 0L), entry.getValue());
            if (Sat.isSaturated(next)) return null;
            usedReusableStock.put(usage, next);
        }
        Map<K, Long> missing = new LinkedHashMap<>();
        Map<K, Long> grossDemand = new LinkedHashMap<>();
        for (int item = 0; item < itemCount; item++) {
            used[item] = Math.max(used[item], reservedCatalystStock[item]);
            if (used[item] > 0L) {
                usedStock.put(c.items.get(item), used[item]);
            }
            long baseMissing = reusableItems[item]
                    ? missingSupply[item]
                    : Math.max(
                            missingSupply[item] - virtual[item], reservedCatalystMissing[item]);
            long reportedMissing = Sat.add(baseMissing, replayMissing[item]);
            if (Sat.isSaturated(reportedMissing)) return null;
            if (reportedMissing > 0L) missing.put(c.items.get(item), reportedMissing);
            if (gross[item] > 0L) grossDemand.put(c.items.get(item), gross[item]);
        }
        return new CraftPlan<>(
                true,
                missing.isEmpty(),
                Map.copyOf(firingMap),
                Map.copyOf(usedStock),
                Map.copyOf(usedReusableStock),
                Map.copyOf(missing),
                Map.copyOf(grossDemand),
                c.items.size(),
                false);
    }

    private boolean executeFeedbackMacro(
            Compilation<K> c,
            FeedbackMacro<K> macro,
            long[] quantities,
            long[] original,
            long[] virtual,
            long[] generated,
            long[] used,
            long[] reservedCatalystStock,
            long[] reservedCatalystMissing,
            long[] replayMissing,
            long[] gross,
            Map<CraftPattern<K>, Long> firingMap,
            boolean[] executed) {
        Map<CraftPattern<K>, Long> fired = new IdentityHashMap<>();
        for (int recipe : macro.recipes) {
            if (quantities[recipe] > 0L) {
                fired.put(c.patterns.get(recipe), quantities[recipe]);
            }
        }

        // A static SCC may become a DAG after CP-SAT chooses only one direction of several
        // overlapping rings. Such a firing support needs no startup macro and must not inherit the
        // conservative marking of the unused reverse edges.
        List<Integer> acyclicOrder = activeFeedbackOrder(c, macro, quantities);
        if (acyclicOrder != null) {
            for (int recipe : acyclicOrder) {
                if (!executePattern(
                        c, recipe, quantities[recipe], original, virtual, generated, used,
                        reservedCatalystStock, reservedCatalystMissing, gross, firingMap)) {
                    return false;
                }
                executed[recipe] = true;
            }
            return true;
        }

        List<ConservativeFeedbackAnalysis.ScheduleOption<K>> options = new ArrayList<>();
        Set<Map<K, BigInteger>> seenRequirements = new LinkedHashSet<>();
        if (macro.exact != null) {
            for (ConservativeFeedbackAnalysis.ScheduleOption<K> option
                    : ConservativeFeedbackAnalysis.scheduleOptions(macro.exact, fired)) {
                if (seenRequirements.add(option.required())) options.add(option);
            }
        }
        for (ConservativeFeedbackAnalysis.ScheduleOption<K> option
                : ConservativeFeedbackAnalysis.scheduleOptions(macro.fallback, fired)) {
            if (seenRequirements.add(option.required())) options.add(option);
        }
        if (options.isEmpty()) return false;

        int stateCount = macro.stateOrder.size();
        long[][] requirements = new long[options.size()][stateCount];
        long[] available = new long[stateCount];
        for (int state = 0; state < stateCount; state++) {
            int item = macro.stateItems[state];
            available[state] = available(original[item], virtual[item], generated[item]);
            for (int option = 0; option < options.size(); option++) {
                Long amount = plannerLong(options.get(option).required().getOrDefault(
                        macro.stateOrder.get(state), BigInteger.ZERO));
                if (amount == null) return false;
                requirements[option][state] = amount;
            }
        }
        long[] scoreRanks = feedbackScoreRanks(
                c, macro, requirements, original, virtual, generated);
        long remainingNanos = PlanningCancellation.remainingNanos(Long.MAX_VALUE);
        if (remainingNanos <= 0L) return false;
        long[] selected;
        try {
            selected = CpSatRuntime.chooseFeedbackOption(
                    requirements,
                    available,
                    scoreRanks,
                    remainingNanos / 1_000_000_000.0D);
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
        if (selected.length != 3 || selected[0] != 0L
                || selected[2] < 0L || selected[2] >= options.size()) {
            return false;
        }
        int selectedOption = (int) selected[2];
        ConservativeFeedbackAnalysis.ScheduleOption<K> schedule = options.get(selectedOption);

        // Reserve the concrete initial marking. Aggregate Missing is consumed first; any further
        // startup shortage is recorded separately so a refill run receives the complete seed.
        for (int state = 0; state < stateCount; state++) {
            int item = macro.stateItems[state];
            if (!drawWithTopUp(
                    original, virtual, generated, used, replayMissing,
                    item, requirements[selectedOption][state])) {
                return false;
            }
        }

        for (int recipe : macro.recipes) {
            long quantity = quantities[recipe];
            if (quantity == 0L) continue;
            if (!executeFeedbackPattern(
                    c, macro.states, recipe, quantity, original, virtual, generated, used,
                    reservedCatalystStock, reservedCatalystMissing, replayMissing, gross)) {
                return false;
            }
            firingMap.put(c.patterns.get(recipe), quantity);
            executed[recipe] = true;
        }

        // The prefix proof has already executed all internal arcs. Materialize only its final
        // marking; external inputs and outputs were replayed from the ordinary recipe matrices.
        for (int state = 0; state < stateCount; state++) {
            K key = macro.stateOrder.get(state);
            BigInteger finalAmount = schedule.required().getOrDefault(key, BigInteger.ZERO)
                    .add(schedule.delta().getOrDefault(key, BigInteger.ZERO));
            Long amount = plannerLong(finalAmount);
            if (amount == null || !add(generated, macro.stateItems[state], amount)) return false;
        }
        return true;
    }

    /** Returns a stable active-recipe topological order, or {@code null} for a real active cycle. */
    private static <K> List<Integer> activeFeedbackOrder(
            Compilation<K> c, FeedbackMacro<K> macro, long[] quantities) {
        int stateCount = macro.stateItems.length;
        int[] stateByItem = new int[c.items.size()];
        Arrays.fill(stateByItem, -1);
        for (int state = 0; state < stateCount; state++) {
            stateByItem[macro.stateItems[state]] = state;
        }
        List<Set<Integer>> edges = new ArrayList<>(stateCount);
        for (int state = 0; state < stateCount; state++) edges.add(new LinkedHashSet<>());
        int[] indegree = new int[stateCount];
        List<Integer> activeRecipes = new ArrayList<>();
        for (int recipe : macro.recipes) {
            if (quantities[recipe] <= 0L) continue;
            activeRecipes.add(recipe);
            for (int inputItem = 0; inputItem < c.items.size(); inputItem++) {
                int inputState = stateByItem[inputItem];
                if (inputState < 0
                        || (c.consumed[recipe][inputItem] <= 0L
                                && c.catalysts[recipe][inputItem] <= 0L
                                && c.finiteUseAmounts[recipe][inputItem] <= 0L)) {
                    continue;
                }
                for (int outputItem = 0; outputItem < c.items.size(); outputItem++) {
                    int outputState = stateByItem[outputItem];
                    if (outputState < 0 || c.produced[recipe][outputItem] <= 0L) continue;
                    if (edges.get(inputState).add(outputState)) indegree[outputState]++;
                }
            }
        }
        Deque<Integer> ready = new ArrayDeque<>();
        for (int state = 0; state < stateCount; state++) {
            if (indegree[state] == 0) ready.addLast(state);
        }
        int[] position = new int[stateCount];
        int visited = 0;
        while (!ready.isEmpty()) {
            int state = ready.removeFirst();
            position[state] = visited++;
            for (int output : edges.get(state)) {
                if (--indegree[output] == 0) ready.addLast(output);
            }
        }
        if (visited != stateCount) return null;
        activeRecipes.sort(Comparator
                .comparingInt((Integer recipe) -> firstInternalOutputPosition(
                        c, recipe, stateByItem, position))
                .thenComparingInt(Integer::intValue));
        return activeRecipes;
    }

    private static <K> int firstInternalOutputPosition(
            Compilation<K> c, int recipe, int[] stateByItem, int[] position) {
        int first = Integer.MAX_VALUE;
        for (int item = 0; item < c.items.size(); item++) {
            int state = stateByItem[item];
            if (state >= 0 && c.produced[recipe][item] > 0L) {
                first = Math.min(first, position[state]);
            }
        }
        return first;
    }

    private boolean executeFeedbackPattern(
            Compilation<K> c,
            Set<K> internalStates,
            int recipe,
            long quantity,
            long[] original,
            long[] virtual,
            long[] generated,
            long[] used,
            long[] reservedCatalystStock,
            long[] reservedCatalystMissing,
            long[] replayMissing,
            long[] gross) {
        int itemCount = c.items.size();
        for (int item = 0; item < itemCount; item++) {
            long required = c.catalysts[recipe][item];
            if (required <= 0L) continue;
            gross[item] = Math.max(gross[item], required);
            if (internalStates.contains(c.items.get(item))) continue;
            if (!reserveCatalystWithTopUp(
                    original, virtual, generated, reservedCatalystStock,
                    reservedCatalystMissing, item, required)) {
                return false;
            }
        }
        for (int item = 0; item < itemCount; item++) {
            long demand = Sat.mul(c.consumed[recipe][item], quantity);
            if (c.finiteUseAmounts[recipe][item] > 0L) {
                long batches = Sat.ceilDiv(quantity, c.finiteUseLifetimes[recipe][item]);
                demand = Sat.add(
                        demand,
                        Sat.mul(c.finiteUseAmounts[recipe][item], batches));
            }
            if (Sat.isSaturated(demand) || !add(gross, item, demand)) return false;
            if (!internalStates.contains(c.items.get(item))
                    && !drawWithTopUp(
                            original, virtual, generated, used, replayMissing, item, demand)) {
                return false;
            }
        }
        for (int item = 0; item < itemCount; item++) {
            if (internalStates.contains(c.items.get(item))) continue;
            long output = Sat.mul(c.produced[recipe][item], quantity);
            if (Sat.isSaturated(output) || !add(generated, item, output)) return false;
        }
        return true;
    }

    private static boolean reserveCatalystWithTopUp(
            long[] original,
            long[] virtual,
            long[] generated,
            long[] reservedStock,
            long[] reservedMissing,
            int item,
            long required) {
        long unavailableFromGenerated = Math.max(0L, required - generated[item]);
        long fromMissing = Math.min(unavailableFromGenerated, virtual[item]);
        long afterMissing = unavailableFromGenerated - fromMissing;
        long fromOriginal = Math.min(afterMissing, original[item]);
        long topUp = afterMissing - fromOriginal;
        long missing = Sat.add(fromMissing, topUp);
        if (Sat.isSaturated(missing)) return false;
        reservedMissing[item] = Math.max(reservedMissing[item], missing);
        reservedStock[item] = Math.max(reservedStock[item], fromOriginal);
        return true;
    }

    private static <K> long[] feedbackScoreRanks(
            Compilation<K> c,
            FeedbackMacro<K> macro,
            long[][] requirements,
            long[] original,
            long[] virtual,
            long[] generated) {
        List<Integer> order = new ArrayList<>(requirements.length);
        for (int option = 0; option < requirements.length; option++) order.add(option);
        order.sort((left, right) -> compareFeedbackOptions(
                c, macro, requirements[left], requirements[right],
                original, virtual, generated, left, right));
        long[] ranks = new long[requirements.length];
        for (int rank = 0; rank < order.size(); rank++) ranks[order.get(rank)] = rank;
        return ranks;
    }

    private static <K> int compareFeedbackOptions(
            Compilation<K> c,
            FeedbackMacro<K> macro,
            long[] left,
            long[] right,
            long[] original,
            long[] virtual,
            long[] generated,
            int leftIndex,
            int rightIndex) {
        java.util.TreeMap<Integer, BigInteger> leftMissing = new java.util.TreeMap<>();
        java.util.TreeMap<Integer, BigInteger> rightMissing = new java.util.TreeMap<>();
        BigInteger leftUsed = BigInteger.ZERO;
        BigInteger rightUsed = BigInteger.ZERO;
        for (int state = 0; state < macro.stateItems.length; state++) {
            int item = macro.stateItems[state];
            int distance = c.itemDistances[item];
            long leftAfterVirtual = afterGeneratedAndVirtual(
                    left[state], generated[item], virtual[item]);
            long rightAfterVirtual = afterGeneratedAndVirtual(
                    right[state], generated[item], virtual[item]);
            long leftStockUsed = Math.min(leftAfterVirtual, original[item]);
            long rightStockUsed = Math.min(rightAfterVirtual, original[item]);
            accumulateMissing(
                    leftMissing, distance, leftAfterVirtual - leftStockUsed);
            accumulateMissing(
                    rightMissing, distance, rightAfterVirtual - rightStockUsed);
            leftUsed = leftUsed.add(BigInteger.valueOf(leftStockUsed));
            rightUsed = rightUsed.add(BigInteger.valueOf(rightStockUsed));
        }
        int comparison = compareGraded(leftMissing, rightMissing);
        if (comparison != 0) return comparison;
        comparison = leftUsed.compareTo(rightUsed);
        if (comparison != 0) return comparison;
        comparison = total(left).compareTo(total(right));
        return comparison != 0 ? comparison : Integer.compare(leftIndex, rightIndex);
    }

    private static long afterGeneratedAndVirtual(
            long required, long generated, long virtual) {
        return Math.max(0L, Math.max(0L, required - generated) - virtual);
    }

    private static void accumulateMissing(
            Map<Integer, BigInteger> missing, int distance, long shortage) {
        if (shortage > 0L) missing.merge(
                distance, BigInteger.valueOf(shortage), BigInteger::add);
    }

    private static int compareGraded(
            java.util.NavigableMap<Integer, BigInteger> left,
            java.util.NavigableMap<Integer, BigInteger> right) {
        java.util.TreeSet<Integer> tiers = new java.util.TreeSet<>(left.keySet());
        tiers.addAll(right.keySet());
        for (int tier : tiers) {
            int comparison = left.getOrDefault(tier, BigInteger.ZERO)
                    .compareTo(right.getOrDefault(tier, BigInteger.ZERO));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static BigInteger total(long[] values) {
        BigInteger result = BigInteger.ZERO;
        for (long value : values) result = result.add(BigInteger.valueOf(value));
        return result;
    }

    private boolean executePattern(
            Compilation<K> c,
            int recipe,
            long quantity,
            long[] original,
            long[] virtual,
            long[] generated,
            long[] used,
            long[] reservedCatalystStock,
            long[] reservedCatalystMissing,
            long[] gross,
            Map<CraftPattern<K>, Long> firingMap) {
        int itemCount = c.items.size();
        for (int item = 0; item < itemCount; item++) {
            long required = c.catalysts[recipe][item];
            if (required > 0L
                    && available(original[item], virtual[item], generated[item]) < required) {
                return false;
            }
            if (required > 0L) {
                long unavailableFromGenerated = Math.max(0L, required - generated[item]);
                long fromMissing = Math.min(unavailableFromGenerated, virtual[item]);
                long fromOriginal = unavailableFromGenerated - fromMissing;
                reservedCatalystMissing[item] = Math.max(
                        reservedCatalystMissing[item], fromMissing);
                reservedCatalystStock[item] = Math.max(
                        reservedCatalystStock[item], fromOriginal);
                gross[item] = Math.max(gross[item], required);
            }
        }
        for (int item = 0; item < itemCount; item++) {
            long demand = Sat.mul(c.consumed[recipe][item], quantity);
            if (c.finiteUseAmounts[recipe][item] > 0L) {
                long batches = Sat.ceilDiv(quantity, c.finiteUseLifetimes[recipe][item]);
                demand = Sat.add(
                        demand,
                        Sat.mul(c.finiteUseAmounts[recipe][item], batches));
            }
            if (Sat.isSaturated(demand)
                    || !add(gross, item, demand)
                    || !draw(original, virtual, generated, used, item, demand)) {
                return false;
            }
        }
        for (int item = 0; item < itemCount; item++) {
            long output = Sat.mul(c.produced[recipe][item], quantity);
            if (Sat.isSaturated(output) || !add(generated, item, output)) return false;
        }
        firingMap.put(c.patterns.get(recipe), quantity);
        return true;
    }

    private static <K> boolean overlaps(
            Set<K> states,
            int[] recipes,
            Set<K> coveredStates,
            boolean[] coveredRecipes) {
        if (!Collections.disjoint(states, coveredStates)) return true;
        for (int recipe : recipes) {
            if (recipe < 0 || recipe >= coveredRecipes.length || coveredRecipes[recipe]) return true;
        }
        return false;
    }

    private static <K> FeedbackMacro<K> feedbackMacro(
            ConservativeFeedbackAnalysis.Component<K> component,
            Map<CraftPattern<K>, Integer> recipeIndex,
            Map<K, Integer> itemIndex) {
        int size = component.patterns().size();
        int[] recipes = new int[size];
        int[] outputItems = new int[size];
        Map<CraftPattern<K>, K> transitionOutputs = new IdentityHashMap<>();
        for (ConservativeFeedbackAnalysis.Transition<K> transition : component.cycle()) {
            transitionOutputs.put(transition.pattern(), transition.output());
        }
        for (int offset = 0; offset < size; offset++) {
            CraftPattern<K> pattern = component.patterns().get(offset);
            Integer recipe = recipeIndex.get(pattern);
            Integer output = itemIndex.get(transitionOutputs.get(pattern));
            if (recipe == null || output == null) return null;
            recipes[offset] = recipe;
            outputItems[offset] = output;
        }
        int[] stateItems = stateItems(component.stateOrder(), itemIndex);
        if (stateItems == null) return null;
        var fallback = new ConservativeFeedbackAnalysis.FallbackComponent<>(
                component.states(), component.stateOrder(), component.patterns(), Map.of());
        return new FeedbackMacro<>(
                component.states(), component.stateOrder(), stateItems,
                recipes, outputItems, component, fallback, -1);
    }

    private static <K> FeedbackMacro<K> feedbackMacro(
            ConservativeFeedbackAnalysis.FallbackComponent<K> component,
            Map<CraftPattern<K>, Integer> recipeIndex,
            Map<K, Integer> itemIndex) {
        int size = component.patterns().size();
        int[] recipes = new int[size];
        int[] outputItems = new int[size];
        for (int offset = 0; offset < size; offset++) {
            CraftPattern<K> pattern = component.patterns().get(offset);
            Integer recipe = recipeIndex.get(pattern);
            Integer output = internalOutputItem(pattern, component.stateOrder(), itemIndex);
            if (recipe == null || output == null) return null;
            recipes[offset] = recipe;
            outputItems[offset] = output;
        }
        int[] stateItems = stateItems(component.stateOrder(), itemIndex);
        if (stateItems == null) return null;
        return new FeedbackMacro<>(
                component.states(), component.stateOrder(), stateItems,
                recipes, outputItems, null, component, -1);
    }

    private static <K> Integer internalOutputItem(
            CraftPattern<K> pattern,
            List<K> stateOrder,
            Map<K, Integer> itemIndex) {
        for (K state : stateOrder) {
            if (state.equals(pattern.output())) return itemIndex.get(state);
            for (CraftOutput<K> byproduct : pattern.byproducts()) {
                if (state.equals(byproduct.key())) return itemIndex.get(state);
            }
        }
        return null;
    }

    private static <K> int[] stateItems(List<K> stateOrder, Map<K, Integer> itemIndex) {
        int[] result = new int[stateOrder.size()];
        for (int state = 0; state < stateOrder.size(); state++) {
            Integer item = itemIndex.get(stateOrder.get(state));
            if (item == null) return null;
            result[state] = item;
        }
        return result;
    }

    private static <K> PrimitiveCycle primitiveCycle(
            ConservativeFeedbackAnalysis.Component<K> component,
            Map<CraftPattern<K>, Integer> recipeIndex,
            Map<K, Integer> itemIndex) {
        List<ConservativeFeedbackAnalysis.Transition<K>> transitions = component.cycle();
        for (ConservativeFeedbackAnalysis.RoundVector<K> vector : component.firingVectors()) {
            int size = transitions.size();
            int[] recipes = new int[size];
            int[] inputItems = new int[size];
            long[] inputAmounts = new long[size];
            long[] firings = new long[size];
            boolean valid = true;
            for (int offset = 0; offset < size; offset++) {
                ConservativeFeedbackAnalysis.Transition<K> transition = transitions.get(offset);
                Integer recipe = recipeIndex.get(transition.pattern());
                Integer input = itemIndex.get(transition.input());
                Long firing = vector.firings().get(transition.pattern());
                if (recipe == null || input == null || firing == null || firing <= 0L) {
                    valid = false;
                    break;
                }
                recipes[offset] = recipe;
                inputItems[offset] = input;
                inputAmounts[offset] = transition.inputAmount();
                firings[offset] = firing;
            }
            if (!valid) continue;
            for (int offset = 0; offset < size; offset++) {
                int previous = (offset + size - 1) % size;
                BigInteger produced = BigInteger.valueOf(transitions.get(previous).outputAmount())
                        .multiply(BigInteger.valueOf(firings[previous]));
                BigInteger consumed = BigInteger.valueOf(inputAmounts[offset])
                        .multiply(BigInteger.valueOf(firings[offset]));
                if (!produced.equals(consumed)) {
                    valid = false;
                    break;
                }
            }
            if (valid) return new PrimitiveCycle(
                    recipes, inputItems, inputAmounts, firings);
        }
        return null;
    }

    private static <K> int[][] cycleRecipes(List<ConversionCycle<K>> cycles) {
        int[][] result = new int[cycles.size()][];
        for (int cycle = 0; cycle < cycles.size(); cycle++) {
            result[cycle] = cycles.get(cycle).recipes().clone();
        }
        return result;
    }

    private static <K> int[][] cycleInputItems(List<ConversionCycle<K>> cycles) {
        int[][] result = new int[cycles.size()][];
        for (int cycle = 0; cycle < cycles.size(); cycle++) {
            result[cycle] = cycles.get(cycle).inputItems().clone();
        }
        return result;
    }

    private static <K> long[][] cycleInputAmounts(List<ConversionCycle<K>> cycles) {
        long[][] result = new long[cycles.size()][];
        for (int cycle = 0; cycle < cycles.size(); cycle++) {
            result[cycle] = cycles.get(cycle).inputAmounts().clone();
        }
        return result;
    }

    private static <K> long[][] cyclePrimitiveFirings(List<ConversionCycle<K>> cycles) {
        long[][] result = new long[cycles.size()][];
        for (int cycle = 0; cycle < cycles.size(); cycle++) {
            result[cycle] = cycles.get(cycle).primitiveFirings().clone();
        }
        return result;
    }

    private static <K> int[] reusableItems(List<ReusableGroup<K>> groups) {
        int[] result = new int[groups.size()];
        for (int group = 0; group < groups.size(); group++) {
            result[group] = groups.get(group).item;
        }
        return result;
    }

    private static <K> boolean hasReusableCatalyst(
            Compilation<K> compilation, int recipe, int item) {
        for (int group = 0; group < compilation.reusableGroups.size(); group++) {
            if (compilation.reusableGroups.get(group).item == item
                    && compilation.reusableCatalysts[recipe][group] > 0L) {
                return true;
            }
        }
        return false;
    }

    private static <K> long[] reusableNeeds(Compilation<K> compilation, long[] quantities) {
        long[] result = new long[compilation.reusableGroups.size()];
        for (int recipe = 0; recipe < compilation.patterns.size(); recipe++) {
            if (quantities[recipe] <= 0L) continue;
            for (int group = 0; group < result.length; group++) {
                result[group] = Math.max(
                        result[group], compilation.reusableCatalysts[recipe][group]);
            }
        }
        return result;
    }

    private static long available(long original, long virtual, long generated) {
        long value = Sat.add(Sat.add(original, virtual), generated);
        return Sat.isSaturated(value) ? Sat.SAT : value;
    }

    private static boolean draw(
            long[] original,
            long[] virtual,
            long[] generated,
            long[] used,
            int item,
            long amount) {
        long fromGenerated = Math.min(generated[item], amount);
        generated[item] -= fromGenerated;
        long remaining = amount - fromGenerated;
        long fromMissing = Math.min(virtual[item], remaining);
        virtual[item] -= fromMissing;
        remaining -= fromMissing;
        if (remaining > original[item]) return false;
        original[item] -= remaining;
        return add(used, item, remaining);
    }

    private static boolean drawWithTopUp(
            long[] original,
            long[] virtual,
            long[] generated,
            long[] used,
            long[] replayMissing,
            int item,
            long amount) {
        long fromGenerated = Math.min(generated[item], amount);
        generated[item] -= fromGenerated;
        long remaining = amount - fromGenerated;
        long fromMissing = Math.min(virtual[item], remaining);
        virtual[item] -= fromMissing;
        remaining -= fromMissing;
        long fromOriginal = Math.min(original[item], remaining);
        original[item] -= fromOriginal;
        remaining -= fromOriginal;
        return add(used, item, fromOriginal)
                && add(replayMissing, item, remaining);
    }

    private static Long plannerLong(BigInteger amount) {
        if (amount.signum() < 0
                || amount.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0) {
            return null;
        }
        return amount.longValueExact();
    }

    private static boolean hasPositive(int[] recipes, long[] quantities) {
        for (int recipe : recipes) {
            if (quantities[recipe] > 0L) return true;
        }
        return false;
    }

    private static boolean add(long[] values, int index, long amount) {
        if (amount == 0L) return true;
        long next = Sat.add(values[index], amount);
        if (Sat.isSaturated(next)) return false;
        values[index] = next;
        return true;
    }

    private static <K> ReusableGroup<K> reusableGroup(
            CraftGraph<K> graph, ReusableStockSource source, K plannedKey, int item) {
        List<K> actualKeys = List.copyOf(graph.reusableStockCandidates(source, plannedKey));
        return new ReusableGroup<>(
                new ReusableStockRouteKey<>(source, plannedKey),
                source, plannedKey, item, actualKeys);
    }

    private record Compilation<K>(
            List<K> items,
            List<CraftPattern<K>> patterns,
            long[][] consumed,
            long[][] produced,
            long[][] catalysts,
            long[][] finiteUseAmounts,
            long[][] finiteUseLifetimes,
            long[][] reusableCatalysts,
            int[] outputItems,
            int[] primaryOutputItems,
            long[] primaryOutputAmounts,
            int[] rankGroups,
            List<ConversionCycle<K>> conversionCycles,
            int[] conversionCycleByRecipe,
            List<FeedbackMacro<K>> feedbackMacros,
            int[] feedbackMacroByRecipe,
            long[] stocks,
            List<ReusableGroup<K>> reusableGroups,
            int[][] reusableCandidatePhysicals,
            long[] reusablePhysicalStocks,
            int[] itemDistances,
            long[] firingUpperBounds,
            int targetItem) {
    }

    private record ReusableGroup<K>(
            ReusableStockRouteKey<K> route,
            ReusableStockSource source,
            K plannedKey,
            int item,
            List<K> actualKeys) {
    }

    private record PrimitiveCycle(
            int[] recipes,
            int[] inputItems,
            long[] inputAmounts,
            long[] firings) {
    }

    private record ConversionCycle<K>(
            Set<K> states,
            int[] recipes,
            int[] inputItems,
            long[] inputAmounts,
            long[] primitiveFirings,
            FeedbackMacro<K> replayMacro,
            int rankGroup) {
        private ConversionCycle<K> withRankGroup(int group) {
            return new ConversionCycle<>(
                    states, recipes, inputItems, inputAmounts, primitiveFirings,
                    replayMacro.withRankGroup(group), group);
        }
    }

    private record FeedbackMacro<K>(
            Set<K> states,
            List<K> stateOrder,
            int[] stateItems,
            int[] recipes,
            int[] outputItems,
            ConservativeFeedbackAnalysis.Component<K> exact,
            ConservativeFeedbackAnalysis.FallbackComponent<K> fallback,
            int rankGroup) {
        private FeedbackMacro<K> withRankGroup(int group) {
            return new FeedbackMacro<>(
                    states, stateOrder, stateItems, recipes, outputItems, exact, fallback, group);
        }
    }
}

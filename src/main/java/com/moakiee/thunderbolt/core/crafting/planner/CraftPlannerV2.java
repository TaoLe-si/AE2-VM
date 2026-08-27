package com.moakiee.thunderbolt.core.crafting.planner;

import com.moakiee.thunderbolt.core.crafting.pattern.ReusableStockSource;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * v2 autocrafting planner: an iterative linear backbone plus bounded integer flow over shared
 * material conflicts, with ordered execution validation for stateful recipes and bounded fallback.
 *
 * <p>This is the evolution of the v1 planner (closed-form two-pass, removed). It keeps v1's strengths
 * — quantity-independent batching ({@code ceil} arithmetic), {@code returned} (container/catalyst)
 * inputs in closed form, saturating arithmetic — and adds:
 *
 * <ul>
 *   <li><b>In-engine cycle breaking ("去头尾")</b>: instead of declining when the recipe graph has a
 *       cycle, a DFS from the target drops back-edges, keeping the recipe direction toward the target
 *       and cutting the reverse. A compress/decompress pair (1 block ⇄ 9 ingots) is planned directly;
 *       the reverse side resolves from stock/missing. Cuts only remove options, so feasibility is never
 *       overstated (no false positives).</li>
 *   <li><b>Shared pool + byproducts</b>: stock and crafting byproducts live in one mutable pool;
 *       demand draws byproducts first, then stock, then crafts. Multi-output patterns are supported
 *       (a pattern's extra outputs feed sibling demands).</li>
 *   <li><b>Dynamic-capacity greedy</b>: among an item's recipes, the one with the highest current
 *       capacity ({@code stock + craftable}) is preferred. Concrete fuzzy expansions of one real
 *       recipe are kept in a source group and consume already-stocked variants before recursively
 *       crafting another accepted variant.</li>
 *   <li><b>Budgeted backtracking</b>: contended items (more than one recipe) are searched in
 *       capacity order with a {@code trail} for commit/rollback. No node drops candidates at a fixed
 *       route count: a hot node re-ranks every materially distinct route against the current pools and
 *       search continues while the plan-wide deterministic work budget remains. Failed speculative
 *       subtrees are memoized only for the exact node, amount, depth and rollback-restored availability
 *       state, preventing repeated proof work without reusing a stale inventory result.</li>
 *   <li><b>Global integer flow</b>: every bounded-width material conflict, including a single fork
 *       that competes with a later sibling, is solved as one integral balance model. Its firing vector
 *       is validated in deterministic producer/seed order; ordinary recipe choices never trigger
 *       a whole-plan replay. Unsupported stateful or over-budget components retain the bounded local
 *       fallback, while conversion SCCs may retry a small fixed set of cut orientations.</li>
 *   <li><b>Consumable-bound no-goods</b>: an unavoidable direct raw-consumable shortage is proven
 *       against the exact current pool and quantity, rather than tied to a whole recipe identity.
 *       Thus materially different routes that both require eight unavailable C are pruned alike,
 *       while a route requiring one available C remains eligible.</li>
 * </ul>
 *
 * <p><b>Soundness (no false positives):</b> the pool is never overdrawn (a draw is capped by what is
 * actually present), so a plan reports {@link CraftPlan#feasible() feasible} only when every demand was
 * met from stock or from a craft whose own inputs were met. Shortfalls always surface in
 * {@link CraftPlan#missing()}.
 */
public final class CraftPlannerV2<K> {

    /**
     * Default hot-node threshold. It does not truncate search: after this many visits the node merely
     * switches from its immutable capacity order to current-pool re-ranking. Normal graphs are resolved
     * by the linear backbone or recover via a handful of backtracks, never approaching this threshold.
     */
    public static final int DEFAULT_VISIT_CAP = 256;

    /** Upper guard for the graph-scaled {@code O(E log E)} default search budget. */
    public static final int DEFAULT_SEARCH_WORK_BUDGET =
            Math.max(4_096, Integer.getInteger("thunderbolt.maxCraftSearchWork", 262_144));

    private static final int MIN_SEARCH_WORK_BUDGET = 4_096;
    private static final int FALLBACK_WORK_PER_REACHABLE_UNIT = 64;
    private static final int MAX_FALLBACK_WORK_BUDGET = 262_144;
    /**
     * Whole-graph preprocessing guard. Optional exact/local stages have their own smaller budgets,
     * but even linear normalization becomes disruptive on a deliberately enormous reachable graph.
     * Exceeding this bound reports the requested key as missing (Policy A), never feasible.
     */
    private static final int MAX_REACHABLE_PLANNING_WORK = Math.min(
            Integer.MAX_VALUE - 1,
            Math.max(4_096,
                    Integer.getInteger("thunderbolt.maxReachablePlanningWork", 65_536)));
    /** Generic exact-flow gate; ordinary and individually wide components skip dense solving. */
    private static final int MAX_LOW_WIDTH_SEPARATOR = 12;
    /** Unsafe-but-validated byproduct balances may need a wider frontier than ordinary flow. */
    private static final int MAX_VALIDATED_BYPRODUCT_SEPARATOR = 36;
    private static final int MAX_LOW_WIDTH_VARIABLES = 96;
    private static final int MAX_LOW_WIDTH_CONSTRAINTS = 128;
    private static final int MAX_LOW_WIDTH_INTEGER_NODES = 64;
    /** Dense exact solving is optional; keep its worst case below the ordinary fallback cost. */
    private static final long MAX_LOW_WIDTH_TABLEAU_CELLS = Math.max(
            1_024L,
            Long.getLong("thunderbolt.maxLowWidthTableauCells", 16_384L));
    private static final long MAX_LOW_WIDTH_CELL_WORK = Math.max(
            MAX_LOW_WIDTH_TABLEAU_CELLS,
            Long.getLong("thunderbolt.maxLowWidthCellWork", 2_097_152L));
    /** Optional byproduct-order analysis is abandoned before a huge fan-out can dominate planning. */
    private static final long MAX_BYPRODUCT_SCHEDULE_WORK = Math.max(
            1_024L,
            Long.getLong("thunderbolt.maxByproductScheduleWork", 16_384L));
    /**
     * Maximum number of alternate roots tried for a proven conservative conversion SCC. This is a
     * fixed bound, so cycle orientation remains linear in graph size rather than enumerating cuts.
     */
    static final int MAX_CONVERSION_ORIENTATION_RETRIES = 16;

    /**
     * Stack-overflow safety net for the bounded fallback search. {@link #obtain} recurses once per
     * crafting edge along a single root-to-leaf path ({@code obtain → fire → obtain}), so its stack depth
     * equals the depth of the (acyclic) recipe DAG. The clean linear backbone ({@link #linearPass}) is
     * fully iterative and resolves every feasible, non-contended request without recursing — the
     * recursion is entered only when that backbone reports infeasible or hits contention. To keep a
     * pathologically deep recipe chain from overflowing the calculating thread's stack, descent past this
     * many levels degrades only that branch to "missing" (Policy A), allowing its parent to try another
     * route. 256 levels (~512 stack frames with the paired {@code fire}) is far
     * deeper than any real Minecraft recipe chain yet safe on a default thread stack; overridable via
     * {@code -Dthunderbolt.maxCraftDepth} for unusual {@code -Xss} setups.
     */
    public static final int MAX_OBTAIN_DEPTH =
            Math.max(16, Integer.getInteger("thunderbolt.maxCraftDepth", 256));

    /**
     * Single-threaded state shared by every amount probe of one external crafting calculation.
     *
     * <p>AE2's {@code CRAFT_LESS} strategy may call the planner once per quantity bit. Reusing this
     * object makes those probes share exact/search/fallback budgets and immutable graph compilation,
     * instead of multiplying every bound by {@code O(log requestedAmount)}. Create one session per
     * calculation and discard it when that calculation finishes.
     */
    public static final class PlanningSession<K> {
        private CraftGraph<K> graph;
        private K target;
        private Thread owner;
        private BoundedIntegerLinearSolver.WorkBudget lowWidthWorkBudget;
        private SharedCounterBudget searchWorkBudget;
        private SharedCounterBudget resolutionWorkBudget;
        private SharedCounterBudget fallbackWorkBudget;
        private final Map<List<K>, PreparedGraph<K>> preparedByOrientation = new HashMap<>();
        private int reachableWorkEstimate;

        public PlanningSession() {
        }

        private int reachableWork(CraftGraph<K> candidateGraph, K candidateTarget) {
            validateOwner(candidateGraph, candidateTarget);
            if (reachableWorkEstimate == 0) {
                reachableWorkEstimate = CraftPlannerV2.reachableWorkEstimate(
                        candidateGraph, candidateTarget);
            }
            return reachableWorkEstimate;
        }

        private void bind(
                CraftGraph<K> candidateGraph,
                K candidateTarget,
                int searchLimit,
                int resolutionLimit,
                int fallbackLimit) {
            validateOwner(candidateGraph, candidateTarget);
            if (lowWidthWorkBudget == null) {
                lowWidthWorkBudget = BoundedIntegerLinearSolver.WorkBudget.wallClockBounded(
                        MAX_LOW_WIDTH_TABLEAU_CELLS,
                        MAX_LOW_WIDTH_CELL_WORK,
                        PlanningCancellation.remainingNanos(Long.MAX_VALUE));
                searchWorkBudget = new SharedCounterBudget(searchLimit);
                resolutionWorkBudget = new SharedCounterBudget(resolutionLimit);
                fallbackWorkBudget = new SharedCounterBudget(fallbackLimit);
            }
        }

        private void validateOwner(CraftGraph<K> candidateGraph, K candidateTarget) {
            PlanningCancellation.check();
            Thread current = Thread.currentThread();
            if (graph == null) {
                graph = candidateGraph;
                target = candidateTarget;
                owner = current;
                return;
            }
            if (owner != current) {
                throw new IllegalStateException("planning session cannot move between threads");
            }
            if (graph != candidateGraph || !Objects.equals(target, candidateTarget)) {
                throw new IllegalArgumentException(
                        "planning session is already bound to another graph or target");
            }
        }
    }

    private final CraftGraph<K> graph;
    private final int visitCap;
    private final SearchBudget searchBudget;
    private final FallbackBudget fallbackBudget;
    /** Shared by every local component, cut-orientation retry, and quantity probe in one session. */
    private final BoundedIntegerLinearSolver.WorkBudget lowWidthWorkBudget;
    private final DiagnosticsCollector diagnostics;
    /** Root-distance tiers used by every exact component in the current run. */
    private Map<K, Integer> objectiveDistances = Map.of();
    /** Immutable graph analysis shared by every quantity probe with the same cycle orientation. */
    private PreparedGraph<K> preparedGraph;
    /** Active producer-before-byproduct-row order for linear and aggregate sweeps in this run. */
    private List<K> activeReplayOrder = List.of();
    private final Set<K> cutOutputs = new LinkedHashSet<>();
    private final Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs =
            new IdentityHashMap<>();
    /** One physical remainder batch withheld from linear byproduct credit to start a feedback path. */
    private final Map<CraftPattern<K>, Map<K, Long>> linearContainerBootstrapReserves =
            new IdentityHashMap<>();
    private final Map<K, Long> reservedSelfSeeds = new HashMap<>();
    /**
     * A narrowly proven two-node startup path for a contracted loop:
     * {@code A -> returned seed B -> net A}. The normal {@code A -> B} converter is also the
     * requested final step, so one physical A must be held aside long enough to manufacture the
     * first B seed instead of being consumed as ordinary final-output input.
     */
    private final Map<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> feedbackSeedBootstraps =
            new IdentityHashMap<>();
    private final Map<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> feedbackSeedConverters =
            new IdentityHashMap<>();
    private final Map<FeedbackSeedBootstrap<K>, Long> reservedFeedbackSeedOutputs =
            new HashMap<>();
    /** Portion of each held feedback-output state borrowed from its private reusable-seed host. */
    private final Map<FeedbackSeedBootstrap<K>, Long> reservedFeedbackSeedHostOutputs =
            new HashMap<>();
    /** Proven ordinary feedback state machines, classified before linear/integer planning. */
    private List<ConservativeFeedbackAnalysis.Component<K>> conservativeFeedbackComponents = List.of();
    /** Proven non-growing ordinary SCCs that use bounded canonical prefix replay. */
    private List<ConservativeFeedbackAnalysis.FallbackComponent<K>>
            canonicalFeedbackFallbackComponents = List.of();
    /** Component members that can obtain their first state from an acyclic producer. */
    private final Set<CraftPattern<K>> craftableConservativeFeedbackPatterns =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean requiresSeedOrderedPlanning;
    /** Ordinary unchanged catalysts may share one seed in the linear pass when no byproduct can feed it. */
    private final Set<K> ordinaryReturnedSeedKeys = new HashSet<>();
    private final Set<K> reachableByproductKeys = new HashSet<>();
    /**
     * Outputs whose resolution can reach a seed-order-sensitive pattern. Aggregating one of these
     * outputs could reorder sibling seed acquisitions, while ordinary dependency cones below the
     * sensitive pattern remain safe to fold into one topological sweep.
     */
    private final Set<K> seedOrderedDependencyCone = new HashSet<>();

    // Current recursion depth of the bounded fallback search (obtain/fire). Guards against stack overflow
    // on degenerate deep chains; see MAX_OBTAIN_DEPTH. Not part of the rolled-back planning state.
    private int depth;

    /** Memo for {@link #isAggregable}: which nodes resolve deterministically without route search. */
    private final Map<K, Boolean> aggregableMemo = new HashMap<>();
    /** Lazily built key set for {@link #byproductFeedableKeys()}. */
    private Set<K> byproductFeedableKeys;

    private final Map<K, List<CraftPattern<K>>> patternsByOutput = new HashMap<>();
    /**
     * Capacity is immutable after the DAG pass. Keep both the score and the stable order so fallback
     * retries do not repeatedly sort the same patterns and re-walk every input from inside TimSort's
     * comparator.
     */
    private final Map<CraftPattern<K>, Long> capacityScoreByPattern = new IdentityHashMap<>();
    private final Map<K, List<CraftPattern<K>>> capacityOrderByOutput = new HashMap<>();
    /** Per-firing unavoidable ordinary raw inputs, aggregated by consumable rather than pattern slot. */
    private final Map<CraftPattern<K>, Map<K, Long>> directRawConsumablesByPattern =
            new IdentityHashMap<>();
    /**
     * Learned lower bounds keyed by the consumable and exact rollback-restored availability state.
     * The value is the smallest quantity already proven unavailable in that state, so the proof is
     * reusable across unrelated patterns with an equal or larger requirement.
     */
    private final Map<ConsumableProofState<K>, Long> provenDirectConsumableShortfalls =
            new HashMap<>();
    // Canonical, stock-independent material transformation for patterns whose whole downstream tree
    // can be proven simple and deterministic. Equal ids let one obtain() call search an equivalent
    // branch once instead of reopening the same dependency tree under a different intermediate key.
    private final Map<CraftPattern<K>, Integer> materialFootprintByPattern = new IdentityHashMap<>();
    private Map<K, Long> capacity;

    // Mutable planning state (all writes go through the trail so a branch can be rolled back).
    private final Map<K, Long> bpPool = new HashMap<>();      // byproduct / surplus supply
    private final Map<K, Long> stockLeft = new HashMap<>();   // remaining inventory snapshot
    private final Map<K, Long> usedStock = new HashMap<>();   // drawn from inventory
    /** Route-private host borrows that may still be reassigned by the global variant matcher. */
    private final Map<ReusableStockRouteKey<K>, Long> reusableBorrowedDemand = new HashMap<>();
    /** Returned non-exact variants, reusable only by the route/consumer that owns them. */
    private final Map<ReusableStockRouteKey<K>, Long> reusablePrivatePool = new HashMap<>();
    /** Returned exact variants, safely reusable by every route in the logical shared pool. */
    private final Map<ReusableStockKey<K>, Long> reusablePool = new HashMap<>();
    /** Exact host allocations already exposed as shared credit; these can no longer be rematched. */
    private final Map<ReusableStockUsageKey<K>, Long> pinnedExactReusableStock = new HashMap<>();
    private final Map<ReusableStockUsageKey<K>, Long> usedReusableStock = new HashMap<>();
    private final Map<K, Long> missing = new HashMap<>();     // unmet at raw leaves
    private final Map<K, Long> grossDemand = new HashMap<>(); // pre-extraction request totals (bytes)
    private final Map<CraftPattern<K>, Long> firings = new IdentityHashMap<>();
    /** Exact component quotas retained when only an unresolved sibling reaches recursive fallback. */
    private final Map<CraftPattern<K>, Long> fixedFiringQuota = new IdentityHashMap<>();
    private final Set<K> fixedFallbackItems = new HashSet<>();
    /** Pure-material outputs with batch routes that need split primary credit during fixed replay. */
    private final Set<K> fixedBatchReplayOutputs = new HashSet<>();
    // Node-local search state is monotonic: rollback restores inventory, not knowledge already learned.
    private final Map<K, Integer> visit = new HashMap<>();
    // Exact failure memo for speculative calls. availabilityState is restored with trail rollback,
    // so a proof is reused only when node, amount and every availability-affecting map are identical.
    private final Set<SearchFailure<K>> failedSpeculativeSearches = new HashSet<>();
    private final Deque<Runnable> trail = new ArrayDeque<>();
    private long availabilityState;
    private long nextAvailabilityState;
    private int processed;

    // Running sum of all unmet (missing) amounts; trail-restored. A search branch is accepted iff it
    // introduces no new missing, so the decision survives nested single-recipe commits.
    private long missingTotal;

    private CraftPlannerV2(
            CraftGraph<K> graph,
            int visitCap,
            SearchBudget searchBudget,
            BoundedIntegerLinearSolver.WorkBudget lowWidthWorkBudget,
            DiagnosticsCollector diagnostics) {
        this.graph = graph;
        this.visitCap = Math.max(1, visitCap);
        this.searchBudget = searchBudget;
        this.fallbackBudget = new FallbackBudget(
                diagnostics.fallbackBudgetLimit(),
                diagnostics.sharedFallbackWorkBudget,
                diagnostics);
        this.lowWidthWorkBudget = lowWidthWorkBudget;
        this.diagnostics = diagnostics;
    }

    private CraftPlannerV2(
            PreparedGraph<K> preparedGraph,
            int visitCap,
            SearchBudget searchBudget,
            BoundedIntegerLinearSolver.WorkBudget lowWidthWorkBudget,
            DiagnosticsCollector diagnostics) {
        this(
                preparedGraph.graph,
                visitCap,
                searchBudget,
                lowWidthWorkBudget,
                diagnostics);
        this.preparedGraph = preparedGraph;
        loadPreparedGraph(preparedGraph);
    }

    public static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount) {
        int reachableWork = reachableWorkEstimate(graph, target);
        return planDetailed(
                graph, target, amount, DEFAULT_VISIT_CAP,
                scaledSearchWorkBudget(reachableWork), reachableWork,
                new PlanningSession<>()).plan();
    }

    public static <K> CraftPlan<K> plan(CraftGraph<K> graph, K target, long amount, int visitCap) {
        int reachableWork = reachableWorkEstimate(graph, target);
        return planDetailed(
                graph, target, amount, visitCap,
                scaledSearchWorkBudget(reachableWork), reachableWork,
                new PlanningSession<>()).plan();
    }

    static <K> CraftPlan<K> plan(
            CraftGraph<K> graph,
            K target,
            long amount,
            int visitCap,
            int searchWorkBudget) {
        return planDetailed(graph, target, amount, visitCap, searchWorkBudget).plan();
    }

    /**
     * Plans the request and returns measurements collected by that exact run. Diagnostics never cause
     * a second traversal or a second planning pass.
     */
    public static <K> PlanningResult<K> planDetailed(
            CraftGraph<K> graph, K target, long amount) {
        int reachableWork = reachableWorkEstimate(graph, target);
        return planDetailed(
                graph, target, amount, DEFAULT_VISIT_CAP,
                scaledSearchWorkBudget(reachableWork), reachableWork,
                new PlanningSession<>());
    }

    /** Reuses immutable compilation and all work budgets across quantity probes of one calculation. */
    public static <K> PlanningResult<K> planDetailed(
            CraftGraph<K> graph,
            K target,
            long amount,
            PlanningSession<K> session) {
        Objects.requireNonNull(session, "session");
        int reachableWork = session.reachableWork(graph, target);
        return planDetailed(
                graph, target, amount, DEFAULT_VISIT_CAP,
                scaledSearchWorkBudget(reachableWork), reachableWork, session);
    }

    static <K> PlanningResult<K> planDetailed(
            CraftGraph<K> graph,
            K target,
            long amount,
            int visitCap,
            int searchWorkBudget) {
        return planDetailed(
                graph, target, amount, visitCap, searchWorkBudget,
                reachableWorkEstimate(graph, target), new PlanningSession<>());
    }

    private static <K> PlanningResult<K> planDetailed(
            CraftGraph<K> graph,
            K target,
            long amount,
            int visitCap,
            int searchWorkBudget,
            int reachableWork,
            PlanningSession<K> session) {
        long started = System.nanoTime();
        int fallbackLimit = fallbackWorkBudget(reachableWork);
        session.bind(
                graph,
                target,
                searchWorkBudget,
                fallbackLimit,
                fallbackLimit);
        DiagnosticsCollector diagnostics =
                new DiagnosticsCollector(reachableWork, searchWorkBudget, session);
        if (amount <= 0) {
            CraftPlan<K> empty = new CraftPlan<>(
                    true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
            return new PlanningResult<>(empty, diagnostics.finish(started, null));
        }
        if (reachableWork > MAX_REACHABLE_PLANNING_WORK) {
            CraftPlan<K> boundedMissing = new CraftPlan<>(
                    true,
                    false,
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(target, amount),
                    Map.of(target, amount),
                    0,
                    true);
            return new PlanningResult<>(boundedMissing, diagnostics.finish(started, null));
        }
        Map<K, Integer> objectiveDistances = shortestInputDistances(graph, target);
        SearchBudget budget = new SearchBudget(
                searchWorkBudget, session.searchWorkBudget, diagnostics);
        BoundedIntegerLinearSolver.WorkBudget lowWidthWorkBudget = session.lowWidthWorkBudget;
        int orientationCharge = reachableWork;
        Map<List<K>, PreparedGraph<K>> preparedByOrientation = session.preparedByOrientation;
        List<K> firstOrientation = List.of();
        PreparedGraph<K> firstPrepared =
                preparedByOrientation.get(firstOrientation);
        CraftPlannerV2<K> firstPlanner = firstPrepared == null
                ? new CraftPlannerV2<>(
                        graph,
                        visitCap,
                        budget,
                        lowWidthWorkBudget,
                        diagnostics)
                : new CraftPlannerV2<>(
                        firstPrepared,
                        visitCap,
                        budget,
                        lowWidthWorkBudget,
                        diagnostics);
        firstPlanner.objectiveDistances = objectiveDistances;
        CraftPlan<K> first = firstPlanner.run(target, amount, firstOrientation);
        preparedByOrientation.put(firstOrientation, firstPlanner.preparedGraph);
        if (first.feasible()) {
            return finish(first, diagnostics, budget, started);
        }
        if (first.budgetExhausted()) {
            return finish(first, diagnostics, budget, started);
        }
        CraftPlan<K> bestIncomplete = first;

        Deque<List<K>> frontier = new ArrayDeque<>();

        // A conversion SCC may still need a different cut orientation. This is bounded separately
        // from ordinary recipe choice, which is now resolved inside one integer-flow plan run.
        if (!firstPlanner.cutOutputs.isEmpty()) {
            CycleAnalysis<K> cycleAnalysis = CycleAnalysis.analyze(graph, target);
            // The DFS cut position inside a cycle depends on sibling arrival order, so a bad cut can
            // surface its shortfall on ANOTHER member of the same cycle instead of on the recorded
            // cut output. Attribute missing at member granularity and try the most-starved
            // orientation first, so the bounded retries go to the cuts that actually hurt.
            List<Map.Entry<K, Long>> reorientCandidates = new ArrayList<>();
            for (K cutOutput : firstPlanner.cutOutputs) {
                if (!cycleAnalysis.mayReorient(cutOutput)) {
                    continue;
                }
                long attributedMissing = first.missing().getOrDefault(cutOutput, 0L);
                for (K member : cycleAnalysis.membersOf(cutOutput)) {
                    if (!member.equals(cutOutput)) {
                        attributedMissing = Sat.add(
                                attributedMissing, first.missing().getOrDefault(member, 0L));
                    }
                }
                if (attributedMissing > 0) {
                    reorientCandidates.add(Map.entry(cutOutput, attributedMissing));
                }
            }
            reorientCandidates.sort((left, right) -> Long.compare(right.getValue(), left.getValue()));
            int retries = 0;
            for (Map.Entry<K, Long> candidate : reorientCandidates) {
                if (retries >= MAX_CONVERSION_ORIENTATION_RETRIES) break;
                retries++;
                frontier.addLast(List.of(candidate.getKey()));
            }
        }

        diagnostics.recordFrontierSize(frontier.size());

        while (!frontier.isEmpty()) {
            List<K> orientation = frontier.removeFirst();
            if (!budget.tryConsume(orientationCharge)) {
                return finish(markBudgetExhausted(bestIncomplete), diagnostics, budget, started);
            }
            PreparedGraph<K> prepared = preparedByOrientation.get(orientation);
            CraftPlannerV2<K> planner = prepared == null
                    ? new CraftPlannerV2<>(
                            graph,
                            visitCap,
                            budget,
                            lowWidthWorkBudget,
                            diagnostics)
                    : new CraftPlannerV2<>(
                            prepared,
                            visitCap,
                            budget,
                            lowWidthWorkBudget,
                            diagnostics);
            planner.objectiveDistances = objectiveDistances;
            CraftPlan<K> candidate = planner.run(target, amount, orientation);
            preparedByOrientation.putIfAbsent(orientation, planner.preparedGraph);
            if (candidate.feasible()) {
                return finish(candidate, diagnostics, budget, started);
            }
            if (candidate.budgetExhausted()) {
                return finish(
                        markBudgetExhausted(betterPlan(
                                bestIncomplete, candidate, objectiveDistances)),
                        diagnostics, budget, started);
            }
            bestIncomplete = betterPlan(bestIncomplete, candidate, objectiveDistances);
            diagnostics.recordFrontierSize(frontier.size());
        }
        return finish(bestIncomplete, diagnostics, budget, started);
    }

    private static <K> PlanningResult<K> finish(
            CraftPlan<K> plan,
            DiagnosticsCollector diagnostics,
            SearchBudget budget,
            long started) {
        return new PlanningResult<>(plan, diagnostics.finish(started, budget));
    }

    /**
     * The first planner run remains the ordinary linear/integer path. A bounded conversion-cut retry
     * is charged by the whole reachable graph size, keeping total work near {@code O(E log E)}.
     */
    static <K> int scaledSearchWorkBudget(CraftGraph<K> graph, K target) {
        return scaledSearchWorkBudget(reachableWorkEstimate(graph, target));
    }

    private static int scaledSearchWorkBudget(int work) {
        int log = 32 - Integer.numberOfLeadingZeros(Math.max(1, work));
        long scaled = (long) work * (log + 4L);
        return (int) Math.min(
                DEFAULT_SEARCH_WORK_BUDGET,
                Math.max(MIN_SEARCH_WORK_BUDGET, scaled));
    }

    private static int fallbackWorkBudget(int reachableWork) {
        long scaled = (long) Math.max(1, reachableWork) * FALLBACK_WORK_PER_REACHABLE_UNIT;
        return (int) Math.min(MAX_FALLBACK_WORK_BUDGET, Math.max(64L, scaled));
    }

    static <K> int reachableWorkEstimate(CraftGraph<K> graph, K target) {
        PlanningCancellation.check();
        Set<K> seen = new HashSet<>();
        Deque<K> queue = new ArrayDeque<>();
        seen.add(target);
        queue.add(target);
        long work = 0L;
        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            K key = queue.removeFirst();
            work++;
            if (work > MAX_REACHABLE_PLANNING_WORK) {
                return MAX_REACHABLE_PLANNING_WORK + 1;
            }
            for (CraftPattern<K> pattern : graph.patternsFor(key)) {
                work++;
                if (work > MAX_REACHABLE_PLANNING_WORK) {
                    return MAX_REACHABLE_PLANNING_WORK + 1;
                }
                for (CraftInput<K> input : pattern.inputs()) {
                    work++;
                    if (work > MAX_REACHABLE_PLANNING_WORK) {
                        return MAX_REACHABLE_PLANNING_WORK + 1;
                    }
                    if (seen.add(input.key())) {
                        queue.addLast(input.key());
                    }
                }
                for (CraftOutput<K> ignored : pattern.byproducts()) {
                    work++;
                    if (work > MAX_REACHABLE_PLANNING_WORK) {
                        return MAX_REACHABLE_PLANNING_WORK + 1;
                    }
                }
            }
        }
        return Math.max(1, (int) work);
    }

    /**
     * Exact planner preference: missing first, then stock use, then recipe executions. Missing and
     * stock are compared as vectors indexed by shortest dependency distance from the requested
     * root. Missing is minimized near-to-far, while stock use is minimized far-to-near: the latter
     * prefers consuming an already-made near-root item over consuming deeper ingredients to remake
     * it, without rewarding a recipe merely for consuming more units. This avoids overflow-prone
     * scalar weights and makes the priority independent of request magnitude.
     */
    private static <K> CraftPlan<K> betterPlan(
            CraftPlan<K> current,
            CraftPlan<K> candidate,
            Map<K, Integer> distances) {
        int comparison = compareGraded(
                gradedAmounts(candidate.missing(), Map.of(), distances),
                gradedAmounts(current.missing(), Map.of(), distances),
                false);
        if (comparison < 0) return candidate;
        if (comparison > 0) return current;

        comparison = compareGraded(
                gradedAmounts(candidate.usedStock(), candidate.usedReusableStock(), distances),
                gradedAmounts(current.usedStock(), current.usedReusableStock(), distances),
                true);
        if (comparison < 0) return candidate;
        if (comparison > 0) return current;

        long candidateExecutions = executionTotal(candidate);
        long currentExecutions = executionTotal(current);
        return candidateExecutions < currentExecutions ? candidate : current;
    }

    private static <K> java.util.NavigableMap<Integer, Long> gradedAmounts(
            Map<K, Long> ordinary,
            Map<ReusableStockUsageKey<K>, Long> reusable,
            Map<K, Integer> distances) {
        java.util.NavigableMap<Integer, Long> tiers = new java.util.TreeMap<>();
        for (Map.Entry<K, Long> entry : ordinary.entrySet()) {
            int distance = distances.getOrDefault(entry.getKey(), Integer.MAX_VALUE);
            tiers.merge(distance, entry.getValue(), Sat::add);
        }
        for (Map.Entry<ReusableStockUsageKey<K>, Long> entry : reusable.entrySet()) {
            int distance = distances.getOrDefault(entry.getKey().key(), Integer.MAX_VALUE);
            tiers.merge(distance, entry.getValue(), Sat::add);
        }
        return tiers;
    }

    private static int compareGraded(
            java.util.NavigableMap<Integer, Long> left,
            java.util.NavigableMap<Integer, Long> right,
            boolean deepestFirst) {
        java.util.NavigableSet<Integer> tiers = new java.util.TreeSet<>(left.keySet());
        tiers.addAll(right.keySet());
        Iterable<Integer> orderedTiers = deepestFirst ? tiers.descendingSet() : tiers;
        for (int tier : orderedTiers) {
            int comparison = Long.compare(
                    left.getOrDefault(tier, 0L), right.getOrDefault(tier, 0L));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private static long executionTotal(CraftPlan<?> plan) {
        long total = 0L;
        for (long executions : plan.firings().values()) total = Sat.add(total, executions);
        return total;
    }

    private static <K> Map<K, Integer> shortestInputDistances(CraftGraph<K> graph, K target) {
        Map<K, Integer> distances = new LinkedHashMap<>();
        Deque<K> queue = new ArrayDeque<>();
        distances.put(target, 0);
        queue.addLast(target);
        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            K output = queue.removeFirst();
            int nextDistance = distances.get(output) + 1;
            for (CraftPattern<K> pattern : graph.patternsFor(output)) {
                for (CraftInput<K> input : pattern.inputs()) {
                    Integer previous = distances.get(input.key());
                    if (previous == null || nextDistance < previous) {
                        distances.put(input.key(), nextDistance);
                        queue.addLast(input.key());
                    }
                }
            }
        }
        return Map.copyOf(distances);
    }

    private static <K> CraftPlan<K> markBudgetExhausted(CraftPlan<K> plan) {
        return new CraftPlan<>(
                plan.supported(),
                plan.feasible(),
                plan.firings(),
                plan.usedStock(),
                plan.usedReusableStock(),
                plan.missing(),
                plan.grossDemand(),
                plan.itemsProcessed(),
                true);
    }

    private CraftPlan<K> run(K target, long amount, List<K> priorityRoots) {
        PlanningCancellation.check();
        diagnostics.recordPlanRun();
        if (amount <= 0) {
            return new CraftPlan<>(true, true, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0, false);
        }
        if (objectiveDistances.isEmpty()) {
            objectiveDistances = shortestInputDistances(graph, target);
        }

        List<K> order;
        Set<K> items;
        if (preparedGraph == null) {
            long compileStarted = System.nanoTime();
            identifyPositiveFeedbackByproducts(target);

            // Build an acyclic view of the reachable recipe graph: a DFS from the target drops any
            // recipe whose input is an ancestor still being expanded (a back-edge), i.e. AE2's
            // "去头尾". The resulting graph facts are frozen and reused by amount probes and any
            // retry of this cycle orientation.
            items = new LinkedHashSet<>();
            List<K> postOrder = new ArrayList<>();
            buildDag(target, priorityRoots, postOrder, items);
            if (!requiresSeedOrderedPlanning
                    && ordinaryReturnedSeedKeys.stream().anyMatch(reachableByproductKeys::contains)) {
                // A byproduct might otherwise appear in the aggregate pool before the pattern that
                // needs the catalyst is executable.
                requiresSeedOrderedPlanning = true;
            }
            order = new ArrayList<>(postOrder.size());
            for (int i = postOrder.size() - 1; i >= 0; i--) {
                order.add(postOrder.get(i));
            }
            // Include primary and byproduct material flow in SCC classification before any
            // algebraic pass can treat a returned state as supply. Only proven non-growing marked
            // graphs survive as ordinary feedback components; gain/ambiguous loops are excluded.
            ConservativeFeedbackAnalysis.Analysis<K> feedbackAnalysis =
                    ConservativeFeedbackAnalysis.analyzeAll(order, patternsByOutput);
            conservativeFeedbackComponents = feedbackAnalysis.components();
            canonicalFeedbackFallbackComponents = feedbackAnalysis.fallbacks();
            boolean requiresCraftableFeedbackOrder = false;
            for (ConservativeFeedbackAnalysis.Component<K> component
                    : conservativeFeedbackComponents) {
                if (component.hasExternalProducer()) {
                    craftableConservativeFeedbackPatterns.addAll(component.patterns());
                    boolean containerFeedback = component.patterns().stream()
                            .flatMap(pattern -> pattern.inputs().stream())
                            .anyMatch(input -> input.remainder() != null);
                    requiresCraftableFeedbackOrder |= !containerFeedback;
                }
            }
            if (requiresCraftableFeedbackOrder) {
                // A general feedback seed made by an acyclic producer still needs ordered validation:
                // aggregate balance may cancel its input against its own later output. A container
                // remainder is the narrow exception because fireLinear withholds one returned batch
                // and the conservative prefix postcondition validates the exact startup marking.
                requiresSeedOrderedPlanning = true;
            }
            indexLinearContainerBootstrapReserves();
            indexSeedOrderedDependencyCone(order);
            this.capacity = capacityFromOrder(order, items.size());
            indexCapacityOrder();
            indexDirectRawConsumables();
            indexEquivalentMaterialFootprints(order);
            ByproductSchedule<K> compiledByproductSchedule = lowWidthByproductSchedule(order);
            preparedGraph = snapshotPreparedGraph(order, items, compiledByproductSchedule);
            diagnostics.recordCompilation(preparedGraph, System.nanoTime() - compileStarted);
        } else {
            order = preparedGraph.order;
            items = preparedGraph.items;
            diagnostics.recordCompilationReuse();
        }

        // Build the same conservative producer-before-consumer schedule for every normalized DAG.
        // Seed-sensitive graphs may use its safe edges for the count model, but only the recursive
        // replay below may accept the result as executable; the aggregate pass alone is not allowed to
        // bootstrap a returned catalyst from its own outputs.
        ByproductSchedule<K> byproductSchedule = preparedGraph.byproductSchedule;
        List<K> replayOrder = byproductSchedule.order();
        activeReplayOrder = replayOrder;

        CraftPlan<K> linearDiagnosis = null;
        Map<CraftPattern<K>, Long> fixedFirings = new IdentityHashMap<>();
        Set<K> fixedItems = new HashSet<>();
        Set<CraftPattern<K>> orderedValidationPatterns =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<K> orderedValidationItems = new HashSet<>();
        Set<CraftPattern<K>> speculativeByproductPatterns =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<K> speculativeByproductItems = new HashSet<>();
        List<SolvedLowWidthComponent<K>> solvedComponents = new ArrayList<>();
        // 1) Linear backbone (v2-memo-deps / v2-lazy-deduct): one topological aggregation pass,
        //    each item resolved exactly once = O(n + E). On a seed-sensitive graph this is a demand
        //    baseline only; accepting it directly could let an output algebraically start its own seed.
        long linearStarted = System.nanoTime();
        CraftPlan<K> linear = linearPass(replayOrder, target, amount);
        diagnostics.addLinearPassNanos(System.nanoTime() - linearStarted);
        if (linear.feasible() && !requiresSeedOrderedPlanning) {
            return enforceCycleBootstrap(linear, target, amount);
        }
        if (!requiresSeedOrderedPlanning || !linear.feasible()) {
            linearDiagnosis = linear;
        }

        LowWidthAnalysis<K> lowWidth = analyzeLowWidthComponents(
                byproductSchedule, target, amount, linear.firings());
        if (lowWidth != null) {
            replayOrder = lowWidth.replayOrder();
        }
        if (!requiresSeedOrderedPlanning
                && lowWidth != null
                && lowWidth.optimisticTargetCapacity() < amount) {
            // Sum-of-routes capacity deliberately double-counts shared stock, so falling below
            // the request is a proof of infeasibility on an ordinary DAG. Stateful startup stays on
            // exact replay because its one-time reserve is intentionally absent from this capacity.
            diagnostics.recordLowWidthCapacityProof();
            return enforceCycleBootstrap(linearDiagnosis, target, amount);
        }

        if (lowWidth != null) {
            for (LowWidthComponent<K> component : lowWidth.components()) {
                if (!component.exactSolverEligible()) {
                    continue;
                }
                diagnostics.recordLowWidthAttempt();
                LowWidthSolve<K> exact = solveLowWidthComponent(component, List.of());
                diagnostics.recordLowWidthResult(exact.status(), exact.integerNodes());
                if (exact.status() == BoundedIntegerLinearSolver.Status.INFEASIBLE) {
                    if (component.infeasibilityProof() && linearDiagnosis != null) {
                        // This proves only the current cut orientation. planDetailed remains responsible
                        // for trying the other admitted conversion orientations before final failure.
                        return enforceCycleBootstrap(linearDiagnosis, target, amount);
                    }
                    continue;
                }
                if (exact.firings() != null) {
                    solvedComponents.add(new SolvedLowWidthComponent<>(component, exact));
                    fixedFirings.putAll(exact.firings());
                    fixedItems.addAll(component.items());
                    if (isPureSpeculativeMaterialComponent(component)) {
                        for (CraftPattern<K> pattern : component.patterns()) {
                            if (pattern.outputAmount() > 1L) {
                                fixedBatchReplayOutputs.add(pattern.output());
                            }
                        }
                    }
                    if (component.requiresOrderedValidation()) {
                        orderedValidationPatterns.addAll(component.patterns());
                        orderedValidationItems.addAll(component.items());
                    }
                    if (component.reusesByproducts()) {
                        speculativeByproductPatterns.addAll(component.patterns());
                        speculativeByproductItems.addAll(component.items());
                    }
                }
            }
        }

        if (!fixedItems.isEmpty()
                && orderedValidationItems.isEmpty()
                && !requiresSeedOrderedPlanning) {
            // Replay the complete request once. Solved components use their exact firing vector;
            // wide/cutoff components keep the ordinary dynamic-capacity allocation. This is the
            // common fast path for a globally wide graph made from independent narrow kernels.
            LinearPassState<K> pinned = linearPassState(
                    replayOrder, target, amount, Map.of(), fixedFirings, fixedItems);
            if (!pinned.fixedAllocationFailed() && pinned.miss().isEmpty()) {
                CraftPlan<K> exactHybrid = new CraftPlan<>(
                        true, true, pinned.fired(), pinned.used(), Map.of(), Map.of(),
                        pinned.gross(), pinned.done(), false);
                return enforceCycleBootstrap(exactHybrid, target, amount);
            }
            if (pinned.fixedAllocationFailed()) {
                // A balance-feasible byproduct vector may deliberately overproduce a primary
                // output solely to obtain its side output. Ordered replay validates that
                // relaxation. Reopen only rejected byproduct components; independent ordinary
                // components remain pinned.
                discardSpeculativeByproductFixes(
                        fixedFirings,
                        fixedItems,
                        speculativeByproductPatterns,
                        speculativeByproductItems);
            }
        }
        if (!requiresSeedOrderedPlanning && orderedValidationItems.isEmpty()) {
            // 1b) Allocation repair: search over the contended outputs' route allocations only,
            //     each candidate evaluated by one O(E) sweep. The sweep is the same aggregation
            //     the linear backbone uses, so this applies exactly where the backbone's feasible
            //     answers are already trusted: catalysts resolve through the shared seed reserve,
            //     fixed conversion rings arrive here already cut to a DAG, and contracted
            //     gain/feedback loops keep their compile-time bookkeeping. Unlike the recursive
            //     search this can SPLIT one product's demand across routes; the cycle-bootstrap
            //     check afterwards may still veto a plan, in which case the recursion runs.
            CraftPlan<K> repaired = allocationRepair(
                    replayOrder, target, amount, fixedFirings, fixedItems);
            if (repaired != null && repaired.feasible()) {
                CraftPlan<K> bootstrapped = enforceCycleBootstrap(repaired, target, amount);
                if (bootstrapped.feasible()) {
                    return bootstrapped;
                }
            }
        }

        if (orderedValidationItems.isEmpty()) {
            // Recursive execution already has exact byproduct ordering. A relaxed aggregate vector
            // that did not pass complete validation must not become a hard recursive quota.
            discardSpeculativeByproductFixes(
                    fixedFirings,
                    fixedItems,
                    speculativeByproductPatterns,
                    speculativeByproductItems);
        }

        // 2) Contended cone only: fall back to the budgeted recursive search (trail + rollback).
        for (K x : items) {
            PlanningCancellation.check();
            stockLeft.put(x, graph.stock(x));
        }
        int recursiveMark = trail.size();
        int processedAtRecursiveMark = processed;
        fixedFiringQuota.putAll(fixedFirings);
        fixedFallbackItems.addAll(fixedItems);
        long searchStarted = System.nanoTime();
        obtain(target, amount, true);
        CraftPlan<K> fallback = snapshotRecursivePlan();

        if (!fallback.feasible()
                && !orderedValidationItems.isEmpty()
                && !searchBudget.exhausted()
                && !diagnostics.resolutionExhausted()) {
            // The integer seed model deliberately uses a cheap host-capacity relaxation. Validate its
            // selected active set with the real ordered acquisition/max-flow machinery. A failed
            // validation becomes a no-good over that component's route-support bits; solve once more
            // before reopening the compatibility fallback. The shared exact-work budget bounds every
            // retry, and independent ordinary components stay pinned.
            resetRecursiveExecution(recursiveMark, processedAtRecursiveMark);
            for (SolvedLowWidthComponent<K> solvedComponent : solvedComponents) {
                LowWidthComponent<K> component = solvedComponent.component();
                LowWidthSolve<K> rejected = solvedComponent.solve();
                if (!component.requiresOrderedValidation()
                        || rejected.decisionSupport().isEmpty()
                        || searchBudget.exhausted()
                        || diagnostics.resolutionExhausted()) {
                    continue;
                }
                diagnostics.recordLowWidthAttempt();
                LowWidthSolve<K> retry = solveLowWidthComponent(
                        component, List.of(rejected.decisionSupport()));
                diagnostics.recordLowWidthResult(retry.status(), retry.integerNodes());
                if (retry.firings() == null) {
                    continue;
                }

                Map<CraftPattern<K>, Long> retriedFirings =
                        new IdentityHashMap<>(fixedFirings);
                for (CraftPattern<K> pattern : component.patterns()) {
                    retriedFirings.remove(pattern);
                }
                retriedFirings.putAll(retry.firings());
                fixedFiringQuota.putAll(retriedFirings);
                fixedFallbackItems.addAll(fixedItems);
                obtain(target, amount, true);
                CraftPlan<K> retried = snapshotRecursivePlan();
                if (retried.feasible()) {
                    diagnostics.addSearchNanos(System.nanoTime() - searchStarted);
                    return enforceCycleBootstrap(retried, target, amount);
                }
                resetRecursiveExecution(recursiveMark, processedAtRecursiveMark);
            }

            // A cutoff, unsupported state detail, or multi-component conflict stays on the existing
            // bounded compatibility path. It does not trigger a whole-plan rerun.
            for (CraftPattern<K> pattern : orderedValidationPatterns) {
                fixedFirings.remove(pattern);
            }
            fixedItems.removeAll(orderedValidationItems);
            fixedFiringQuota.putAll(fixedFirings);
            fixedFallbackItems.addAll(fixedItems);
            obtain(target, amount, true);
            fallback = snapshotRecursivePlan();
        }
        diagnostics.addSearchNanos(System.nanoTime() - searchStarted);
        boolean budgetTruncated = fallback.budgetExhausted();
        // A budget-truncated descent stops mid-graph and reports still-craftable intermediates as
        // missing (e.g. "2 x complex component" instead of the millions of raw units their subtree
        // needs). The aggregate pass has already expanded the same request all the way to raw leaves,
        // so its diagnosis is the actionable one; the plan stays flagged budget-exhausted because
        // alternate routes were still never fully explored.
        if (budgetTruncated && linearDiagnosis != null && hasCraftableMissing(fallback)) {
            CraftPlan<K> diagnosis = new CraftPlan<>(true, false,
                    linearDiagnosis.firings(),
                    linearDiagnosis.usedStock(),
                    linearDiagnosis.usedReusableStock(),
                    linearDiagnosis.missing(),
                    linearDiagnosis.grossDemand(),
                    linearDiagnosis.itemsProcessed(),
                    true);
            return enforceCycleBootstrap(diagnosis, target, amount);
        }
        return enforceCycleBootstrap(fallback, target, amount);
    }

    private void resetRecursiveExecution(int trailMark, int processedAtMark) {
        rollback(trailMark);
        processed = processedAtMark;
        depth = 0;
        fixedFiringQuota.clear();
        fixedFallbackItems.clear();
        failedSpeculativeSearches.clear();
    }

    private CraftPlan<K> snapshotRecursivePlan() {
        boolean feasible = missing.isEmpty();
        boolean budgetTruncated =
                (searchBudget.exhausted() || diagnostics.resolutionExhausted()) && !feasible;
        return new CraftPlan<>(true, feasible,
                new IdentityHashMap<>(firings),
                new HashMap<>(usedStock),
                new HashMap<>(usedReusableStock),
                new HashMap<>(missing),
                new HashMap<>(grossDemand),
                processed,
                budgetTruncated);
    }

    /** True when a missing entry still has a producing pattern, i.e. the diagnosis was cut short. */
    private boolean hasCraftableMissing(CraftPlan<K> plan) {
        for (K key : plan.missing().keySet()) {
            if (!patternsByOutput.getOrDefault(key, List.of()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private PreparedGraph<K> snapshotPreparedGraph(
            List<K> order,
            Set<K> items,
            ByproductSchedule<K> byproductSchedule) {
        Map<K, List<CraftPattern<K>>> frozenPatterns = new HashMap<>();
        patternsByOutput.forEach((key, value) -> frozenPatterns.put(key, List.copyOf(value)));

        IdentityHashMap<CraftPattern<K>, Set<K>> frozenSuppressed = new IdentityHashMap<>();
        suppressedPositiveFeedbackOutputs.forEach(
                (pattern, outputs) -> frozenSuppressed.put(pattern, Set.copyOf(outputs)));
        IdentityHashMap<CraftPattern<K>, Map<K, Long>> frozenContainerReserves =
                new IdentityHashMap<>();
        linearContainerBootstrapReserves.forEach(
                (pattern, reserves) -> frozenContainerReserves.put(pattern, Map.copyOf(reserves)));
        IdentityHashMap<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> frozenBootstraps =
                new IdentityHashMap<>();
        feedbackSeedBootstraps.forEach(
                (pattern, values) -> frozenBootstraps.put(pattern, List.copyOf(values)));
        IdentityHashMap<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> frozenConverters =
                new IdentityHashMap<>();
        feedbackSeedConverters.forEach(
                (pattern, values) -> frozenConverters.put(pattern, List.copyOf(values)));

        IdentityHashMap<CraftPattern<K>, Long> frozenScores = new IdentityHashMap<>();
        frozenScores.putAll(capacityScoreByPattern);
        IdentityHashMap<CraftPattern<K>, Map<K, Long>> frozenRaw = new IdentityHashMap<>();
        frozenRaw.putAll(directRawConsumablesByPattern);
        IdentityHashMap<CraftPattern<K>, Integer> frozenFootprints = new IdentityHashMap<>();
        frozenFootprints.putAll(materialFootprintByPattern);

        int patternCount = 0;
        int inputCount = 0;
        int contended = 0;
        for (List<CraftPattern<K>> patterns : frozenPatterns.values()) {
            patternCount += patterns.size();
            if (patterns.size() > 1) contended++;
            for (CraftPattern<K> pattern : patterns) {
                inputCount += pattern.inputs().size();
            }
        }

        return new PreparedGraph<>(
                graph,
                List.copyOf(order),
                Set.copyOf(items),
                Set.copyOf(cutOutputs),
                frozenPatterns,
                frozenSuppressed,
                frozenContainerReserves,
                frozenBootstraps,
                frozenConverters,
                conservativeFeedbackComponents,
                canonicalFeedbackFallbackComponents,
                requiresSeedOrderedPlanning,
                Set.copyOf(seedOrderedDependencyCone),
                new HashMap<>(capacity),
                frozenScores,
                new HashMap<>(capacityOrderByOutput),
                frozenRaw,
                frozenFootprints,
                byproductSchedule,
                patternCount,
                inputCount,
                contended);
    }

    private void loadPreparedGraph(PreparedGraph<K> prepared) {
        cutOutputs.addAll(prepared.cutOutputs);
        patternsByOutput.putAll(prepared.patternsByOutput);
        suppressedPositiveFeedbackOutputs.putAll(prepared.suppressedPositiveFeedbackOutputs);
        linearContainerBootstrapReserves.putAll(prepared.linearContainerBootstrapReserves);
        feedbackSeedBootstraps.putAll(prepared.feedbackSeedBootstraps);
        feedbackSeedConverters.putAll(prepared.feedbackSeedConverters);
        conservativeFeedbackComponents = prepared.conservativeFeedbackComponents;
        canonicalFeedbackFallbackComponents = prepared.canonicalFeedbackFallbackComponents;
        for (ConservativeFeedbackAnalysis.Component<K> component
                : conservativeFeedbackComponents) {
            if (component.hasExternalProducer()) {
                craftableConservativeFeedbackPatterns.addAll(component.patterns());
            }
        }
        requiresSeedOrderedPlanning = prepared.seedOrdered;
        seedOrderedDependencyCone.addAll(prepared.seedOrderedDependencyCone);
        capacity = prepared.capacity;
        capacityScoreByPattern.putAll(prepared.capacityScoreByPattern);
        capacityOrderByOutput.putAll(prepared.capacityOrderByOutput);
        directRawConsumablesByPattern.putAll(prepared.directRawConsumablesByPattern);
        materialFootprintByPattern.putAll(prepared.materialFootprintByPattern);
    }

    /**
     * A balanced container cycle still needs one physical state token to start. A purely algebraic
     * flow can otherwise schedule {@code full -> empty -> full} with zero initial containers. When a
     * fired consumed-returning input is refilled by another fired pattern, require one batch from
     * inventory unless some fired acyclic producer supplies either state from outside the pair.
     */
    private CraftPlan<K> enforceCycleBootstrap(CraftPlan<K> plan, K target, long amount) {
        Map<K, List<CraftPattern<K>>> firedByOutput = new HashMap<>();
        for (Map.Entry<CraftPattern<K>, Long> entry : plan.firings().entrySet()) {
            if (entry.getValue() > 0) {
                firedByOutput.computeIfAbsent(entry.getKey().output(), ignored -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }

        Map<K, Long> used = new HashMap<>(plan.usedStock());
        Map<K, Long> missing = new HashMap<>(plan.missing());
        Set<Set<K>> handled = new java.util.HashSet<>();

        for (CraftPattern<K> consumer : plan.firings().keySet()) {
            if (plan.firings().getOrDefault(consumer, 0L) <= 0) continue;
            for (CraftInput<K> transition : consumer.inputs()) {
                K remainder = transition.remainder();
                if (remainder == null) continue;
                if (transition.key().equals(remainder)) continue;

                long refillRequirement = Long.MAX_VALUE;
                for (CraftPattern<K> refill : firedByOutput.getOrDefault(transition.key(), List.of())) {
                    for (CraftInput<K> refillInput : refill.inputs()) {
                        if (remainder.equals(refillInput.key())) {
                            refillRequirement = Math.min(refillRequirement, refillInput.amount());
                        }
                    }
                }
                if (refillRequirement == Long.MAX_VALUE) continue;

                Set<K> states = Set.of(transition.key(), remainder);
                if (!handled.add(states)) continue;
                if (used.getOrDefault(transition.key(), 0L) > 0
                        || used.getOrDefault(remainder, 0L) > 0
                        || hasExternalBootstrapProducer(states, firedByOutput)) {
                    continue;
                }

                long required = Math.max(1L, Math.min(transition.amount(), refillRequirement));
                K chosen = graph.stock(transition.key()) >= graph.stock(remainder)
                        ? transition.key() : remainder;
                long extracted = Math.min(required, graph.stock(chosen));
                if (extracted > 0) {
                    used.merge(chosen, extracted, Sat::add);
                }
                if (extracted < required) {
                    missing.merge(chosen, required - extracted, Sat::add);
                }
            }
        }

        Set<K> preciselyScheduledStates = enforceConservativeFeedbackBootstrap(
                plan, target, amount, used, missing);
        enforceCanonicalFeedbackFallback(
                plan, target, amount, used, missing, preciselyScheduledStates);

        return new CraftPlan<>(plan.supported(), missing.isEmpty(), plan.firings(), used,
                plan.usedReusableStock(), missing, plan.grossDemand(), plan.itemsProcessed(),
                plan.budgetExhausted());
    }

    /**
     * Reserves one executable initial marking for every proven non-growing ordinary feedback SCC
     * used by this plan. The aggregate solver may erase the state retained after a balanced or lossy
     * round; this postcondition restores the physical token before the first transition.
     *
     * <p>Seed alternatives are the bounded cyclic rotations computed during graph compilation. A
     * state already drawn (or already reported missing) for net demand can serve as that same token,
     * as can a fired acyclic producer entering the SCC from outside. Components not fully used by the
     * chosen firing vector are left to ordinary accounting and the legacy partial-return check.
     */
    private Set<K> enforceConservativeFeedbackBootstrap(
            CraftPlan<K> plan,
            K target,
            long targetAmount,
            Map<K, Long> used,
            Map<K, Long> missing) {
        Set<K> handledStates = new HashSet<>();
        for (ConservativeFeedbackAnalysis.Component<K> component
                : conservativeFeedbackComponents) {
            boolean active = true;
            for (CraftPattern<K> pattern : component.patterns()) {
                if (plan.firings().getOrDefault(pattern, 0L) <= 0) {
                    active = false;
                    break;
                }
            }
            if (!active) continue;

            List<ConservativeFeedbackAnalysis.ScheduleOption<K>> options =
                    ConservativeFeedbackAnalysis.scheduleOptions(component, plan.firings());
            if (options.isEmpty()) continue;
            Map<K, Long> chosen = chooseFeedbackRequirement(
                    component.states(), component.stateOrder(), component.patterns(), options,
                    plan.firings(), target, targetAmount);
            if (chosen == null) continue;
            handledStates.addAll(component.states());

            // Replace the aggregate net-flow accounting for SCC states with the initial marking of
            // the chosen concrete schedule. This removes intermediary shortages created solely by a
            // cycle cut while retaining every real external sink demand in the formula above.
            replaceFeedbackAccounting(component.stateOrder(), chosen, used, missing);
        }
        return handledStates;
    }

    /**
     * Bounded canonical-prefix fallback for proven non-growing ordinary Petri SCCs. The schedule is
     * reconstructed solely from the graph and actual firing multiset; no search witness or context
     * is carried into a later refill calculation. Complicated nets may receive a non-minimal initial
     * marking, but a missing-free result always has a concrete executable order.
     */
    private void enforceCanonicalFeedbackFallback(
            CraftPlan<K> plan,
            K target,
            long targetAmount,
            Map<K, Long> used,
            Map<K, Long> missing,
            Set<K> preciselyScheduledStates) {
        for (ConservativeFeedbackAnalysis.FallbackComponent<K> component
                : canonicalFeedbackFallbackComponents) {
            if (!java.util.Collections.disjoint(component.states(), preciselyScheduledStates)) {
                continue;
            }
            if (!hasActiveFeedbackCycle(component, plan.firings())) continue;
            List<ConservativeFeedbackAnalysis.ScheduleOption<K>> options =
                    ConservativeFeedbackAnalysis.scheduleOptions(component, plan.firings());
            if (options.isEmpty()) continue;
            Map<K, Long> chosen = chooseFeedbackRequirement(
                    component.states(), component.stateOrder(), component.patterns(), options,
                    plan.firings(), target, targetAmount);
            if (chosen == null) continue;
            replaceFeedbackAccounting(component.stateOrder(), chosen, used, missing);
        }
    }

    private Map<K, Long> chooseFeedbackRequirement(
            Set<K> states,
            List<K> stateOrder,
            List<CraftPattern<K>> componentPatterns,
            List<ConservativeFeedbackAnalysis.ScheduleOption<K>> options,
            Map<CraftPattern<K>, Long> fired,
            K target,
            long targetAmount) {
        Map<K, BigInteger> externalSupply = externalFeedbackSupply(
                states, componentPatterns, fired);
        Map<K, BigInteger> externalDemand = externalFeedbackDemand(
                states, componentPatterns, fired, target, targetAmount);
        Map<K, Integer> distances = shortestInputDistances(graph, target);
        FeedbackRequirement<K> best = null;
        for (ConservativeFeedbackAnalysis.ScheduleOption<K> option : options) {
            Map<K, Long> amounts = new LinkedHashMap<>();
            int firstRank = Integer.MAX_VALUE;
            java.util.NavigableMap<Integer, Long> missingByDistance = new java.util.TreeMap<>();
            java.util.NavigableMap<Integer, Long> usedByDistance = new java.util.TreeMap<>();
            for (int rank = 0; rank < stateOrder.size(); rank++) {
                K key = stateOrder.get(rank);
                BigInteger supply = externalSupply.getOrDefault(key, BigInteger.ZERO);
                BigInteger scheduleNeed = option.required()
                        .getOrDefault(key, BigInteger.ZERO).subtract(supply);
                BigInteger finalNeed = externalDemand.getOrDefault(key, BigInteger.ZERO)
                        .subtract(supply)
                        .subtract(option.delta().getOrDefault(key, BigInteger.ZERO));
                long required = plannerAmount(scheduleNeed.max(finalNeed).max(BigInteger.ZERO));
                if (required > 0L) {
                    amounts.put(key, required);
                    firstRank = Math.min(firstRank, rank);
                }
                long stock = graph.stock(key);
                long shortage = Math.max(0L, required - stock);
                int distance = distances.getOrDefault(key, Integer.MAX_VALUE);
                if (shortage > 0L) {
                    missingByDistance.merge(distance, shortage, Sat::add);
                }
                long stockUsed = Math.min(required, stock);
                if (stockUsed > 0L) {
                    usedByDistance.merge(distance, stockUsed, Sat::add);
                }
            }
            FeedbackRequirement<K> candidate = new FeedbackRequirement<>(
                    amounts,
                    java.util.Collections.unmodifiableNavigableMap(missingByDistance),
                    java.util.Collections.unmodifiableNavigableMap(usedByDistance),
                    firstRank);
            if (candidate.betterThan(best)) best = candidate;
        }
        return best == null ? null : best.amounts();
    }

    private static int compareFeedbackRequirement(
            FeedbackRequirement<?> left, FeedbackRequirement<?> right) {
        int comparison = compareGraded(
                left.missingByDistance(), right.missingByDistance(), false);
        if (comparison != 0) return comparison;
        comparison = compareGraded(left.usedByDistance(), right.usedByDistance(), true);
        if (comparison != 0) return comparison;
        // Rotations of one fixed feedback firing vector have identical execution counts. Prefer
        // the smaller concrete startup marking only after every declared objective tier ties; this
        // also avoids choosing an unnecessarily stateful read-arc rotation.
        comparison = Integer.compare(left.amounts().size(), right.amounts().size());
        if (comparison != 0) return comparison;
        return Integer.compare(left.firstRank(), right.firstRank());
    }

    private void replaceFeedbackAccounting(
            List<K> stateOrder,
            Map<K, Long> requirement,
            Map<K, Long> used,
            Map<K, Long> missing) {
        for (K key : stateOrder) {
            used.remove(key);
            missing.remove(key);
            long required = requirement.getOrDefault(key, 0L);
            long extracted = Math.min(required, graph.stock(key));
            if (extracted > 0L) used.put(key, extracted);
            if (extracted < required) missing.put(key, required - extracted);
        }
    }

    private record FeedbackRequirement<K>(
            Map<K, Long> amounts,
            java.util.NavigableMap<Integer, Long> missingByDistance,
            java.util.NavigableMap<Integer, Long> usedByDistance,
            int firstRank) {
        boolean betterThan(FeedbackRequirement<K> other) {
            if (other == null) return true;
            return compareFeedbackRequirement(this, other) < 0;
        }
    }

    private boolean hasActiveFeedbackCycle(
            ConservativeFeedbackAnalysis.FallbackComponent<K> component,
            Map<CraftPattern<K>, Long> fired) {
        Map<K, LinkedHashSet<K>> edges = new LinkedHashMap<>();
        Map<K, Integer> indegree = new LinkedHashMap<>();
        for (K state : component.stateOrder()) {
            edges.put(state, new LinkedHashSet<>());
            indegree.put(state, 0);
        }
        for (CraftPattern<K> pattern : component.patterns()) {
            if (fired.getOrDefault(pattern, 0L) <= 0L) continue;
            List<K> outputs = new ArrayList<>();
            if (component.states().contains(pattern.output())) outputs.add(pattern.output());
            for (CraftOutput<K> output : pattern.byproducts()) {
                if (component.states().contains(output.key())) outputs.add(output.key());
            }
            for (CraftInput<K> input : pattern.inputs()) {
                if (!component.states().contains(input.key())) continue;
                for (K output : outputs) {
                    if (edges.get(input.key()).add(output)) {
                        indegree.merge(output, 1, Integer::sum);
                    }
                }
            }
        }
        Deque<K> ready = new ArrayDeque<>();
        for (K state : component.stateOrder()) {
            if (indegree.getOrDefault(state, 0) == 0) ready.addLast(state);
        }
        int removed = 0;
        while (!ready.isEmpty()) {
            K state = ready.removeFirst();
            removed++;
            for (K output : edges.getOrDefault(state, new LinkedHashSet<>())) {
                int remaining = indegree.merge(output, -1, Integer::sum);
                if (remaining == 0) ready.addLast(output);
            }
        }
        return removed < component.states().size();
    }

    /** Supply that can be scheduled before the feedback component without consuming its state. */
    private Map<K, BigInteger> externalFeedbackSupply(
            Set<K> states,
            List<CraftPattern<K>> componentPatterns,
            Map<CraftPattern<K>, Long> fired) {
        Map<K, BigInteger> result = new HashMap<>();
        Set<CraftPattern<K>> internal = new HashSet<>(componentPatterns);
        for (Map.Entry<CraftPattern<K>, Long> entry : fired.entrySet()) {
            CraftPattern<K> pattern = entry.getKey();
            long times = entry.getValue();
            if (times <= 0 || internal.contains(pattern)) continue;
            boolean consumesState = pattern.inputs().stream()
                    .anyMatch(input -> states.contains(input.key()));
            if (consumesState) continue;

            if (states.contains(pattern.output())) {
                mergeProduct(result, pattern.output(), pattern.outputAmount(), times);
            }
            for (CraftOutput<K> output : pattern.byproducts()) {
                if (states.contains(output.key())) {
                    mergeProduct(result, output.key(), output.amount(), times);
                }
            }
        }
        return result;
    }

    /** Demand scheduled after the feedback component, including the top-level requested state. */
    private Map<K, BigInteger> externalFeedbackDemand(
            Set<K> states,
            List<CraftPattern<K>> componentPatterns,
            Map<CraftPattern<K>, Long> fired,
            K target,
            long targetAmount) {
        Map<K, BigInteger> result = new HashMap<>();
        Set<CraftPattern<K>> internal = new HashSet<>(componentPatterns);
        if (states.contains(target) && targetAmount > 0L) {
            result.put(target, BigInteger.valueOf(targetAmount));
        }
        for (Map.Entry<CraftPattern<K>, Long> entry : fired.entrySet()) {
            CraftPattern<K> pattern = entry.getKey();
            long times = entry.getValue();
            if (times <= 0L || internal.contains(pattern)) continue;
            for (CraftInput<K> input : pattern.inputs()) {
                if (!states.contains(input.key())) continue;
                result.merge(input.key(), BigInteger.valueOf(input.unitsFor(times)), BigInteger::add);
            }
        }
        return result;
    }

    private static <K> void mergeProduct(
            Map<K, BigInteger> values, K key, long amount, long times) {
        values.merge(key,
                BigInteger.valueOf(amount).multiply(BigInteger.valueOf(times)),
                BigInteger::add);
    }

    private static long plannerAmount(BigInteger amount) {
        return amount.compareTo(BigInteger.valueOf(Sat.SAT)) >= 0
                ? Sat.SAT
                : amount.longValueExact();
    }

    private static long gcd(long a, long b) {
        a = Math.max(1L, a);
        b = Math.max(1L, b);
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    /**
     * Finds direct byproduct feedback whose material state grows after one balanced round, for example
     * {@code A -> B + C; C -> 2 A}. Such a loop is intentionally not a capability of the ordinary
     * planner: it must first be compiled into one closed-loop macro pattern. We therefore keep the raw
     * member recipes visible, but do not let the growing feedback byproduct enter the shared pool.
     * Existing stock of the returned state remains usable, so finite non-feedback crafts still work.
     *
     * <p>This is a linear, deliberately local guard matching the local feedback optimization below;
     * arbitrary SCC coefficient solving remains exclusively in the closed-loop analyzer.
     */
    private void identifyPositiveFeedbackByproducts(K target) {
        Set<K> seen = new LinkedHashSet<>();
        Deque<K> queue = new ArrayDeque<>();
        seen.add(target);
        queue.add(target);

        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            K output = queue.remove();
            for (CraftPattern<K> consumer : graph.patternsFor(output)) {
                for (CraftInput<K> input : consumer.inputs()) {
                    if (seen.add(input.key())) queue.add(input.key());
                }
                for (CraftOutput<K> byproduct : consumer.byproducts()) {
                    if (seen.add(byproduct.key())) queue.add(byproduct.key());
                }

                Set<K> ordinaryInputs = new LinkedHashSet<>();
                for (CraftInput<K> input : consumer.inputs()) {
                    if (!input.returned() && input.remainder() == null) {
                        ordinaryInputs.add(input.key());
                    }
                }
                for (K consumedKey : ordinaryInputs) {
                    long consumedAmount = ordinaryInputAmount(consumer, consumedKey);
                    if (consumedAmount <= 0) continue;
                    for (CraftOutput<K> byproduct : consumer.byproducts()) {
                        long returnedAmount = byproductAmount(consumer, byproduct.key());
                        if (returnedAmount <= 0) continue;
                        for (CraftPattern<K> refill : graph.patternsFor(consumedKey)) {
                            long refillInput = ordinaryInputAmount(refill, byproduct.key());
                            if (refillInput <= 0) continue;
                            long common = gcd(returnedAmount, refillInput);
                            long consumerBatch = refillInput / common;
                            long refillBatch = returnedAmount / common;
                            long consumedPerRound = Sat.mul(consumedAmount, consumerBatch);
                            long recoveredPerRound = Sat.mul(refill.outputAmount(), refillBatch);
                            if (recoveredPerRound > consumedPerRound) {
                                suppressedPositiveFeedbackOutputs
                                        .computeIfAbsent(consumer, ignored -> new LinkedHashSet<>())
                                        .add(byproduct.key());
                            }
                        }
                    }
                }
            }
        }
    }

    private static <K> long ordinaryInputAmount(CraftPattern<K> pattern, K key) {
        long result = 0L;
        for (CraftInput<K> input : pattern.inputs()) {
            if (!input.returned() && input.remainder() == null && key.equals(input.key())) {
                result = Sat.add(result, input.amount());
            }
        }
        return result;
    }

    private static <K> long byproductAmount(CraftPattern<K> pattern, K key) {
        long result = 0L;
        for (CraftOutput<K> output : pattern.byproducts()) {
            if (key.equals(output.key())) result = Sat.add(result, output.amount());
        }
        return result;
    }

    /**
     * The linear pass aggregates all byproducts before resolving their consumers. For a path such as
     * {@code full -> intermediate -> empty}, crediting every returned {@code empty} lets the path
     * bootstrap itself from zero physical containers. Withhold one returned batch so ordinary demand
     * obtains that bootstrap from stock or a real producer; the remaining returns still serve the
     * rest of the batch.
     */
    private void indexLinearContainerBootstrapReserves() {
        linearContainerBootstrapReserves.clear();
        for (List<CraftPattern<K>> patterns : patternsByOutput.values()) {
            PlanningCancellation.check();
            for (CraftPattern<K> consumer : patterns) {
                Map<K, Long> reserves = new HashMap<>();
                for (CraftInput<K> transition : consumer.inputs()) {
                    K remainder = transition.remainder();
                    if (remainder == null || transition.key().equals(remainder)) continue;
                    long returned = byproductAmount(consumer, remainder);
                    if (returned <= 0 || !dependsOn(transition.key(), remainder)) continue;
                    reserves.merge(remainder, transition.amount(), Sat::add);
                }
                reserves.replaceAll((key, required) ->
                        Math.min(required, byproductAmount(consumer, key)));
                reserves.values().removeIf(amount -> amount <= 0);
                if (!reserves.isEmpty()) {
                    linearContainerBootstrapReserves.put(consumer, Map.copyOf(reserves));
                }
            }
        }
    }

    private boolean dependsOn(K output, K requiredInput) {
        Set<K> seen = new HashSet<>();
        Deque<K> queue = new ArrayDeque<>();
        seen.add(output);
        queue.add(output);
        while (!queue.isEmpty()) {
            PlanningCancellation.check();
            K current = queue.removeFirst();
            for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(current, List.of())) {
                for (CraftInput<K> input : pattern.inputs()) {
                    if (requiredInput.equals(input.key())) return true;
                    if (seen.add(input.key())) queue.addLast(input.key());
                }
            }
        }
        return false;
    }

    private boolean mayReuseByproduct(CraftPattern<K> pattern, K key) {
        return !suppressedPositiveFeedbackOutputs
                .getOrDefault(pattern, Set.of())
                .contains(key);
    }

    private boolean hasExternalBootstrapProducer(
            Set<K> states,
            Map<K, List<CraftPattern<K>>> firedByOutput) {
        for (K state : states) {
            for (CraftPattern<K> producer : firedByOutput.getOrDefault(state, List.of())) {
                boolean consumesCycleState = producer.inputs().stream()
                        .anyMatch(input -> states.contains(input.key()));
                if (!consumesCycleState) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- graph construction ------------------------------------------------

    private static final int GRAY = 1; // on the current DFS path (an ancestor)
    private static final int BLACK = 2; // fully expanded

    private static final class Frame<K> {
        final K node;
        final List<K> children;
        int i;

        Frame(K node, List<K> children) {
            this.node = node;
            this.children = children;
        }
    }

    private static final class SccFrame<K> {
        final K node;
        final List<K> neighbors;
        int next;

        private SccFrame(K node, List<K> neighbors) {
            this.node = node;
            this.neighbors = neighbors;
        }
    }

    private static final class FixedPatternAllocation<K> {
        private final CraftPattern<K> pattern;
        private final long times;
        private final long produced;
        private final long minimumOwn;
        private long allocated;

        private FixedPatternAllocation(
                CraftPattern<K> pattern, long times, long produced, long minimumOwn) {
            this.pattern = pattern;
            this.times = times;
            this.produced = produced;
            this.minimumOwn = minimumOwn;
        }

        private CraftPattern<K> pattern() {
            return pattern;
        }

        private long times() {
            return times;
        }

        private long produced() {
            return produced;
        }

        private long minimumOwn() {
            return minimumOwn;
        }
    }

    /**
     * Iterative DFS from {@code target} that records each node's <em>acyclic</em> recipe set into
     * {@link #patternsByOutput} and emits a post-order. A recipe is kept only if none of its inputs is an
     * ancestor currently on the DFS path ({@code GRAY}); such a recipe would close a cycle ("去头尾"), so
     * it is dropped and the input is satisfied from stock/another recipe instead. Each node and edge is
     * touched once → {@code O(n + E)}; iterative (not recursive) so deep graphs can't overflow the stack.
     */
    private void buildDag(
            K target,
            List<K> priorityRoots,
            List<K> postOrderOut,
            Set<K> itemsOut) {
        Map<K, Integer> color = new HashMap<>();
        for (K priorityRoot : priorityRoots) {
            buildDagRoot(priorityRoot, color, postOrderOut, itemsOut);
        }
        buildDagRoot(target, color, postOrderOut, itemsOut);
    }

    private void buildDagRoot(
            K root,
            Map<K, Integer> color,
            List<K> postOrderOut,
            Set<K> itemsOut) {
        if (color.containsKey(root)) return;
        Deque<Frame<K>> stack = new ArrayDeque<>();
        color.put(root, GRAY);
        itemsOut.add(root);
        stack.push(frameFor(root, color, itemsOut));
        while (!stack.isEmpty()) {
            PlanningCancellation.check();
            Frame<K> f = stack.peek();
            if (f.i < f.children.size()) {
                K c = f.children.get(f.i++);
                if (color.get(c) == null) { // WHITE -> descend (GRAY children are excluded by frameFor)
                    color.put(c, GRAY);
                    stack.push(frameFor(c, color, itemsOut));
                }
            } else {
                color.put(f.node, BLACK);
                postOrderOut.add(f.node);
                stack.pop();
            }
        }
    }

    private Frame<K> frameFor(K x, Map<K, Integer> color, Set<K> itemsOut) {
        List<CraftPattern<K>> all = graph.patternsFor(x);
        List<CraftPattern<K>> usable = new ArrayList<>(all.size());
        Set<K> children = new LinkedHashSet<>();
        for (CraftPattern<K> p : all) {
            for (CraftOutput<K> byproduct : p.byproducts()) {
                reachableByproductKeys.add(byproduct.key());
            }
            List<CraftInput<K>> backEdges = new ArrayList<>(1);
            for (CraftInput<K> in : p.inputs()) {
                if (in.returned() && in.uses() == CraftInput.INFINITE_USES) {
                    if (isSelfReturnedSeed(p, in) || in.reusableStockSource() != null) {
                        requiresSeedOrderedPlanning = true;
                    } else {
                        ordinaryReturnedSeedKeys.add(in.key());
                    }
                }
                if (isSelfReturnedSeed(p, in) || isHostBackedReusableSeed(in)) continue;
                Integer col = color.get(in.key());
                if (col != null && col == GRAY) { // input is an ancestor being made -> back-edge, cut it
                    backEdges.add(in);
                }
            }

            List<FeedbackSeedBootstrap<K>> resolvedBackEdges =
                    new ArrayList<>(backEdges.size());
            int resolvedBackEdgeCount = 0;
            for (CraftInput<K> backEdge : backEdges) {
                List<FeedbackSeedBootstrap<K>> direct =
                        directFeedbackSeedBootstraps(p, backEdge);
                if (!direct.isEmpty()) {
                    resolvedBackEdges.addAll(direct);
                    resolvedBackEdgeCount++;
                    continue;
                }
                FeedbackSeedBootstrap<K> bootstrap =
                        feedbackSeedBootstrapFromConverter(p, backEdge);
                if (bootstrap == null) {
                    break;
                }
                resolvedBackEdges.add(bootstrap);
                resolvedBackEdgeCount++;
            }
            if (resolvedBackEdgeCount != backEdges.size()) {
                cutOutputs.add(x);
                continue;
            }
            for (FeedbackSeedBootstrap<K> bootstrap : resolvedBackEdges) {
                addFeedbackSeedBootstrap(bootstrap);
            }
            usable.add(p);
            for (CraftInput<K> in : p.inputs()) {
                if (isSelfReturnedSeed(p, in)
                        || isHostBackedReusableSeed(in)
                        || isFeedbackSeed(p, in)
                        || isFeedbackConverterInput(p, in)) {
                    continue;
                }
                children.add(in.key());
                itemsOut.add(in.key());
            }
        }
        patternsByOutput.put(x, usable);
        return new Frame<>(x, new ArrayList<>(children));
    }

    /**
     * Marks only the consumer-side cone above seed-order-sensitive patterns. The topological order is
     * target first, so scanning it backwards lets the marker flow from an input to every output that
     * may resolve it. Dependency cones below a sensitive pattern are deliberately left unmarked and
     * can still use {@link #obtainAggregate}.
     */
    private void indexSeedOrderedDependencyCone(List<K> order) {
        if (!requiresSeedOrderedPlanning) {
            return;
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K output = order.get(i);
            boolean affected = directlyRequiresSeedOrder(output);
            if (!affected) {
                outer:
                for (CraftPattern<K> pattern
                        : patternsByOutput.getOrDefault(output, List.of())) {
                    for (CraftInput<K> input : pattern.inputs()) {
                        if (seedOrderedDependencyCone.contains(input.key())) {
                            affected = true;
                            break outer;
                        }
                    }
                }
            }
            if (affected) {
                seedOrderedDependencyCone.add(output);
            }
        }
    }

    /** The exact local conditions that raise the graph-wide seed-order safety gate. */
    private boolean directlyRequiresSeedOrder(K output) {
        for (CraftPattern<K> pattern
                : patternsByOutput.getOrDefault(output, List.of())) {
            if (craftableConservativeFeedbackPatterns.contains(pattern)) return true;
            for (CraftInput<K> input : pattern.inputs()) {
                if (!input.returned() || input.uses() != CraftInput.INFINITE_USES) {
                    continue;
                }
                if (isSelfReturnedSeed(pattern, input)
                        || input.reusableStockSource() != null
                        || reachableByproductKeys.contains(input.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A complete private-host seed is already an acyclic startup source. It must not be treated as a
     * graph edge back to an ancestor merely because the same state is also craftable downstream.
     */
    private boolean isHostBackedReusableSeed(CraftInput<K> input) {
        return input.returned()
                && input.uses() == CraftInput.INFINITE_USES
                && input.reusableStockSource() != null
                && graph.reusableStock(input.reusableStockSource(), input.key()) >= input.amount();
    }

    private void addFeedbackSeedBootstrap(FeedbackSeedBootstrap<K> bootstrap) {
        List<FeedbackSeedBootstrap<K>> seeds = feedbackSeedBootstraps.computeIfAbsent(
                bootstrap.loopPattern(), ignored -> new ArrayList<>());
        if (!seeds.contains(bootstrap)) {
            seeds.add(bootstrap);
        }
        List<FeedbackSeedBootstrap<K>> converters = feedbackSeedConverters.computeIfAbsent(
                bootstrap.converter(), ignored -> new ArrayList<>());
        if (!converters.contains(bootstrap)) {
            converters.add(bootstrap);
        }
    }

    /**
     * Proves the only feedback shape handled inside the ordinary planner: a contracted loop pattern
     * with reusable seed {@code B} produces {@code A}, while an already-kept ordinary pattern converts
     * {@code A} back into {@code B}. This does not admit a general SCC; it merely records the
     * executable one-step bootstrap that the target-first DAG cut would otherwise hide.
     */
    private List<FeedbackSeedBootstrap<K>> directFeedbackSeedBootstraps(
            CraftPattern<K> loopPattern, CraftInput<K> seedInput) {
        if (!seedInput.returned()
                || seedInput.uses() != CraftInput.INFINITE_USES
                || seedInput.reusableStockSource() == null) {
            return List.of();
        }

        List<FeedbackSeedBootstrap<K>> result = new ArrayList<>();
        for (CraftPattern<K> candidate
                : patternsByOutput.getOrDefault(seedInput.key(), List.of())) {
            CraftInput<K> input = feedbackConverterInput(
                    candidate, loopPattern.output(), seedInput.key());
            if (input == null) continue;
            result.add(new FeedbackSeedBootstrap<>(
                    loopPattern, seedInput, candidate, input));
        }
        return result;
    }

    /**
     * Recognizes the same proof when DFS encounters the ordinary {@code A -> B} converter as the
     * back-edge. This is the orientation used by a downstream request such as
     * {@code charged A -> A -> loop(B seed, net A)}.
     */
    private FeedbackSeedBootstrap<K> feedbackSeedBootstrapFromConverter(
            CraftPattern<K> converter, CraftInput<K> converterInput) {
        for (CraftPattern<K> loopPattern
                : patternsByOutput.getOrDefault(converterInput.key(), List.of())) {
            for (CraftInput<K> seedInput : loopPattern.inputs()) {
                if (seedInput.key().equals(converter.output())
                        && seedInput.returned()
                        && seedInput.uses() == CraftInput.INFINITE_USES
                        && seedInput.reusableStockSource() != null
                        && feedbackConverterInput(
                                converter, loopPattern.output(), seedInput.key())
                                == converterInput) {
                    return new FeedbackSeedBootstrap<>(
                            loopPattern, seedInput, converter, converterInput);
                }
            }
        }
        return null;
    }

    /**
     * The single loop-state input of a candidate bootstrap converter, or null when the candidate
     * cannot serve as one. Exactly one ordinary input consumes the loop's output state; every other
     * input must be an ordinary material from outside the cycle (e.g. the reaction chamber's water),
     * obtained normally when the bootstrap fires. Byproducts stay excluded so the converter's only
     * effect on the cycle is the state conversion itself.
     */
    private CraftInput<K> feedbackConverterInput(
            CraftPattern<K> candidate, K loopOutput, K seedKey) {
        if (!candidate.byproducts().isEmpty()) return null;
        CraftInput<K> loopInput = null;
        for (CraftInput<K> input : candidate.inputs()) {
            if (input.returned() || input.remainder() != null
                    || input.reusableStockSource() != null) {
                return null;
            }
            if (input.key().equals(loopOutput)) {
                if (loopInput != null) return null;
                loopInput = input;
            } else if (input.key().equals(seedKey)) {
                return null;
            }
        }
        return loopInput;
    }

    /** cap[X] = stock + max recipe-producible (reverse-topo, byproducts ignored = optimistic upper bound). */
    private Map<K, Long> capacityFromOrder(List<K> order, int sizeHint) {
        Map<K, Long> cap = new HashMap<>(sizeHint * 2);
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K x = order.get(i);
            long best = 0;
            for (CraftPattern<K> p : patternsByOutput.getOrDefault(x, List.of())) {
                best = Math.max(best, producibleVia(p, cap));
                if (Sat.isSaturated(best)) {
                    break;
                }
            }
            cap.put(x, Sat.add(graph.stock(x), best));
        }
        return cap;
    }

    /**
     * Gives simple deterministic recipe trees a canonical material-transformation id. Intermediate
     * item names disappear from the shape, so {@code E→C→A1} and {@code E→D→A2} receive the same id,
     * while exact batch sizes and branching structure remain part of it.
     *
     * <p>This is deliberately a proof, not a heuristic. A craftable intermediate with direct stock or
     * possible dynamic pool credit keeps its own identity, and patterns with returned inputs,
     * remainders, reusable hosts or byproducts are left unclassified. Those routes still search
     * normally; only a pair with identical classified ids may skip a repeated failed expansion.
     */
    private void indexEquivalentMaterialFootprints(List<K> order) {
        Set<K> dynamicPoolKeys = new HashSet<>();
        for (Map.Entry<K, List<CraftPattern<K>>> entry : patternsByOutput.entrySet()) {
            PlanningCancellation.check();
            for (CraftPattern<K> pattern : entry.getValue()) {
                if (pattern.outputAmount() > 1) {
                    dynamicPoolKeys.add(pattern.output());
                }
                for (CraftOutput<K> output : pattern.byproducts()) {
                    dynamicPoolKeys.add(output.key());
                }
                for (CraftInput<K> input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null
                            || input.reusableStockSource() != null) {
                        dynamicPoolKeys.add(input.key());
                        if (input.remainder() != null) {
                            dynamicPoolKeys.add(input.remainder());
                        }
                    }
                }
            }
        }

        FootprintInterner interner = new FootprintInterner();
        Map<K, Integer> footprintByKey = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            PlanningCancellation.check();
            K key = order.get(i);
            List<CraftPattern<K>> patterns = patternsByOutput.getOrDefault(key, List.of());
            if (patterns.isEmpty()) {
                footprintByKey.put(key, interner.intern(new MaterialLeaf(key)));
                continue;
            }

            Integer common = null;
            boolean allEquivalent = true;
            for (CraftPattern<K> pattern : patterns) {
                Integer footprint = materialFootprint(pattern, footprintByKey, interner);
                if (footprint != null) {
                    materialFootprintByPattern.put(pattern, footprint);
                }
                if (footprint == null) {
                    allEquivalent = false;
                } else if (common == null) {
                    common = footprint;
                } else if (!common.equals(footprint)) {
                    allEquivalent = false;
                }
            }

            // Direct stock and dynamic surplus/byproduct credit belong to this concrete intermediate,
            // not merely to its production tree. Keep its identity when it is used by a parent.
            if (graph.stock(key) > 0 || dynamicPoolKeys.contains(key)
                    || !allEquivalent || common == null) {
                footprintByKey.put(key, interner.intern(new MaterialLeaf(key)));
            } else {
                footprintByKey.put(key, common);
            }
        }
    }

    private Integer materialFootprint(
            CraftPattern<K> pattern,
            Map<K, Integer> footprintByKey,
            FootprintInterner interner) {
        if (!pattern.byproducts().isEmpty()) {
            return null;
        }

        Map<Integer, Long> amounts = new HashMap<>();
        for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() || input.remainder() != null
                    || input.reusableStockSource() != null) {
                return null;
            }
            Integer inputFootprint = footprintByKey.get(input.key());
            if (inputFootprint == null) {
                return null;
            }
            long previous = amounts.getOrDefault(inputFootprint, 0L);
            if (Long.MAX_VALUE - previous < input.amount()) {
                return null; // exact proof only; never merge two different saturated totals
            }
            amounts.put(inputFootprint, previous + input.amount());
        }

        List<MaterialTerm> terms = new ArrayList<>(amounts.size());
        for (Map.Entry<Integer, Long> entry : amounts.entrySet()) {
            terms.add(new MaterialTerm(entry.getKey(), entry.getValue()));
        }
        terms.sort((left, right) -> Integer.compare(left.footprint(), right.footprint()));
        return interner.intern(new MaterialRecipe(pattern.outputAmount(), List.copyOf(terms)));
    }

    private long producibleVia(CraftPattern<K> p, Map<K, Long> cap) {
        return producibleVia(p, cap, null);
    }

    private long producibleVia(
            CraftPattern<K> p,
            Map<K, Long> cap,
            Map<CraftPattern<K>, Long> memo) {
        if (memo != null) {
            Long cached = memo.get(p);
            if (cached != null) {
                return cached;
            }
        }
        long bound = Sat.SAT;
        boolean feedbackSeedsBootstrappable = canBootstrapAllFeedbackSeeds(p, cap);
        for (CraftInput<K> in : p.inputs()) {
            long c;
            if (in.reusableStockSource() != null) {
                c = Sat.add(
                        graph.reusableStock(in.reusableStockSource(), in.key()),
                        cap.getOrDefault(in.key(), 0L));
                if (feedbackSeedsBootstrappable
                        && feedbackSeedBootstrap(p, in, cap) != null) {
                    // One physical output-state token can be converted into the reusable seed. Once
                    // that seed exists it supports every firing, exactly like a host-owned catalyst.
                    c = Math.max(c, in.amount());
                }
            } else if (isSelfReturnedSeed(p, in)) {
                c = graph.stock(in.key());
                for (CraftPattern<K> alternative : patternsByOutput.getOrDefault(in.key(), List.of())) {
                    if (alternative == p || hasSelfReturnedSeed(alternative)) continue;
                    c = Sat.add(c, producibleVia(alternative, cap, memo));
                    if (c >= in.amount()) break;
                }
            } else {
                c = cap.getOrDefault(in.key(), 0L);
            }
            bound = Math.min(bound, in.firingsFrom(c)); // finite-use tools bound by uses·units
            if (bound == 0) {
                if (memo != null) {
                    memo.put(p, 0L);
                }
                return 0;
            }
        }
        long result = Sat.mul(bound, p.outputAmount());
        if (memo != null) {
            memo.put(p, result);
        }
        return result;
    }

    private void indexCapacityOrder() {
        capacityScoreByPattern.clear();
        capacityOrderByOutput.clear();
        for (Map.Entry<K, List<CraftPattern<K>>> entry : patternsByOutput.entrySet()) {
            PlanningCancellation.check();
            List<CraftPattern<K>> ordered = new ArrayList<>(entry.getValue());
            for (CraftPattern<K> pattern : ordered) {
                capacityScore(pattern);
            }
            ordered = groupedCapacityOrder(
                    ordered, this::capacityScore, this::preexistingStockCapacity);
            capacityOrderByOutput.put(entry.getKey(), List.copyOf(ordered));
        }
    }

    private long capacityScore(CraftPattern<K> pattern) {
        Long cached = capacityScoreByPattern.get(pattern);
        if (cached != null) {
            return cached;
        }
        return producibleVia(pattern, capacity, capacityScoreByPattern);
    }

    /** Capacity supplied directly by the immutable inventory snapshot. */
    private long preexistingStockCapacity(CraftPattern<K> pattern) {
        long bound = Sat.SAT;
        for (CraftInput<K> input : pattern.inputs()) {
            bound = Math.min(bound, input.firingsFrom(graph.stock(input.key())));
            if (bound == 0) return 0L;
        }
        return Sat.mul(bound, pattern.outputAmount());
    }

    private void indexDirectRawConsumables() {
        directRawConsumablesByPattern.clear();
        for (List<CraftPattern<K>> patterns : patternsByOutput.values()) {
            PlanningCancellation.check();
            for (CraftPattern<K> pattern : patterns) {
                Map<K, Long> perFiring = new HashMap<>();
                for (CraftInput<K> input : pattern.inputs()) {
                    if (input.returned() || input.reusableStockSource() != null
                            || !patternsByOutput.getOrDefault(input.key(), List.of()).isEmpty()) {
                        continue;
                    }
                    perFiring.merge(input.key(), input.amount(), Sat::add);
                }
                if (!perFiring.isEmpty()) {
                    directRawConsumablesByPattern.put(pattern, Map.copyOf(perFiring));
                }
            }
        }
    }

    private List<CraftPattern<K>> capacityOrder(K key) {
        return capacityOrderByOutput.getOrDefault(
                key, patternsByOutput.getOrDefault(key, List.of()));
    }

    private boolean canBootstrapAllFeedbackSeeds(
            CraftPattern<K> pattern, Map<K, Long> materialCapacity) {
        List<FeedbackSeedBootstrap<K>> bootstraps = feedbackSeedBootstraps.get(pattern);
        if (bootstraps == null || bootstraps.isEmpty()) return false;
        long requiredOutput = 0L;
        long availableOutput = graph.stock(pattern.output());
        Set<Object> countedStorageScopes = new HashSet<>();
        boolean foundSeed = false;
        for (CraftInput<K> seed : pattern.inputs()) {
            FeedbackSeedBootstrap<K> bootstrap =
                    feedbackSeedBootstrap(pattern, seed, materialCapacity);
            if (bootstrap == null) continue;
            foundSeed = true;
            long hostAvailable = graph.reusableStock(seed.reusableStockSource(), seed.key());
            long seedShortfall = Math.max(0L, seed.amount() - hostAvailable);
            if (feedbackBootstrapSeedCapacity(bootstrap, materialCapacity) < seedShortfall) {
                return false;
            }
            requiredOutput = Sat.add(
                    requiredOutput, bootstrap.outputUnitsFor(seedShortfall));
            Object storageScope = seed.reusableStockSource().storageScope();
            if (countedStorageScopes.add(storageScope)) {
                availableOutput = Sat.add(
                        availableOutput, graph.reusableStock(storageScope, pattern.output()));
            }
        }
        return foundSeed && availableOutput >= requiredOutput;
    }

    // ---- linear backbone: one topological aggregation pass, each item resolved once -------------

    /**
     * Resolves the whole request in a single topological pass (target → leaves). Each item is visited
     * once, its full demand already aggregated, then split across recipes by current remaining
     * capacity. Capacity is reserved with O(1) deduction ({@code need} doubles as the reservation
     * counter), so there is no recompute loop — this is the {@code O(n + E)} clean backbone. If it
     * comes back feasible the plan is exact and contention never bound; otherwise the caller escalates
     * to the bounded search on the contended cone.
     */
    private CraftPlan<K> linearPass(List<K> order, K target, long amount) {
        LinearPassState<K> pass = linearPassState(order, target, amount, Map.of());
        boolean feasible = pass.miss().isEmpty();
        return new CraftPlan<>(true, feasible, pass.fired(), pass.used(), Map.of(), pass.miss(),
                pass.gross(), pass.done(), false);
    }

    /** Everything one aggregation sweep learned; the allocation-repair loop reads it back. */
    private record LinearPassState<K>(
            Map<K, Long> need,
            Map<CraftPattern<K>, Long> fired,
            Map<K, Long> used,
            Map<K, Long> miss,
            Map<K, Long> gross,
            Map<CraftPattern<K>, Long> allocatedUnits,
            int done,
            boolean fixedAllocationFailed) {
    }

    /**
     * Anytime allocation search whose decision variables are ONLY the contended outputs' route
     * allocations. Each iteration is one O(E) aggregation sweep under per-route unit caps; when the
     * sweep is infeasible, the largest raw shortfall is attributed backward through the realized
     * demand flow to the contended route that pulled most of it, that route's cap is tightened by
     * the attributed amount, and the freed demand re-splits across sibling routes on the next sweep.
     * This finds mixed allocations (partially route A, remainder route B) that the all-or-nothing
     * recursive search cannot express, and its cost scales with the number of contended outputs
     * rather than with root-to-leaf path counts. Returns {@code null} when repair gave up — the
     * recursive search then runs unchanged.
     */
    private CraftPlan<K> allocationRepair(
            List<K> order,
            K target,
            long amount,
            Map<CraftPattern<K>, Long> fixedFirings,
            Set<K> fixedItems) {
        int contended = 0;
        for (K output : order) {
            if (!fixedItems.contains(output) && isContendedOutput(output)) {
                contended++;
            }
        }
        if (contended == 0) {
            return null; // nothing to reallocate; the plain pass already had the only answer
        }
        int maxIterations = (int) Math.min(64L, 4L + 2L * contended);
        Map<CraftPattern<K>, Long> caps = new IdentityHashMap<>();
        Set<CraftPattern<K>> ineffective =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < maxIterations; i++) {
            PlanningCancellation.check();
            if (!searchBudget.tryConsume()) {
                return null;
            }
            diagnostics.recordDynamicCapacityEvaluation();
            LinearPassState<K> pass = linearPassState(
                    order, target, amount, caps, fixedFirings, fixedItems);
            if (pass.fixedAllocationFailed()) {
                return null;
            }
            if (pass.miss().isEmpty()) {
                return new CraftPlan<>(true, true, pass.fired(), pass.used(), Map.of(), Map.of(),
                        pass.gross(), pass.done(), false);
            }
            if (!tightenBlamedRoute(pass, caps, ineffective, fixedItems)) {
                return null;
            }
        }
        return null;
    }

    /**
     * Attributes the largest leaf shortfall backward through this sweep's realized demand flow and
     * tightens the cap of the contended route that pulled the biggest share of it. Blame fractions
     * are heuristic step sizes only — every resulting allocation is re-validated by the next full
     * sweep, so precision here affects convergence speed, never correctness.
     *
     * @return false when no cap can make further progress
     */
    private boolean tightenBlamedRoute(
            LinearPassState<K> pass,
            Map<CraftPattern<K>, Long> caps,
            Set<CraftPattern<K>> ineffective,
            Set<K> fixedItems) {
        K shortLeaf = null;
        long shortfall = 0L;
        for (Map.Entry<K, Long> entry : pass.miss().entrySet()) {
            if (entry.getValue() > shortfall) {
                shortLeaf = entry.getKey();
                shortfall = entry.getValue();
            }
        }
        if (shortLeaf == null) {
            return false;
        }

        // consumers[i] = the fired patterns that demanded item i this sweep, with their unit draw.
        Map<K, List<Map.Entry<CraftPattern<K>, Long>>> consumers = new HashMap<>();
        for (Map.Entry<CraftPattern<K>, Long> firing : pass.fired().entrySet()) {
            CraftPattern<K> r = firing.getKey();
            for (CraftInput<K> in : r.inputs()) {
                long units = in.unitsFor(firing.getValue());
                if (units > 0) {
                    consumers.computeIfAbsent(in.key(), ignored -> new ArrayList<>())
                            .add(Map.entry(r, units));
                }
            }
        }

        // Backward blame propagation, leaves toward the target (reverse topological order).
        Map<K, Double> blameByItem = new HashMap<>();
        blameByItem.put(shortLeaf, (double) shortfall);
        CraftPattern<K> blamed = null;
        double blamedShare = 0.0;
        List<K> order = activeReplayOrder.isEmpty()
                ? preparedGraph.order
                : activeReplayOrder;
        for (int i = order.size() - 1; i >= 0; i--) {
            K item = order.get(i);
            Double blame = blameByItem.get(item);
            if (blame == null || blame <= 0.0) {
                continue;
            }
            long demand = pass.need().getOrDefault(item, 0L);
            if (demand <= 0) {
                continue;
            }
            for (Map.Entry<CraftPattern<K>, Long> consumer
                    : consumers.getOrDefault(item, List.of())) {
                CraftPattern<K> r = consumer.getKey();
                double share = blame * consumer.getValue() / (double) demand;
                if (share <= 0.0) {
                    continue;
                }
                if (!fixedItems.contains(r.output())
                        && isContendedOutput(r.output())
                        && !ineffective.contains(r)
                        && share > blamedShare) {
                    blamed = r;
                    blamedShare = share;
                }
                blameByItem.merge(r.output(), share, Double::sum);
            }
        }
        if (blamed == null) {
            return false;
        }

        long allocated = pass.allocatedUnits().getOrDefault(blamed, 0L);
        long leafDemand = pass.need().getOrDefault(shortLeaf, 0L);
        // contribution of the blamed route to the leaf ≈ (blamedShare/shortfall)·leafDemand;
        // removing Δ allocation units removes Δ·contribution/allocated leaf units.
        double contribution = blamedShare / (double) shortfall * (double) leafDemand;
        long delta = contribution <= 0.0
                ? allocated
                : (long) Math.ceil((double) shortfall * (double) allocated / contribution);
        long newCap = Math.max(0L, allocated - Math.max(1L, delta));
        Long existing = caps.get(blamed);
        if (existing != null && existing <= newCap) {
            ineffective.add(blamed);
            return tightenBlamedRoute(pass, caps, ineffective, fixedItems);
        }
        caps.put(blamed, newCap);
        return true;
    }

    private boolean isContendedOutput(K output) {
        List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(output, List.of());
        return ps.size() > 1 && distinctMaterialBranches(capacityOrder(output)).size() > 1;
    }

    /**
     * Discovers independent ordinary-material conflict components in one topological sweep. A
     * reusable byproduct is another positive coefficient on its concrete item row: it joins decision
     * cones that can exchange that supply, but surplus never creates a demand of its own. Stateful
     * patterns disqualify only their local component; an unrelated byproduct or container no longer
     * disables exact solving for the whole AE2 network.
     */
    private LowWidthAnalysis<K> analyzeLowWidthComponents(
            ByproductSchedule<K> byproductSchedule,
            K target,
            long amount,
            Map<CraftPattern<K>, Long> baselineFirings) {
        if (preparedGraph.contendedOutputCount < 1) {
            return null;
        }

        List<K> analysisOrder = byproductSchedule.order();

        Map<K, Integer> decisionByOutput = new HashMap<>();
        for (K output : analysisOrder) {
            PlanningCancellation.check();
            if (isContendedOutput(output)) {
                decisionByOutput.put(output, decisionByOutput.size());
            }
        }
        // A single material fork can still constrain another fixed branch through shared stock.
        // Solve it in the same global balance model instead of committing locally and replaying the
        // entire plan after a later sibling exposes the conflict.
        if (decisionByOutput.isEmpty()) {
            return null;
        }

        IntDisjointSet unions = new IntDisjointSet(decisionByOutput.size());
        Map<K, Integer> ownerByItem = new HashMap<>(analysisOrder.size() * 2);
        for (K output : analysisOrder) {
            PlanningCancellation.check();
            Integer owner = ownerByItem.get(output);
            Integer decision = decisionByOutput.get(output);
            if (decision != null) {
                if (owner != null) {
                    unions.union(owner, decision);
                }
                owner = decision;
                ownerByItem.put(output, decision);
            }
            if (owner == null) {
                continue;
            }
            for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(output, List.of())) {
                for (CraftInput<K> input : pattern.inputs()) {
                    unionItemOwner(ownerByItem, unions, input.key(), owner);
                }
                for (CraftOutput<K> byproduct : pattern.byproducts()) {
                    if (byproductSchedule.models(pattern, byproduct.key())) {
                        unionItemOwner(ownerByItem, unions, byproduct.key(), owner);
                    }
                }
            }
        }

        Map<K, Integer> componentByItem = new HashMap<>(ownerByItem.size() * 2);
        Map<Integer, List<K>> itemsByComponent = new HashMap<>();
        List<Integer> componentSequence = new ArrayList<>();
        Set<Integer> seenComponents = new HashSet<>();
        for (K item : analysisOrder) {
            PlanningCancellation.check();
            Integer owner = ownerByItem.get(item);
            if (owner == null) {
                continue;
            }
            int component = unions.find(owner);
            componentByItem.put(item, component);
            itemsByComponent.computeIfAbsent(component, ignored -> new ArrayList<>()).add(item);
            if (seenComponents.add(component)) {
                componentSequence.add(component);
            }
        }

        Map<Integer, List<CraftPattern<K>>> patternsByComponent = new HashMap<>();
        Map<Integer, Boolean> supportedByComponent = new HashMap<>();
        Map<Integer, Boolean> orderedValidationByComponent = new HashMap<>();
        Map<Integer, Boolean> proofByComponent = new HashMap<>();
        for (Map.Entry<Integer, List<K>> entry : itemsByComponent.entrySet()) {
            PlanningCancellation.check();
            List<CraftPattern<K>> componentPatterns = new ArrayList<>();
            boolean supported = true;
            boolean orderedValidation = false;
            boolean exactProof = true;
            for (K output : entry.getValue()) {
                if (byproductSchedule.unsafeItems().contains(output)) {
                    // Unsafe byproduct precedences are deliberately not credited. A solved vector is
                    // still sound, but failure of that conservative model is not an infeasibility proof;
                    // only the recursive executor may validate the resulting fixed firing vector.
                    orderedValidation = true;
                    exactProof = false;
                }
                List<CraftPattern<K>> outputPatterns =
                        patternsByOutput.getOrDefault(output, List.of());
                componentPatterns.addAll(outputPatterns);
                for (CraftPattern<K> pattern : outputPatterns) {
                    if (byproductSchedule.hasSpeculativeByproduct(pattern)) {
                        orderedValidation = true;
                        exactProof = false;
                    }
                    if (suppressedPositiveFeedbackOutputs.containsKey(pattern)
                            || feedbackSeedBootstraps.containsKey(pattern)
                            || feedbackSeedConverters.containsKey(pattern)) {
                        orderedValidation = true;
                        exactProof = false;
                    }
                    for (CraftInput<K> input : pattern.inputs()) {
                        if (input.returned()
                                && input.uses() != CraftInput.INFINITE_USES) {
                            // Production durability chains are normalized to ordinary use-currency
                            // edges by FastCraftingPlanner. Keep the core finite-use fallback outside
                            // this matrix until it has its own ceil(x / uses) auxiliary variable.
                            supported = false;
                        }
                        if (input.returned() || input.remainder() != null
                                || input.reusableStockSource() != null) {
                            orderedValidation = true;
                            exactProof = false;
                        }
                    }
                }
            }
            patternsByComponent.put(entry.getKey(), componentPatterns);
            supportedByComponent.put(entry.getKey(), supported);
            orderedValidationByComponent.put(entry.getKey(), orderedValidation);
            proofByComponent.put(entry.getKey(), exactProof);
        }

        // Firings outside a component are deterministic prefix work. Their consumption is fixed
        // boundary demand; reusable byproducts they emit are fixed boundary supply. Keeping these as
        // separate non-negative maps avoids signed saturation and makes unused excess harmless.
        Map<Integer, Map<K, Long>> externalDemandByComponent = new HashMap<>();
        Map<Integer, Map<K, Long>> externalSupplyByComponent = new HashMap<>();
        Integer targetComponent = componentByItem.get(target);
        if (targetComponent != null) {
            externalDemandByComponent.computeIfAbsent(targetComponent, ignored -> new HashMap<>())
                    .merge(target, amount, Sat::add);
        }
        for (Map.Entry<CraftPattern<K>, Long> firing : baselineFirings.entrySet()) {
            PlanningCancellation.check();
            long times = firing.getValue();
            if (times <= 0) {
                continue;
            }
            CraftPattern<K> pattern = firing.getKey();
            Integer outputComponent = componentByItem.get(pattern.output());
            for (CraftInput<K> input : pattern.inputs()) {
                Integer inputComponent = componentByItem.get(input.key());
                if (inputComponent != null && !inputComponent.equals(outputComponent)) {
                    externalDemandByComponent
                            .computeIfAbsent(inputComponent, ignored -> new HashMap<>())
                            .merge(input.key(), input.unitsFor(times), Sat::add);
                }
            }
            for (CraftOutput<K> byproduct : pattern.byproducts()) {
                if (!byproductSchedule.reuses(pattern, byproduct.key())) {
                    continue;
                }
                Integer suppliedComponent = componentByItem.get(byproduct.key());
                if (suppliedComponent != null && !suppliedComponent.equals(outputComponent)) {
                    externalSupplyByComponent
                            .computeIfAbsent(suppliedComponent, ignored -> new HashMap<>())
                            .merge(byproduct.key(), Sat.mul(times, byproduct.amount()), Sat::add);
                }
            }
        }

        List<LowWidthComponent<K>> components = new ArrayList<>(componentSequence.size());
        for (int component : componentSequence) {
            PlanningCancellation.check();
            List<K> items = itemsByComponent.get(component);
            List<CraftPattern<K>> patterns = patternsByComponent.get(component);
            Set<K> boundary = new HashSet<>();
            int width = 0;
            boolean hasSpeculativeByproduct = false;
            IdentityHashMap<CraftPattern<K>, Set<K>> componentByproducts = new IdentityHashMap<>();
            for (K output : items) {
                boundary.remove(output);
                for (CraftPattern<K> pattern
                        : patternsByOutput.getOrDefault(output, List.of())) {
                    for (CraftInput<K> input : pattern.inputs()) {
                        if (Integer.valueOf(component).equals(componentByItem.get(input.key()))) {
                            boundary.add(input.key());
                        }
                    }
                    for (CraftOutput<K> byproduct : pattern.byproducts()) {
                        if (byproductSchedule.models(pattern, byproduct.key())
                                && Integer.valueOf(component).equals(
                                        componentByItem.get(byproduct.key()))) {
                            hasSpeculativeByproduct |=
                                    byproductSchedule.isSpeculative(pattern, byproduct.key());
                            boundary.add(byproduct.key());
                            componentByproducts
                                    .computeIfAbsent(pattern, ignored -> new HashSet<>())
                                    .add(byproduct.key());
                        }
                    }
                }
                width = Math.max(width, boundary.size());
            }
            diagnostics.recordSeparatorWidth(width);

            boolean validatedByproductWidth = hasSpeculativeByproduct
                    && width <= MAX_VALIDATED_BYPRODUCT_SEPARATOR;
            boolean exactEligible = !patterns.isEmpty()
                    && supportedByComponent.getOrDefault(component, false)
                    && (width <= MAX_LOW_WIDTH_SEPARATOR || validatedByproductWidth)
                    && patterns.size() <= MAX_LOW_WIDTH_VARIABLES
                    && items.size() <= MAX_LOW_WIDTH_CONSTRAINTS;
            components.add(new LowWidthComponent<>(
                    List.copyOf(items),
                    List.copyOf(patterns),
                    Map.copyOf(externalDemandByComponent.getOrDefault(component, Map.of())),
                    Map.copyOf(externalSupplyByComponent.getOrDefault(component, Map.of())),
                    freezeByproductMap(componentByproducts),
                    width,
                    hasSpeculativeByproduct,
                    exactEligible,
                    orderedValidationByComponent.getOrDefault(component, false),
                    proofByComponent.getOrDefault(component, false)));
        }

        long optimisticCapacity = byproductSchedule.hasRelevantByproducts()
                ? Sat.SAT
                : optimisticTargetCapacity(analysisOrder, target);
        return new LowWidthAnalysis<>(
                List.copyOf(components), optimisticCapacity, List.copyOf(analysisOrder));
    }

    private static <K> void unionItemOwner(
            Map<K, Integer> ownerByItem,
            IntDisjointSet unions,
            K item,
            int owner) {
        Integer existing = ownerByItem.putIfAbsent(item, owner);
        if (existing != null) {
            unions.union(owner, existing);
        }
    }

    private static <K> void discardSpeculativeByproductFixes(
            Map<CraftPattern<K>, Long> fixedFirings,
            Set<K> fixedItems,
            Set<CraftPattern<K>> speculativePatterns,
            Set<K> speculativeItems) {
        for (CraftPattern<K> pattern : speculativePatterns) {
            fixedFirings.remove(pattern);
        }
        fixedItems.removeAll(speculativeItems);
        speculativePatterns.clear();
        speculativeItems.clear();
    }

    private static <K> Map<CraftPattern<K>, Set<K>> freezeByproductMap(
            IdentityHashMap<CraftPattern<K>, Set<K>> mutable) {
        IdentityHashMap<CraftPattern<K>, Set<K>> frozen = new IdentityHashMap<>();
        mutable.forEach((pattern, keys) -> frozen.put(pattern, Set.copyOf(keys)));
        return java.util.Collections.unmodifiableMap(frozen);
    }

    /**
     * Builds one stable material-flow order that places a reusable byproduct producer before the
     * corresponding item row whenever that precedence is acyclic. Candidate byproduct precedences
     * that participate in an SCC are omitted as a group: finding a maximum acyclic subset would turn
     * this linear preprocessing step into another combinatorial problem. Omitted outputs remain in the
     * established shared pool and may still be consumed by recursive execution; the integer solver
     * simply does not claim them as guaranteed supply.
     */
    private ByproductSchedule<K> lowWidthByproductSchedule(List<K> order) {
        Set<K> nodes = new HashSet<>(order);
        Map<K, LinkedHashSet<K>> consumersByInput = new HashMap<>();
        List<ByproductLink<K>> candidates = new ArrayList<>();
        long work = 0L;
        for (K output : order) {
            PlanningCancellation.check();
            for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(output, List.of())) {
                if (++work > MAX_BYPRODUCT_SCHEDULE_WORK) {
                    return new ByproductSchedule<>(
                            List.copyOf(order), Map.of(), Map.of(), Set.of());
                }
                for (CraftInput<K> input : pattern.inputs()) {
                    if (++work > MAX_BYPRODUCT_SCHEDULE_WORK) {
                        return new ByproductSchedule<>(
                                List.copyOf(order), Map.of(), Map.of(), Set.of());
                    }
                    if (nodes.contains(input.key())) {
                        consumersByInput
                                .computeIfAbsent(input.key(), ignored -> new LinkedHashSet<>())
                                .add(output);
                    }
                }
                for (CraftOutput<K> byproduct : pattern.byproducts()) {
                    if (++work > MAX_BYPRODUCT_SCHEDULE_WORK) {
                        return new ByproductSchedule<>(
                                List.copyOf(order), Map.of(), Map.of(), Set.of());
                    }
                    if (!nodes.contains(byproduct.key())
                            || output.equals(byproduct.key())
                            || !mayReuseByproduct(pattern, byproduct.key())) {
                        continue;
                    }
                    candidates.add(new ByproductLink<>(pattern, output, byproduct.key()));
                }
            }
        }
        if (candidates.isEmpty()) {
            return new ByproductSchedule<>(
                    List.copyOf(order), Map.of(), Map.of(), Set.of());
        }

        Map<K, ScheduleItem<K>> itemNode = new HashMap<>(order.size() * 2);
        for (K item : order) {
            PlanningCancellation.check();
            itemNode.put(item, new ScheduleItem<>(item));
        }
        Map<K, ByproductBarrier<K>> barrierNode = new HashMap<>();
        for (ByproductLink<K> candidate : candidates) {
            barrierNode.computeIfAbsent(candidate.key(), ByproductBarrier::new);
        }

        List<ScheduleNode<K>> scheduleNodes = new ArrayList<>(order.size() + barrierNode.size());
        for (K item : order) {
            PlanningCancellation.check();
            ByproductBarrier<K> barrier = barrierNode.get(item);
            if (barrier != null) {
                scheduleNodes.add(barrier);
            }
            scheduleNodes.add(itemNode.get(item));
        }

        Map<ScheduleNode<K>, LinkedHashSet<ScheduleNode<K>>> normalEdges = new HashMap<>();
        Map<ScheduleNode<K>, LinkedHashSet<ScheduleNode<K>>> allEdges = new HashMap<>();
        for (ScheduleNode<K> node : scheduleNodes) {
            normalEdges.put(node, new LinkedHashSet<>());
            allEdges.put(node, new LinkedHashSet<>());
        }
        for (K output : order) {
            PlanningCancellation.check();
            ScheduleItem<K> from = itemNode.get(output);
            for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(output, List.of())) {
                for (CraftInput<K> input : pattern.inputs()) {
                    ScheduleItem<K> to = itemNode.get(input.key());
                    if (to != null && !output.equals(input.key())) {
                        normalEdges.get(from).add(to);
                        allEdges.get(from).add(to);
                    }
                }
            }
        }
        for (Map.Entry<K, ByproductBarrier<K>> entry : barrierNode.entrySet()) {
            PlanningCancellation.check();
            K key = entry.getKey();
            ByproductBarrier<K> barrier = entry.getValue();
            allEdges.get(barrier).add(itemNode.get(key));
            for (K consumer : consumersByInput.getOrDefault(key, new LinkedHashSet<>())) {
                allEdges.get(barrier).add(itemNode.get(consumer));
            }
        }
        for (ByproductLink<K> candidate : candidates) {
            PlanningCancellation.check();
            allEdges.get(itemNode.get(candidate.output())).add(barrierNode.get(candidate.key()));
        }

        Map<ScheduleNode<K>, Integer> components = stronglyConnectedComponents(
                scheduleNodes, freezeAdjacency(scheduleNodes, allEdges));
        Map<Integer, Set<K>> itemsByScc = new HashMap<>();
        for (K item : order) {
            PlanningCancellation.check();
            itemsByScc
                    .computeIfAbsent(components.get(itemNode.get(item)), ignored -> new HashSet<>())
                    .add(item);
        }

        IdentityHashMap<CraftPattern<K>, Set<K>> reusable = new IdentityHashMap<>();
        IdentityHashMap<CraftPattern<K>, Set<K>> speculative = new IdentityHashMap<>();
        Set<K> acceptedKeys = new HashSet<>();
        Set<K> unsafeItems = new HashSet<>();
        for (ByproductLink<K> candidate : candidates) {
            PlanningCancellation.check();
            int producerScc = components.get(itemNode.get(candidate.output()));
            if (producerScc == components.get(barrierNode.get(candidate.key()))) {
                unsafeItems.addAll(itemsByScc.getOrDefault(producerScc, Set.of()));
                speculative.computeIfAbsent(candidate.pattern(), ignored -> new HashSet<>())
                        .add(candidate.key());
                continue;
            }
            reusable.computeIfAbsent(candidate.pattern(), ignored -> new HashSet<>())
                    .add(candidate.key());
            acceptedKeys.add(candidate.key());
        }
        if (reusable.isEmpty()) {
            return new ByproductSchedule<>(
                    List.copyOf(order), Map.of(), freezeByproductMap(speculative),
                    Set.copyOf(unsafeItems));
        }

        Map<ScheduleNode<K>, LinkedHashSet<ScheduleNode<K>>> replayEdges = new HashMap<>();
        for (ScheduleNode<K> node : scheduleNodes) {
            replayEdges.put(node, new LinkedHashSet<>(normalEdges.get(node)));
        }
        for (K key : acceptedKeys) {
            PlanningCancellation.check();
            ByproductBarrier<K> barrier = barrierNode.get(key);
            replayEdges.get(barrier).add(itemNode.get(key));
            for (K consumer : consumersByInput.getOrDefault(key, new LinkedHashSet<>())) {
                replayEdges.get(barrier).add(itemNode.get(consumer));
            }
        }
        for (ByproductLink<K> candidate : candidates) {
            PlanningCancellation.check();
            if (reusable.getOrDefault(candidate.pattern(), Set.of()).contains(candidate.key())) {
                replayEdges.get(itemNode.get(candidate.output())).add(barrierNode.get(candidate.key()));
            }
        }

        List<ScheduleNode<K>> scheduled = stableTopologicalOrder(
                scheduleNodes, freezeAdjacency(scheduleNodes, replayEdges));
        if (scheduled.size() != scheduleNodes.size()) {
            // Defensive fail-closed path. Normal edges are already acyclic and inter-SCC candidate
            // edges cannot form a cycle, so this should be unreachable unless graph equality changed.
            Set<K> allUnsafe = new HashSet<>(unsafeItems);
            IdentityHashMap<CraftPattern<K>, Set<K>> allSpeculative =
                    new IdentityHashMap<>();
            for (ByproductLink<K> candidate : candidates) {
                allUnsafe.add(candidate.output());
                allUnsafe.add(candidate.key());
                allSpeculative
                        .computeIfAbsent(candidate.pattern(), ignored -> new HashSet<>())
                        .add(candidate.key());
            }
            return new ByproductSchedule<>(
                    List.copyOf(order), Map.of(), freezeByproductMap(allSpeculative),
                    Set.copyOf(allUnsafe));
        }
        List<K> replayOrder = new ArrayList<>(order.size());
        for (ScheduleNode<K> node : scheduled) {
            PlanningCancellation.check();
            if (node instanceof ScheduleItem<?> item) {
                @SuppressWarnings("unchecked")
                K key = (K) item.key();
                replayOrder.add(key);
            }
        }
        return new ByproductSchedule<>(
                List.copyOf(replayOrder), freezeByproductMap(reusable),
                freezeByproductMap(speculative), Set.copyOf(unsafeItems));
    }

    private static <K> Map<K, List<K>> freezeAdjacency(
            List<K> nodes, Map<K, LinkedHashSet<K>> mutable) {
        Map<K, List<K>> frozen = new HashMap<>(nodes.size() * 2);
        for (K node : nodes) {
            frozen.put(node, List.copyOf(mutable.getOrDefault(node, new LinkedHashSet<>())));
        }
        return frozen;
    }

    /** Iterative Kosaraju pass: linear in the local item and material-edge counts. */
    private static <K> Map<K, Integer> stronglyConnectedComponents(
            List<K> nodes, Map<K, List<K>> adjacency) {
        Set<K> seen = new HashSet<>();
        List<K> finished = new ArrayList<>(nodes.size());
        for (K start : nodes) {
            PlanningCancellation.check();
            if (!seen.add(start)) {
                continue;
            }
            Deque<SccFrame<K>> stack = new ArrayDeque<>();
            stack.push(new SccFrame<>(start, adjacency.getOrDefault(start, List.of())));
            while (!stack.isEmpty()) {
                PlanningCancellation.check();
                SccFrame<K> frame = stack.peek();
                if (frame.next < frame.neighbors.size()) {
                    K next = frame.neighbors.get(frame.next++);
                    if (seen.add(next)) {
                        stack.push(new SccFrame<>(
                                next, adjacency.getOrDefault(next, List.of())));
                    }
                } else {
                    finished.add(frame.node);
                    stack.pop();
                }
            }
        }

        Map<K, List<K>> reverse = new HashMap<>(nodes.size() * 2);
        for (K node : nodes) {
            PlanningCancellation.check();
            reverse.put(node, new ArrayList<>());
        }
        adjacency.forEach((from, targets) -> {
            for (K target : targets) {
                reverse.computeIfAbsent(target, ignored -> new ArrayList<>()).add(from);
            }
        });

        Map<K, Integer> component = new HashMap<>(nodes.size() * 2);
        int nextComponent = 0;
        for (int index = finished.size() - 1; index >= 0; index--) {
            PlanningCancellation.check();
            K start = finished.get(index);
            if (component.containsKey(start)) {
                continue;
            }
            Deque<K> stack = new ArrayDeque<>();
            stack.push(start);
            component.put(start, nextComponent);
            while (!stack.isEmpty()) {
                PlanningCancellation.check();
                K node = stack.pop();
                for (K previous : reverse.getOrDefault(node, List.of())) {
                    if (!component.containsKey(previous)) {
                        component.put(previous, nextComponent);
                        stack.push(previous);
                    }
                }
            }
            nextComponent++;
        }
        return component;
    }

    private static <K> List<K> stableTopologicalOrder(
            List<K> stableOrder, Map<K, List<K>> adjacency) {
        Map<K, Integer> stableIndex = new HashMap<>(stableOrder.size() * 2);
        Map<K, Integer> indegree = new HashMap<>(stableOrder.size() * 2);
        for (int index = 0; index < stableOrder.size(); index++) {
            PlanningCancellation.check();
            K node = stableOrder.get(index);
            stableIndex.put(node, index);
            indegree.put(node, 0);
        }
        adjacency.forEach((ignored, targets) -> {
            for (K target : targets) {
                indegree.merge(target, 1, Integer::sum);
            }
        });

        PriorityQueue<K> ready = new PriorityQueue<>(
                java.util.Comparator.comparingInt(stableIndex::get));
        for (K node : stableOrder) {
            if (indegree.getOrDefault(node, 0) == 0) {
                ready.add(node);
            }
        }
        List<K> result = new ArrayList<>(stableOrder.size());
        while (!ready.isEmpty()) {
            PlanningCancellation.check();
            K node = ready.poll();
            result.add(node);
            for (K target : adjacency.getOrDefault(node, List.of())) {
                int remaining = indegree.merge(target, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(target);
                }
            }
        }
        return result;
    }

    /** Safe route-summing upper bound; shared stock is deliberately counted more than once. */
    private long optimisticTargetCapacity(List<K> order, K target) {
        Map<K, Long> optimistic = new HashMap<>(order.size() * 2);
        for (int i = order.size() - 1; i >= 0; i--) {
            K output = order.get(i);
            long total = graph.stock(output);
            for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(output, List.of())) {
                long firings = Sat.SAT;
                for (CraftInput<K> input : pattern.inputs()) {
                    long available = optimistic.getOrDefault(
                            input.key(), graph.stock(input.key()));
                    firings = Math.min(
                            firings,
                            input.firingsFrom(available));
                    if (firings == 0) {
                        break;
                    }
                }
                total = Sat.add(total, Sat.mul(firings, pattern.outputAmount()));
            }
            optimistic.put(output, total);
        }
        return optimistic.getOrDefault(target, graph.stock(target));
    }

    private LowWidthSolve<K> solveLowWidthComponent(
            LowWidthComponent<K> model,
            List<Set<CraftPattern<K>>> noGoodSupports) {
        int patternVariableCount = model.patterns().size();
        IdentityHashMap<CraftPattern<K>, Integer> patternVariable = new IdentityHashMap<>();
        Set<CraftPattern<K>> decisionPatterns =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int variable = 0; variable < patternVariableCount; variable++) {
            CraftPattern<K> pattern = model.patterns().get(variable);
            patternVariable.put(pattern, variable);
            if (model.requiresOrderedValidation() && isContendedOutput(pattern.output())) {
                decisionPatterns.add(pattern);
            }
        }
        Map<K, Integer> rowByItem = new HashMap<>(model.items().size() * 2);
        for (int row = 0; row < model.items().size(); row++) {
            rowByItem.put(model.items().get(row), row);
        }

        // Returned inputs are activation costs, not per-firing material coefficients. One binary z_p
        // says whether pattern p fires at all; one reserve per logical pool/key then represents the
        // largest reusable state that must exist before any member of that pool starts. Host-private
        // supply gets a separate variable so it can never leak into an ordinary material row.
        IdentityHashMap<CraftPattern<K>, Integer> activationByPattern = new IdentityHashMap<>();
        LinkedHashMap<SeedReserveKey<K>, SeedGroupModel<K>> seedGroups = new LinkedHashMap<>();
        int variableCount = patternVariableCount;
        boolean pureSpeculativeMaterialComponent =
                isPureSpeculativeMaterialComponent(model);
        boolean compactSpeculativeFirstPass =
                pureSpeculativeMaterialComponent && noGoodSupports.isEmpty();
        if (!compactSpeculativeFirstPass) {
            for (CraftPattern<K> pattern : decisionPatterns) {
                activationByPattern.put(pattern, variableCount++);
            }
        } else {
            // Batch primary support needs z_p even on the compact first solve:
            // minimumOwn(p) = outputAmount * x_p - (outputAmount - 1) * z_p.
            for (CraftPattern<K> pattern : decisionPatterns) {
                if (pattern.outputAmount() != 1L) {
                    activationByPattern.put(pattern, variableCount++);
                }
            }
        }
        for (CraftPattern<K> pattern : model.patterns()) {
            for (CraftInput<K> input : pattern.inputs()) {
                if (!input.returned()) continue;
                if (input.uses() != CraftInput.INFINITE_USES) {
                    return LowWidthSolve.unsupported();
                }
                if (!activationByPattern.containsKey(pattern)) {
                    activationByPattern.put(pattern, variableCount++);
                }
            }
        }
        for (CraftPattern<K> pattern : model.patterns()) {
            Integer activation = activationByPattern.get(pattern);
            if (activation == null) continue;
            for (CraftInput<K> input : pattern.inputs()) {
                if (!input.returned() || input.uses() != CraftInput.INFINITE_USES) continue;
                ReusableStockSource source = input.reusableStockSource();
                SeedReserveKey<K> key = source == null
                        ? new SeedReserveKey<>(input.key(), null, null)
                        : new SeedReserveKey<>(
                                input.key(), source.storageScope(), source.poolScope());
                SeedGroupModel<K> group = seedGroups.computeIfAbsent(
                        key, ignored -> new SeedGroupModel<>(key));
                group.requirements.add(new SeedActivation<>(pattern, input, activation));
                if (source != null) {
                    for (K actual : graph.reusableStockCandidates(source, input.key())) {
                        group.acceptedHostKeys.add(actual);
                    }
                }
            }
        }
        for (SeedGroupModel<K> group : seedGroups.values()) {
            group.ordinaryReserveVariable = variableCount++;
            if (group.key.storageScope() != null) {
                group.hostReserveVariable = variableCount++;
            }
        }
        if (variableCount > MAX_LOW_WIDTH_VARIABLES) {
            return LowWidthSolve.unsupported();
        }

        long[][] coefficients = new long[model.items().size()][variableCount];
        try {
            for (int variable = 0; variable < patternVariableCount; variable++) {
                CraftPattern<K> pattern = model.patterns().get(variable);
                Integer outputRow = rowByItem.get(pattern.output());
                if (outputRow == null) {
                    return LowWidthSolve.unsupported();
                }
                addLowWidthCoefficient(
                        coefficients, outputRow, variable, pattern.outputAmount());
                for (CraftInput<K> input : pattern.inputs()) {
                    if (input.returned()) {
                        if (input.uses() != CraftInput.INFINITE_USES) {
                            return LowWidthSolve.unsupported();
                        }
                        continue;
                    }
                    Integer inputRow = rowByItem.get(input.key());
                    if (inputRow == null) {
                        return LowWidthSolve.unsupported();
                    }
                    addLowWidthCoefficient(
                            coefficients, inputRow, variable, -input.amount());
                }
                for (CraftOutput<K> byproduct : pattern.byproducts()) {
                    if (!model.reusesByproduct(pattern, byproduct.key())) {
                        continue;
                    }
                    Integer byproductRow = rowByItem.get(byproduct.key());
                    if (byproductRow == null) {
                        return LowWidthSolve.unsupported();
                    }
                    addLowWidthCoefficient(
                            coefficients, byproductRow, variable, byproduct.amount());
                }
            }

            for (SeedGroupModel<K> group : seedGroups.values()) {
                Integer row = rowByItem.get(group.key.key());
                if (row != null) {
                    // Host-private seeds are intentionally absent from the ordinary reachable-item
                    // order when nothing can consume or produce their key on the network. In that
                    // case the startup constraint below still caps the ordinary reserve by physical
                    // stock, but no material-balance row needs to reserve it from another demand.
                    addLowWidthCoefficient(
                            coefficients, row, group.ordinaryReserveVariable, -1L);
                }
            }
        } catch (ArithmeticException ignored) {
            return LowWidthSolve.unsupported();
        }

        List<BoundedIntegerLinearSolver.Constraint> constraints =
                new ArrayList<>(model.items().size() + 4 * variableCount);
        for (int row = 0; row < model.items().size(); row++) {
            K item = model.items().get(row);
            long external = model.externalDemand().getOrDefault(item, 0L);
            long available = Sat.add(
                    graph.stock(item), model.externalSupply().getOrDefault(item, 0L));
            long minimum;
            try {
                minimum = Math.subtractExact(external, available);
            } catch (ArithmeticException ignored) {
                return LowWidthSolve.unsupported();
            }
            constraints.add(new BoundedIntegerLinearSolver.Constraint(
                    coefficients[row], minimum));
        }

        // A route may not fire solely to manufacture a byproduct: each selected route receives at
        // least (times - 1) * outputAmount + 1 units of real primary demand. The activation term is
        // zero for unit recipes and accounts exactly for permitted batch-rounding surplus otherwise.
        if (pureSpeculativeMaterialComponent) {
            try {
                for (K output : model.items()) {
                    if (!isContendedOutput(output)) {
                        continue;
                    }
                    boolean hasProducer = false;
                    long[] primaryDemand = new long[variableCount];
                    for (int variable = 0; variable < patternVariableCount; variable++) {
                        CraftPattern<K> pattern = model.patterns().get(variable);
                        if (output.equals(pattern.output())) {
                            hasProducer = true;
                            long outputAmount = pattern.outputAmount();
                            primaryDemand[variable] = Math.subtractExact(
                                    primaryDemand[variable], outputAmount);
                            if (outputAmount != 1L) {
                                Integer activation = activationByPattern.get(pattern);
                                if (activation == null) {
                                    return LowWidthSolve.unsupported();
                                }
                                primaryDemand[activation] = Math.addExact(
                                        primaryDemand[activation], outputAmount - 1L);
                            }
                        }
                        for (CraftInput<K> input : pattern.inputs()) {
                            if (!input.returned() && output.equals(input.key())) {
                                primaryDemand[variable] = Math.addExact(
                                        primaryDemand[variable], input.amount());
                            }
                        }
                    }
                    if (hasProducer) {
                        constraints.add(new BoundedIntegerLinearSolver.Constraint(
                                primaryDemand,
                                -model.externalDemand().getOrDefault(output, 0L)));
                    }
                }
            } catch (ArithmeticException ignored) {
                return LowWidthSolve.unsupported();
            }
        }

        try {
            for (Map.Entry<CraftPattern<K>, Integer> entry : activationByPattern.entrySet()) {
                Integer patternIndex = patternVariable.get(entry.getKey());
                if (patternIndex == null) return LowWidthSolve.unsupported();
                int activationVariable = entry.getValue();

                long[] activeWhenFired = new long[variableCount];
                activeWhenFired[activationVariable] = Sat.SAT;
                activeWhenFired[patternIndex] = -1L;
                constraints.add(new BoundedIntegerLinearSolver.Constraint(activeWhenFired, 0L));

                long[] firedWhenActive = new long[variableCount];
                firedWhenActive[patternIndex] = 1L;
                firedWhenActive[activationVariable] = -1L;
                constraints.add(new BoundedIntegerLinearSolver.Constraint(firedWhenActive, 0L));

                long[] binary = new long[variableCount];
                binary[activationVariable] = -1L;
                constraints.add(new BoundedIntegerLinearSolver.Constraint(binary, -1L));
            }

            for (Set<CraftPattern<K>> rejectedSupport : noGoodSupports) {
                long[] noGood = new long[variableCount];
                int selected = 0;
                for (CraftPattern<K> pattern : decisionPatterns) {
                    Integer activation = activationByPattern.get(pattern);
                    if (activation == null) return LowWidthSolve.unsupported();
                    if (rejectedSupport.contains(pattern)) {
                        noGood[activation] = -1L;
                        selected++;
                    } else {
                        noGood[activation] = 1L;
                    }
                }
                // At least one decision bit must differ from the rejected execution vector.
                constraints.add(new BoundedIntegerLinearSolver.Constraint(
                        noGood, 1L - selected));
            }

            Map<Object, List<Integer>> hostVariablesByStorage = new LinkedHashMap<>();
            for (SeedGroupModel<K> group : seedGroups.values()) {
                Set<CraftPattern<K>> consumers =
                        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
                for (SeedActivation<K> requirement : group.requirements) {
                    consumers.add(requirement.pattern());
                    long[] seed = new long[variableCount];
                    seed[group.ordinaryReserveVariable] = 1L;
                    if (group.hostReserveVariable >= 0) {
                        seed[group.hostReserveVariable] = 1L;
                    }
                    seed[requirement.activationVariable()] = -requirement.input().amount();
                    constraints.add(new BoundedIntegerLinearSolver.Constraint(seed, 0L));
                }

                // A seed may be made by an acyclic alternative or an already-safe byproduct producer,
                // but never by a pattern that itself needs this same seed pool. This is the startup
                // barrier that rejects `seed -> net +seed` with no physical initial state.
                long[] startup = new long[variableCount];
                startup[group.ordinaryReserveVariable] = -1L;
                for (int variable = 0; variable < patternVariableCount; variable++) {
                    CraftPattern<K> producer = model.patterns().get(variable);
                    if (consumers.contains(producer)) continue;
                    if (group.key.key().equals(producer.output())) {
                        startup[variable] = Math.addExact(
                                startup[variable], producer.outputAmount());
                    }
                    for (CraftOutput<K> byproduct : producer.byproducts()) {
                        if (group.key.key().equals(byproduct.key())
                                && model.reusesByproduct(producer, byproduct.key())) {
                            startup[variable] = Math.addExact(
                                    startup[variable], byproduct.amount());
                        }
                    }
                }
                long startupStock = Sat.add(
                        graph.stock(group.key.key()),
                        model.externalSupply().getOrDefault(group.key.key(), 0L));
                constraints.add(new BoundedIntegerLinearSolver.Constraint(
                        startup, -startupStock));

                if (group.hostReserveVariable >= 0) {
                    long hostCapacity = 0L;
                    for (K actual : group.acceptedHostKeys) {
                        hostCapacity = Sat.add(
                                hostCapacity,
                                graph.reusableStock(group.key.storageScope(), actual));
                    }
                    long[] routeCapacity = new long[variableCount];
                    routeCapacity[group.hostReserveVariable] = -1L;
                    constraints.add(new BoundedIntegerLinearSolver.Constraint(
                            routeCapacity, -hostCapacity));
                    hostVariablesByStorage
                            .computeIfAbsent(
                                    group.key.storageScope(), ignored -> new ArrayList<>())
                            .add(group.hostReserveVariable);
                }
            }

            // Different logical loop pools still compete for one physical host inventory. Per-route
            // accepted-key caps above plus this storage-wide cap are a cheap relaxation of fuzzy
            // matching; the existing exact max-flow and ordered validation remain final authority.
            for (Map.Entry<Object, List<Integer>> entry : hostVariablesByStorage.entrySet()) {
                long total = 0L;
                for (Map.Entry<ReusableStockKey<K>, Long> stock : graph.reusableStock().entrySet()) {
                    if (entry.getKey().equals(stock.getKey().scope())) {
                        total = Sat.add(total, stock.getValue());
                    }
                }
                long[] storageCapacity = new long[variableCount];
                for (int variable : entry.getValue()) {
                    storageCapacity[variable] = -1L;
                }
                constraints.add(new BoundedIntegerLinearSolver.Constraint(
                        storageCapacity, -total));
            }
        } catch (ArithmeticException ignored) {
            return LowWidthSolve.unsupported();
        }

        int nodeBudget = Math.min(
                MAX_LOW_WIDTH_INTEGER_NODES,
                Math.max(16, 2 * variableCount + 4 * model.separatorWidth()));
        BoundedIntegerLinearSolver.Result result = BoundedIntegerLinearSolver.solve(
                variableCount,
                constraints,
                Sat.SAT,
                nodeBudget,
                lowWidthWorkBudget);
        if (!result.solved()) {
            return new LowWidthSolve<>(
                    result.status(), null, Set.of(), result.visitedNodes());
        }
        Map<CraftPattern<K>, Long> fired = new IdentityHashMap<>();
        Set<CraftPattern<K>> decisionSupport =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        long[] values = result.values();
        for (int i = 0; i < patternVariableCount; i++) {
            if (values[i] > 0L) {
                CraftPattern<K> pattern = model.patterns().get(i);
                fired.put(pattern, values[i]);
                if (decisionPatterns.contains(pattern)) {
                    decisionSupport.add(pattern);
                }
            }
        }
        return new LowWidthSolve<>(
                result.status(),
                fired,
                java.util.Collections.unmodifiableSet(decisionSupport),
                result.visitedNodes());
    }

    /**
     * True only for the narrow exact-cover-like path whose state is ordinary consumed material plus
     * speculative byproducts. Catalysts, returned containers, fuzzy reusable hosts and feedback
     * macros keep their established ordered-validation/replay semantics.
     */
    private boolean isPureSpeculativeMaterialComponent(LowWidthComponent<K> model) {
        if (!model.hasSpeculativeByproducts()) {
            return false;
        }
        for (CraftPattern<K> pattern : model.patterns()) {
            if (suppressedPositiveFeedbackOutputs.containsKey(pattern)
                    || feedbackSeedBootstraps.containsKey(pattern)
                    || feedbackSeedConverters.containsKey(pattern)) {
                return false;
            }
            for (CraftInput<K> input : pattern.inputs()) {
                if (input.returned()
                        || input.remainder() != null
                        || input.reusableStockSource() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void addLowWidthCoefficient(
            long[][] coefficients, int row, int variable, long delta) {
        coefficients[row][variable] = Math.addExact(coefficients[row][variable], delta);
    }

    /**
     * One topological aggregation sweep. {@code capUnits} (possibly empty) bounds how many units of
     * its own output each pattern may be allocated; the bound is soft — demand nobody has capacity
     * or cap room for is still pushed down the primary recipe so shortfalls always surface at raw
     * leaves, never as silently dropped demand.
     */
    private LinearPassState<K> linearPassState(
            List<K> order, K target, long amount, Map<CraftPattern<K>, Long> capUnits) {
        return linearPassState(order, target, amount, capUnits, Map.of(), Set.of());
    }

    private LinearPassState<K> linearPassState(
            List<K> order,
            K target,
            long amount,
            Map<CraftPattern<K>, Long> capUnits,
            Map<CraftPattern<K>, Long> fixedFirings,
            Set<K> fixedItems) {
        Map<K, Long> diagnosticUnitCosts = diagnosticUnitCosts(order);
        Map<K, Long> need = new HashMap<>();
        Map<K, Long> bp = new HashMap<>();       // byproduct / surplus pool
        Map<K, Long> stockL = new HashMap<>();   // remaining inventory
        Map<K, Long> used = new HashMap<>();
        Map<K, Long> miss = new HashMap<>();
        Map<K, Long> gross = new HashMap<>();
        // One unchanged catalyst can serve every compatible pattern sequentially. Track the largest
        // seed reserve separately so ordinary consumption of the same key is still added on top.
        Map<K, Long> returnedSeedReserve = new HashMap<>();
        Map<CraftPattern<K>, Long> fired = new IdentityHashMap<>();
        Map<CraftPattern<K>, Long> allocatedUnits = new IdentityHashMap<>();
        need.put(target, amount);
        int done = 0;
        boolean fixedAllocationFailed = false;

        for (K x : order) {
            PlanningCancellation.check();
            long d = need.getOrDefault(x, 0L);
            if (d <= 0) {
                continue;
            }
            done++;
            gross.put(x, d);

            long fromBp = Math.min(d, lget(bp, x));
            if (fromBp > 0) {
                bp.put(x, lget(bp, x) - fromBp);
                d -= fromBp;
            }
            long fromStock = Math.min(d, stockL.computeIfAbsent(x, graph::stock));
            if (fromStock > 0) {
                stockL.put(x, lget(stockL, x) - fromStock);
                used.merge(x, fromStock, Sat::add);
                d -= fromStock;
            }
            if (d <= 0) {
                continue;
            }

            if (fixedItems.contains(x)) {
                long unmet = allocateFixedLinear(
                        x,
                        d,
                        patternsByOutput.getOrDefault(x, List.of()),
                        need,
                        bp,
                        returnedSeedReserve,
                        fired,
                        allocatedUnits,
                        fixedFirings);
                if (unmet > 0) {
                    miss.merge(x, unmet, Sat::add);
                    fixedAllocationFailed = true;
                }
                continue;
            }

            List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(x, List.of());
            if (ps.isEmpty()) {
                miss.merge(x, d, Sat::add);
                continue;
            }
            allocateLinear(
                    x,
                    d,
                    ps,
                    need,
                    bp,
                    returnedSeedReserve,
                    fired,
                    capUnits,
                    allocatedUnits,
                    diagnosticUnitCosts);
        }

        return new LinearPassState<>(
                need, fired, used, miss, gross, allocatedUnits, done, fixedAllocationFailed);
    }

    /** Replays one solved component's integer firing vector without reopening its route choices. */
    private long allocateFixedLinear(
            K output,
            long demand,
            List<CraftPattern<K>> patterns,
            Map<K, Long> need,
            Map<K, Long> bp,
            Map<K, Long> returnedSeedReserve,
            Map<CraftPattern<K>, Long> fired,
            Map<CraftPattern<K>, Long> allocatedUnits,
            Map<CraftPattern<K>, Long> fixedFirings) {
        List<FixedPatternAllocation<K>> selected =
                fixedPatternAllocations(demand, patterns, fixedFirings);
        if (selected.isEmpty()) return demand;

        for (FixedPatternAllocation<K> allocation : selected) {
            CraftPattern<K> pattern = allocation.pattern();
            allocatedUnits.merge(pattern, allocation.allocated, Sat::add);
            fireLinear(
                    output,
                    pattern,
                    allocation.times(),
                    allocation.allocated,
                    need,
                    bp,
                    returnedSeedReserve,
                    fired);
        }
        return 0L;
    }

    /**
     * Assigns primary demand to an exact firing vector. Every active route receives enough demand to
     * justify its final firing; any remaining batch surplus is still available to the shared pool.
     */
    private List<FixedPatternAllocation<K>> fixedPatternAllocations(
            long demand,
            List<CraftPattern<K>> patterns,
            Map<CraftPattern<K>, Long> quotas) {
        List<FixedPatternAllocation<K>> selected = new ArrayList<>();
        long minimumAllocated = 0L;
        long totalProduced = 0L;
        for (CraftPattern<K> pattern : patterns) {
            long times = quotas.getOrDefault(pattern, 0L);
            if (times <= 0L) continue;
            long produced = Sat.mul(times, pattern.outputAmount());
            long minimumOwn = Sat.add(
                    Sat.mul(times - 1L, pattern.outputAmount()), 1L);
            minimumAllocated = Sat.add(minimumAllocated, minimumOwn);
            totalProduced = Sat.add(totalProduced, produced);
            selected.add(new FixedPatternAllocation<>(pattern, times, produced, minimumOwn));
        }
        if (selected.isEmpty() || minimumAllocated > demand || totalProduced < demand) {
            return List.of();
        }

        long remainingExtra = demand - minimumAllocated;
        for (FixedPatternAllocation<K> allocation : selected) {
            long extra = Math.min(
                    remainingExtra, allocation.produced() - allocation.minimumOwn());
            allocation.allocated = Sat.add(allocation.minimumOwn(), extra);
            remainingExtra -= extra;
        }
        return remainingExtra == 0L ? selected : List.of();
    }

    /** Split {@code d} of {@code x} across recipes by current remaining capacity (dynamic balance). */
    private void allocateLinear(K x, long d, List<CraftPattern<K>> ps,
                                Map<K, Long> need, Map<K, Long> bp,
                                Map<K, Long> returnedSeedReserve,
                                Map<CraftPattern<K>, Long> fired,
                                Map<CraftPattern<K>, Long> capUnits,
                                Map<CraftPattern<K>, Long> allocatedUnits,
                                Map<K, Long> diagnosticUnitCosts) {
        List<CraftPattern<K>> ordered = groupedCapacityOrder(
                ps,
                pattern -> capRemainingVia(pattern, need),
                pattern -> preexistingStockRemainingCapacity(pattern, need));

        for (CraftPattern<K> r : ordered) {
            if (d <= 0) {
                break;
            }
            long p = capRemainingVia(r, need);
            Long cap = capUnits.get(r);
            if (cap != null) {
                p = Math.min(p, Math.max(0L, cap - allocatedUnits.getOrDefault(r, 0L)));
            }
            if (p <= 0) {
                continue;
            }
            long make = Math.min(d, p);
            long t = Sat.ceilDiv(make, r.outputAmount());
            long consumed = Math.min(d, Sat.mul(t, r.outputAmount()));
            allocatedUnits.merge(r, consumed, Sat::add);
            fireLinear(x, r, t, consumed, need, bp, returnedSeedReserve, fired);
            d -= consumed;
        }
        // Leftover nobody had capacity for: push demand down one concrete recipe so the deficit
        // surfaces at raw leaves. Prefer a route component-wise no worse on shared constrained inputs,
        // then use the ordinary downstream raw cost to avoid an arbitrarily inflated missing report.
        if (d > 0) {
            CraftPattern<K> r0 = diagnosticRoute(d, ordered, need, diagnosticUnitCosts);
            long t = Sat.ceilDiv(d, r0.outputAmount());
            allocatedUnits.merge(r0, d, Sat::add);
            fireLinear(x, r0, t, d, need, bp, returnedSeedReserve, fired);
        }
    }

    private CraftPattern<K> diagnosticRoute(
            long demand, List<CraftPattern<K>> ordered, Map<K, Long> need) {
        return diagnosticRoute(demand, ordered, need, Map.of());
    }

    private CraftPattern<K> diagnosticRoute(
            long demand,
            List<CraftPattern<K>> ordered,
            Map<K, Long> need,
            Map<K, Long> diagnosticUnitCosts) {
        if (ordered.size() <= 1) {
            return ordered.get(0);
        }
        Map<K, Integer> routeCounts = new HashMap<>();
        for (CraftPattern<K> pattern : ordered) {
            Set<K> seen = new HashSet<>();
            for (CraftInput<K> input : pattern.inputs()) {
                if (!input.returned() && seen.add(input.key())) {
                    routeCounts.merge(input.key(), 1, Integer::sum);
                }
            }
        }
        Set<K> shared = new HashSet<>();
        routeCounts.forEach((key, count) -> {
            if (count > 1) {
                shared.add(key);
            }
        });
        CraftPattern<K> best = ordered.get(0);
        for (int i = 1; i < ordered.size(); i++) {
            CraftPattern<K> candidate = ordered.get(i);
            boolean candidateSharedBetter = !shared.isEmpty()
                    && projectedSharedBetter(candidate, best, demand, need, shared);
            boolean bestSharedBetter = !shared.isEmpty()
                    && projectedSharedBetter(best, candidate, demand, need, shared);
            if (candidateSharedBetter
                    || (!bestSharedBetter
                            && diagnosticRawCost(candidate, demand, diagnosticUnitCosts)
                                    < diagnosticRawCost(best, demand, diagnosticUnitCosts))) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Bottom-up raw-material cost used only to choose a concrete missing route when every recipe has
     * zero executable capacity. It never changes feasibility or credits unavailable material. Stateful
     * inputs and byproducts stay on the established route order because their startup/reuse semantics
     * cannot be represented by an additive raw cost.
     */
    private Map<K, Long> diagnosticUnitCosts(List<K> order) {
        Map<K, Long> costs = new HashMap<>(order.size() * 2);
        for (int i = order.size() - 1; i >= 0; i--) {
            K item = order.get(i);
            List<CraftPattern<K>> patterns = patternsByOutput.getOrDefault(item, List.of());
            if (patterns.isEmpty()) {
                costs.put(item, 1L);
                continue;
            }
            long best = Sat.SAT;
            for (CraftPattern<K> pattern : patterns) {
                best = Math.min(best, diagnosticRawCost(pattern, 1L, costs));
            }
            if (best < Sat.SAT) {
                costs.put(item, best);
            }
        }
        return costs;
    }

    private long diagnosticRawCost(
            CraftPattern<K> pattern, long demand, Map<K, Long> diagnosticUnitCosts) {
        if (diagnosticUnitCosts.isEmpty() || !pattern.byproducts().isEmpty()) {
            return Sat.SAT;
        }
        long firings = Sat.ceilDiv(demand, pattern.outputAmount());
        long total = 0L;
        for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() || input.remainder() != null) {
                return Sat.SAT;
            }
            Long unitCost = diagnosticUnitCosts.get(input.key());
            if (unitCost == null) {
                return Sat.SAT;
            }
            total = Sat.add(total, Sat.mul(input.unitsFor(firings), unitCost));
        }
        return total;
    }

    private boolean projectedSharedBetter(
            CraftPattern<K> candidate,
            CraftPattern<K> current,
            long demand,
            Map<K, Long> need,
            Set<K> shared) {
        boolean strict = false;
        for (K key : shared) {
            long available = Math.max(
                    0L,
                    capacity.getOrDefault(key, graph.stock(key)) - need.getOrDefault(key, 0L));
            long candidateUnits = routeInputUnits(candidate, key, demand);
            long currentUnits = routeInputUnits(current, key, demand);
            long candidateMissing = Math.max(0L, candidateUnits - available);
            long currentMissing = Math.max(0L, currentUnits - available);
            if (candidateMissing > currentMissing
                    || (candidateMissing == currentMissing && candidateUnits > currentUnits)) {
                return false;
            }
            if (candidateMissing < currentMissing
                    || (candidateMissing == currentMissing && candidateUnits < currentUnits)) {
                strict = true;
            }
        }
        return strict;
    }

    private static <K> long routeInputUnits(
            CraftPattern<K> pattern, K key, long outputDemand) {
        long firings = Sat.ceilDiv(outputDemand, pattern.outputAmount());
        long total = 0L;
        for (CraftInput<K> input : pattern.inputs()) {
            if (!input.returned() && input.key().equals(key)) {
                total = Sat.add(total, input.unitsFor(firings));
            }
        }
        return total;
    }

    private void fireLinear(K x, CraftPattern<K> r, long t, long consumedOwn,
                            Map<K, Long> need, Map<K, Long> bp,
                            Map<K, Long> returnedSeedReserve,
                            Map<CraftPattern<K>, Long> fired) {
        long previousFirings = fired.getOrDefault(r, 0L);
        fired.merge(r, t, Sat::add);
        for (CraftInput<K> in : r.inputs()) {
            long amt = in.unitsFor(t); // closed form: normal=amount·t, catalyst=amount, finite-use=amount·ceil(t/uses)
            if (in.returned()
                    && in.uses() == CraftInput.INFINITE_USES
                    && in.reusableStockSource() == null) {
                long previousReserve = returnedSeedReserve.getOrDefault(in.key(), 0L);
                if (amt > previousReserve) {
                    need.merge(in.key(), amt - previousReserve, Sat::add);
                    returnedSeedReserve.put(in.key(), amt);
                }
            } else {
                need.merge(in.key(), amt, Sat::add);
            }
        }
        Map<K, Long> bootstrapReserves = new HashMap<>(
                linearContainerBootstrapReserves.getOrDefault(r, Map.of()));
        bootstrapReserves.replaceAll((key, reserve) -> Math.max(
                0L, reserve - Math.min(
                        reserve, Sat.mul(byproductAmount(r, key), previousFirings))));
        for (CraftOutput<K> out : r.byproducts()) {
            if (mayReuseByproduct(r, out.key())) {
                long produced = Sat.mul(out.amount(), t);
                long withheld = Math.min(
                        produced, bootstrapReserves.getOrDefault(out.key(), 0L));
                if (withheld > 0) {
                    bootstrapReserves.put(out.key(), bootstrapReserves.get(out.key()) - withheld);
                }
                if (produced > withheld) {
                    bp.merge(out.key(), produced - withheld, Sat::add);
                }
            }
        }
        long surplus = Sat.mul(t, r.outputAmount()) - consumedOwn;
        if (surplus > 0) {
            bp.merge(x, surplus, Sat::add);
        }
    }

    /** capRemaining(input) = static capacity − already-reserved demand; combined over a recipe's inputs. */
    private long capRemainingVia(CraftPattern<K> r, Map<K, Long> need) {
        long bound = Sat.SAT;
        for (CraftInput<K> in : r.inputs()) {
            long cr = Math.max(0L, capacity.getOrDefault(in.key(), 0L) - need.getOrDefault(in.key(), 0L));
            bound = Math.min(bound, in.firingsFrom(cr));
            if (bound == 0) {
                return 0;
            }
        }
        return Sat.mul(bound, r.outputAmount());
    }

    private long preexistingStockRemainingCapacity(
            CraftPattern<K> pattern, Map<K, Long> need) {
        long bound = Sat.SAT;
        for (CraftInput<K> input : pattern.inputs()) {
            long remaining = Math.max(
                    0L, graph.stock(input.key()) - need.getOrDefault(input.key(), 0L));
            bound = Math.min(bound, input.firingsFrom(remaining));
            if (bound == 0) return 0L;
        }
        return Sat.mul(bound, pattern.outputAmount());
    }

    /**
     * Orders real recipes by total capacity, but keeps concrete fuzzy expansions of the same source
     * together and consumes their stocked variants first.
     */
    private List<CraftPattern<K>> groupedCapacityOrder(
            List<CraftPattern<K>> patterns,
            ToLongFunction<CraftPattern<K>> totalCapacity,
            ToLongFunction<CraftPattern<K>> directStockCapacity) {
        if (patterns.size() < 2) return patterns;

        List<List<CraftPattern<K>>> groups = new ArrayList<>();
        IdentityHashMap<Object, List<CraftPattern<K>>> bySource = new IdentityHashMap<>();
        for (CraftPattern<K> pattern : patterns) {
            Object source = pattern.source();
            if (source == null) {
                groups.add(new ArrayList<>(List.of(pattern)));
                continue;
            }
            List<CraftPattern<K>> group = bySource.get(source);
            if (group == null) {
                group = new ArrayList<>();
                bySource.put(source, group);
                groups.add(group);
            }
            group.add(pattern);
        }

        for (List<CraftPattern<K>> group : groups) {
            if (group.size() > 1) {
                group.sort((left, right) -> {
                    int byStock = Long.compare(
                            directStockCapacity.applyAsLong(right),
                            directStockCapacity.applyAsLong(left));
                    return byStock != 0
                            ? byStock
                            : Long.compare(
                                    totalCapacity.applyAsLong(right),
                                    totalCapacity.applyAsLong(left));
                });
            }
        }
        groups.sort((left, right) -> Long.compare(
                maxCapacity(right, totalCapacity),
                maxCapacity(left, totalCapacity)));

        List<CraftPattern<K>> ordered = new ArrayList<>(patterns.size());
        for (List<CraftPattern<K>> group : groups) ordered.addAll(group);
        return ordered;
    }

    private long maxCapacity(
            List<CraftPattern<K>> patterns,
            ToLongFunction<CraftPattern<K>> capacityFunction) {
        long best = 0L;
        for (CraftPattern<K> pattern : patterns) {
            best = Math.max(best, capacityFunction.applyAsLong(pattern));
        }
        return best;
    }

    private long lget(Map<K, Long> m, K k) {
        Long v = m.get(k);
        return v == null ? 0L : v;
    }

    // ---- core: obtain d units of x, consuming from pool/stock, crafting the rest ----------------

    /**
     * @param commitFailure whether an exhausted route must commit its greedy partial plan and concrete
     *                      missing leaves. Speculative parents pass {@code false}: they only need a
     *                      non-zero result before rolling the branch back.
     * @return the amount of {@code x} that could not be obtained
     */
    private long obtain(K x, long d, boolean commitFailure) {
        PlanningCancellation.check();
        if (d <= 0) {
            return 0;
        }
        bump(grossDemand, x, d);
        reserveSelfSeed(x);
        reserveFeedbackSeedOutput(x, d);
        d -= drawPools(x, d);
        if (d <= 0) {
            return 0;
        }

        if (fixedFallbackItems.contains(x)) {
            return obtainFixedComponent(x, d, commitFailure);
        }

        // This is a branch-local safety guard. Report only the unstocked remainder as missing so the
        // parent may roll this branch back and try another route; never invalidate unrelated nodes.
        if (depth >= MAX_OBTAIN_DEPTH) {
            if (commitFailure) addMissing(x, d);
            return d;
        }

        List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(x, List.of());
        if (ps.isEmpty()) {
            if (commitFailure) addMissing(x, d);
            return d;
        }

        // An uncontended cone needs no route search: resolve it with one topological demand
        // aggregation instead of per-edge recursion. Recursion multiplies obtain() calls by the
        // number of root-to-node paths (exponential when a deep chain's tiers share nodes), so the
        // recursive search below is reserved for genuinely contended nodes.
        if (isAggregable(x)) {
            return obtainAggregate(x, d, commitFailure);
        }

        // Depth is part of the proof identity: a route rejected only because it reached the stack
        // guard must remain eligible when the same node is later reached through a shorter parent path.
        SearchFailure<K> failureKey = new SearchFailure<>(x, d, availabilityState, depth);
        if (!commitFailure && failedSpeculativeSearches.contains(failureKey)) {
            diagnostics.recordFailureMemoHit();
            return d;
        }

        // Stateful/cyclic boundaries cannot use the aggregate sweep and therefore need their own
        // bounded resolution guard. This is deliberately separate from alternative-search work: a
        // single returned seed or container is deterministic and must not consume search budget.
        if (!diagnostics.tryConsumeResolutionWork()) {
            return commitFailure ? commitBudgetFallback(x, d) : d;
        }

        if (processed < Integer.MAX_VALUE) {
            processed++;
        }

        // A single recipe needs no alternate search, but its descendants still resolve their own
        // contention normally. It consumes resolution work above, never alternative-search work.
        if (ps.size() == 1) {
            long unmet = fire(x, ps.get(0), d, !commitFailure);
            if (!commitFailure && unmet > 0
                    && !searchBudget.exhausted() && !diagnostics.resolutionExhausted()) {
                failedSpeculativeSearches.add(failureKey);
            }
            return unmet;
        }

        List<CraftPattern<K>> ordered = capacityOrder(x);
        List<CraftPattern<K>> distinctBranches = distinctMaterialBranches(ordered);
        if (distinctBranches.size() == 1) {
            // There is no materially different alternative to discover. Commit the representative
            // once instead of speculatively expanding it, rolling it back, and expanding it again.
            long unmet = fire(x, distinctBranches.get(0), d, !commitFailure);
            if (!commitFailure && unmet > 0
                    && !searchBudget.exhausted() && !diagnostics.resolutionExhausted()) {
                failedSpeculativeSearches.add(failureKey);
            }
            return unmet;
        }

        // Only a materially contended node reaches this point. Once alternative-search work is
        // exhausted, finish its selected capacity-first route through the separately bounded tail.
        if (searchBudget.exhausted()) {
            return commitFailure ? commitBudgetFallback(x, d) : d;
        }

        int v = visit.getOrDefault(x, 0);
        if (v >= visitCap) {
            diagnostics.recordHotNodeVisit();
            return obtainHot(x, d, commitFailure, failureKey);
        }
        visit.put(x, v + 1);

        for (CraftPattern<K> r : distinctBranches) {
            if (hasProvenDirectConsumableShortfall(r, d)) {
                continue;
            }
            if (!searchBudget.tryConsume()) {
                return commitFailure ? commitBestEffort(distinctBranches, x, d) : d;
            }
            int mark = trail.size();
            long beforeMissing = missingTotal;
            long unmet = fire(x, r, d, true);
            if (searchBudget.exhausted() || diagnostics.resolutionExhausted()) {
                rollback(mark);
                return commitFailure ? commitBestEffort(distinctBranches, x, d) : d;
            }
            if (unmet == 0 && missingTotal == beforeMissing) {
                return unmet; // this recipe satisfied d without introducing any shortfall
            }
            rollback(mark); // restores pool/firings/missing(+total); try the next recipe
        }
        if (!commitFailure) {
            failedSpeculativeSearches.add(failureKey);
            return d;
        }
        // Root/final route: commit the highest-capacity one and record its concrete missing leaves.
        return commitBestEffort(distinctBranches, x, d);
    }

    /**
     * Recursive counterpart of the pinned linear replay. It consumes only the remaining firing
     * quotas proven for this independent exact component, so search in a wide sibling cannot reopen
     * or overwrite the component's route allocation. Quota writes use the ordinary trail and are
     * therefore rollback-safe if an unresolved ancestor is speculative.
     */
    private long obtainFixedComponent(K output, long demand, boolean commitFailure) {
        if (processed < Integer.MAX_VALUE) {
            processed++;
        }
        long remaining = demand;
        long inputUnmet = 0L;
        List<CraftPattern<K>> fixedOrder = new ArrayList<>(
                patternsByOutput.getOrDefault(output, List.of()));
        // A self-output gain macro must acquire its seed before an alternative producer's output can
        // be counted as finished target demand. Stable sorting changes only that startup boundary;
        // ordinary patterns retain graph/preference order.
        fixedOrder.sort((left, right) -> Boolean.compare(
                hasInfiniteReturnedSeed(right), hasInfiniteReturnedSeed(left)));
        if (fixedBatchReplayOutputs.contains(output)) {
            return obtainAllocatedFixedComponent(output, demand, commitFailure, fixedOrder);
        }
        for (CraftPattern<K> pattern : fixedOrder) {
            long quota = get(fixedFiringQuota, pattern);
            if (quota <= 0L || remaining <= 0L) {
                continue;
            }
            long times = Math.min(quota, Sat.ceilDiv(remaining, pattern.outputAmount()));
            long produced = Sat.mul(times, pattern.outputAmount());
            long requested = Math.min(remaining, produced);
            put(fixedFiringQuota, pattern, quota - times);
            long unmet = fire(output, pattern, requested, !commitFailure);
            inputUnmet = Sat.add(inputUnmet, unmet);
            remaining -= requested;
            if (!commitFailure && unmet > 0L) {
                return Sat.add(inputUnmet, remaining);
            }
        }
        if (remaining > 0L) {
            if (commitFailure) {
                addMissing(output, remaining);
            }
            inputUnmet = Sat.add(inputUnmet, remaining);
        }
        return inputUnmet;
    }

    /**
     * Replays every selected pure-material route with the same minimum-primary allocation used by
     * {@link #allocateFixedLinear}. This is necessary for batch recipes: sequentially satisfying the
     * whole primary demand with the first routes can otherwise leave a selected later route unfired,
     * so its solver-promised byproducts never enter the real ordered pool.
     */
    private long obtainAllocatedFixedComponent(
            K output,
            long demand,
            boolean commitFailure,
            List<CraftPattern<K>> fixedOrder) {
        List<FixedPatternAllocation<K>> selected =
                fixedPatternAllocations(demand, fixedOrder, fixedFiringQuota);
        if (selected.isEmpty()) {
            if (commitFailure) {
                addMissing(output, demand);
            }
            return demand;
        }

        long inputUnmet = 0L;
        for (FixedPatternAllocation<K> allocation : selected) {
            CraftPattern<K> pattern = allocation.pattern();
            put(fixedFiringQuota, pattern,
                    get(fixedFiringQuota, pattern) - allocation.times());
            long unmet = fire(output, pattern, allocation.allocated, !commitFailure);
            inputUnmet = Sat.add(inputUnmet, unmet);
            if (!commitFailure && unmet > 0L) {
                return inputUnmet;
            }
        }
        return inputUnmet;
    }

    private static <K> boolean hasInfiniteReturnedSeed(CraftPattern<K> pattern) {
        for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() && input.uses() == CraftInput.INFINITE_USES) {
                return true;
            }
        }
        return false;
    }

    /**
     * A node revisited under many parent alternatives is re-ranked against the exact current pools.
     * Every materially distinct route remains eligible: the plan-wide work budget, rather than a
     * per-node candidate cutoff, is the only search bound.
     */
    private long obtainHot(
            K x, long d, boolean commitFailure, SearchFailure<K> failureKey) {
        List<CraftPattern<K>> distinctBranches = hotRouteOrder(x);
        if (searchBudget.exhausted()) {
            return commitFailure ? commitBestEffort(distinctBranches, x, d) : d;
        }
        for (CraftPattern<K> route : distinctBranches) {
            if (hasProvenDirectConsumableShortfall(route, d)) {
                continue;
            }
            if (!searchBudget.tryConsume()) {
                return commitFailure ? commitBestEffort(distinctBranches, x, d) : d;
            }
            int mark = trail.size();
            long beforeMissing = missingTotal;
            long unmet = fire(x, route, d, true);
            if (searchBudget.exhausted() || diagnostics.resolutionExhausted()) {
                rollback(mark);
                return commitFailure ? commitBestEffort(distinctBranches, x, d) : d;
            }
            if (unmet == 0 && missingTotal == beforeMissing) {
                return 0L;
            }
            rollback(mark);
        }
        if (!commitFailure) {
            failedSpeculativeSearches.add(failureKey);
            return d;
        }
        return commitBestEffort(distinctBranches, x, d);
    }

    /**
     * Re-ranks the immutable capacity order using the inventory/byproduct pools of this exact branch.
     * Only ordinary DAG material edges participate in the dynamic estimate; returned/reusable inputs
     * retain their already-proven static score because their availability has separate reservation
     * semantics. Sorting is stable, so equal estimates preserve the caller-defined preference order.
     */
    private List<CraftPattern<K>> hotRouteOrder(K x) {
        List<CraftPattern<K>> routes =
                new ArrayList<>(distinctMaterialBranches(capacityOrder(x)));
        if (routes.size() < 2) {
            return routes;
        }

        Map<K, Long> currentCapacity = new HashMap<>();
        Map<CraftPattern<K>, Long> scores = new IdentityHashMap<>();
        for (CraftPattern<K> route : routes) {
            scores.put(route, currentCapacityVia(
                    route, currentCapacity, new HashSet<>()));
            if (searchBudget.exhausted()) {
                return routes;
            }
        }
        routes.sort((left, right) ->
                Long.compare(scores.get(right), scores.get(left)));
        return routes;
    }

    /**
     * Consumable-bound no-good proof for this exact rollback-restored pool. Full material footprints
     * need not match: {@code 5 A + 8 C} and {@code 4 B + 8 C} are both impossible when fewer than
     * {@code 8 C} remain and C has no producer. Quantity is aggregated across duplicate slots, so a
     * route needing only one available C is never pruned by another route's eight-C shortfall.
     *
     * <p>Only ordinary, directly consumed raw leaves participate. Returned/reusable inputs and
     * craftable intermediates keep their normal reservation and recursive-search semantics.
     */
    private boolean hasProvenDirectConsumableShortfall(CraftPattern<K> pattern, long demand) {
        long times = Sat.ceilDiv(demand, pattern.outputAmount());
        for (Map.Entry<K, Long> entry
                : directRawConsumablesByPattern.getOrDefault(pattern, Map.of()).entrySet()) {
            long required = Sat.mul(entry.getValue(), times);
            ConsumableProofState<K> state =
                    new ConsumableProofState<>(entry.getKey(), availabilityState);
            Long unavailableFrom = provenDirectConsumableShortfalls.get(state);
            if (unavailableFrom != null && required >= unavailableFrom) {
                return true;
            }
            long available = Sat.add(
                    get(stockLeft, entry.getKey()), get(bpPool, entry.getKey()));
            if (available < required) {
                provenDirectConsumableShortfalls.merge(state, required, Math::min);
                return true;
            }
        }
        return false;
    }

    private long currentCapacityVia(
            CraftPattern<K> pattern,
            Map<K, Long> memo,
            Set<K> evaluating) {
        diagnostics.recordDynamicCapacityEvaluation();
        if (!searchBudget.tryConsume()) {
            return 0L;
        }
        long bound = Sat.SAT;
        for (CraftInput<K> input : pattern.inputs()) {
            if (input.returned() || input.reusableStockSource() != null) {
                return capacityScore(pattern);
            }
            long available = currentCapacity(input.key(), memo, evaluating);
            bound = Math.min(bound, input.firingsFrom(available));
            if (bound == 0) {
                return 0L;
            }
        }
        return Sat.mul(bound, pattern.outputAmount());
    }

    private long currentCapacity(
            K key,
            Map<K, Long> memo,
            Set<K> evaluating) {
        Long cached = memo.get(key);
        if (cached != null) {
            return cached;
        }

        long immediate = Sat.add(get(stockLeft, key), get(bpPool, key));
        if (!evaluating.add(key)) {
            return immediate;
        }
        long bestCrafted = 0L;
        for (CraftPattern<K> pattern
                : patternsByOutput.getOrDefault(key, List.of())) {
            bestCrafted = Math.max(
                    bestCrafted,
                    currentCapacityVia(pattern, memo, evaluating));
            if (searchBudget.exhausted()) {
                evaluating.remove(key);
                return immediate;
            }
            if (Sat.isSaturated(bestCrafted)) {
                break;
            }
        }
        evaluating.remove(key);

        long result = Sat.add(immediate, bestCrafted);
        memo.put(key, result);
        return result;
    }

    private List<CraftPattern<K>> distinctMaterialBranches(List<CraftPattern<K>> ordered) {
        if (ordered.size() < 2 || materialFootprintByPattern.isEmpty()) {
            return ordered;
        }
        Set<Integer> seen = new HashSet<>();
        List<CraftPattern<K>> distinct = new ArrayList<>(ordered.size());
        for (CraftPattern<K> pattern : ordered) {
            Integer footprint = materialFootprintByPattern.get(pattern);
            if (footprint == null || seen.add(footprint)) {
                distinct.add(pattern);
            }
        }
        diagnostics.recordEquivalentRoutesPruned(ordered.size() - distinct.size());
        return distinct;
    }

    /**
     * True when {@code y}'s own resolution is fully deterministic: at most one materially distinct
     * route, ordinary consumed inputs only (no catalysts, containers, tools or reusable-stock
     * seeds), no byproducts, and no cycle/feedback bookkeeping. Such a node never benefits from the
     * branching search, so its demand can be folded into an aggregate sweep. Descendants are NOT
     * required to be aggregable — a sweep hands their aggregated demand back to {@link #obtain}.
     */
    private boolean isAggregable(K y) {
        if (seedOrderedDependencyCone.contains(y)) {
            return false;
        }
        Boolean cached = aggregableMemo.get(y);
        if (cached != null) {
            return cached;
        }
        boolean result = computeAggregable(y);
        aggregableMemo.put(y, result);
        return result;
    }

    private boolean computeAggregable(K y) {
        if (cutOutputs.contains(y)) {
            return false;
        }
        List<CraftPattern<K>> ps = patternsByOutput.getOrDefault(y, List.of());
        if (ps.isEmpty()) {
            return false; // raw leaf: obtain()'s ordinary path already handles it in O(1)
        }
        if (ps.size() > 1 && distinctMaterialBranches(capacityOrder(y)).size() > 1) {
            return false;
        }
        for (CraftPattern<K> r : ps) {
            if (!r.byproducts().isEmpty()
                    || suppressedPositiveFeedbackOutputs.containsKey(r)
                    || feedbackSeedBootstraps.containsKey(r)
                    || feedbackSeedConverters.containsKey(r)) {
                return false;
            }
            for (CraftInput<K> in : r.inputs()) {
                if (in.returned() || in.remainder() != null || in.reusableStockSource() != null) {
                    return false;
                }
                // A byproduct-feedable input depends on sibling firing order, which the
                // topologically ordered sweep would change; leave such nodes to the recursion.
                if (byproductFeedableKeys().contains(in.key())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Keys some reachable pattern emits as a byproduct; consumers of these stay on the recursion. */
    private Set<K> byproductFeedableKeys() {
        if (byproductFeedableKeys == null) {
            Set<K> keys = new HashSet<>();
            for (List<CraftPattern<K>> patterns : patternsByOutput.values()) {
                for (CraftPattern<K> pattern : patterns) {
                    for (CraftOutput<K> out : pattern.byproducts()) {
                        keys.add(out.key());
                    }
                }
            }
            byproductFeedableKeys = keys;
        }
        return byproductFeedableKeys;
    }

    /**
     * Resolves the cone under an uncontended node by one topological demand-aggregation sweep.
     * Demand for every item is summed across all of its consumers before the item is expanded, so a
     * node shared by many parents is fired exactly once per sweep instead of once per root-to-node
     * path (which is exponential in chain depth). Contended or otherwise non-aggregable descendants
     * are handed their <em>total</em> aggregated demand through an ordinary {@link #obtain} boundary
     * call, confining the branching search to the contended subgraph. All mutations go through the
     * trail-logged helpers, so a speculative caller can roll the whole sweep back like any branch.
     *
     * <p>{@code x}'s own gross demand, seed reservations and pool draw were already performed by the
     * calling {@link #obtain}.
     */
    private long obtainAggregate(K x, long d, boolean commitFailure) {
        Map<K, Long> need = new HashMap<>();
        need.put(x, d);
        long unmet = 0L;
        boolean reached = false;
        List<K> aggregateOrder = activeReplayOrder.isEmpty()
                ? preparedGraph.order
                : activeReplayOrder;
        for (K y : aggregateOrder) {
            PlanningCancellation.check();
            if (!reached) {
                if (!y.equals(x)) continue;
                reached = true;
            }
            long dy = need.getOrDefault(y, 0L);
            if (dy <= 0) {
                continue;
            }
            if (!y.equals(x)) {
                bump(grossDemand, y, dy);
                reserveSelfSeed(y);
                reserveFeedbackSeedOutput(y, dy);
                dy -= drawPools(y, dy);
                if (dy <= 0) {
                    continue;
                }
                List<CraftPattern<K>> psy = patternsByOutput.getOrDefault(y, List.of());
                if (psy.isEmpty()) {
                    unmet = Sat.add(unmet, dy);
                    if (!commitFailure) {
                        return unmet; // shortfall: the speculative caller rolls the sweep back
                    }
                    addMissing(y, dy);
                    continue;
                }
                if (!isAggregable(y)) {
                    depth++;
                    long u;
                    try {
                        u = obtain(y, dy, commitFailure);
                    } finally {
                        depth--;
                    }
                    unmet = Sat.add(unmet, u);
                    if (!commitFailure && (u > 0 || searchBudget.exhausted()
                            || diagnostics.resolutionExhausted())) {
                        return unmet;
                    }
                    continue;
                }
            }
            List<CraftPattern<K>> psy = patternsByOutput.getOrDefault(y, List.of());
            CraftPattern<K> r = psy.size() == 1 ? psy.get(0) : capacityOrder(y).get(0);
            if (processed < Integer.MAX_VALUE) {
                processed++;
            }
            long times = Sat.ceilDiv(dy, r.outputAmount());
            bumpFiring(r, times);
            for (CraftInput<K> in : r.inputs()) {
                need.merge(in.key(), in.unitsFor(times), Sat::add);
            }
            long surplus = Sat.mul(times, r.outputAmount()) - dy;
            if (surplus > 0) {
                bump(bpPool, y, surplus);
            }
        }
        return unmet;
    }

    private long commitBudgetFallback(K x, long d) {
        if (!fallbackBudget.tryConsume()) {
            addMissing(x, d);
            return d;
        }
        List<CraftPattern<K>> routes = distinctMaterialBranches(capacityOrder(x));
        if (routes.isEmpty()) {
            addMissing(x, d);
            return d;
        }
        return commitBestEffort(routes, x, d);
    }

    private long commitBestEffort(List<CraftPattern<K>> ps, K x, long d) {
        CraftPattern<K> selected = diagnosticRoute(d, ps, Map.of());
        return fire(x, selected, d, false);
    }

    /**
     * Fire {@code r} enough times to make {@code d} of {@code x}, obtaining its inputs recursively and
     * injecting outputs (surplus + byproducts) into the pool.
     *
     * @param search if true, abort accounting is meaningful: returns total input shortfall so the
     *               caller can decide to roll back and try another recipe.
     * @return input shortfall (0 means this recipe fully satisfied d).
     */
    private long fire(K x, CraftPattern<K> r, long d, boolean search) {
        PlanningCancellation.check();
        long entryMissing = missingTotal;
        long times = Sat.ceilDiv(d, r.outputAmount());
        bumpFiring(r, times);

        long inputUnmet = 0;
        for (CraftInput<K> in : r.inputs()) {
            PlanningCancellation.check();
            long amt = in.unitsFor(times); // closed form per flavour
            long unmet;
            ReusableSeedAcquisition reusableAcquisition = null;
            if (in.reusableStockSource() != null) {
                reusableAcquisition = obtainReusableSeed(r, in, amt, search);
                unmet = reusableAcquisition.unmet();
            } else if (isSelfReturnedSeed(r, in)) {
                long obtained = drawReservedSelfSeed(in.key(), amt);
                if (obtained < amt) {
                    obtained = Sat.add(obtained, drawPools(in.key(), amt - obtained));
                }
                long stillNeeded = amt - obtained;
                unmet = stillNeeded > 0
                        ? craftSelfSeedFromAlternative(in.key(), stillNeeded, r)
                        : 0L;
                if (!search && unmet > 0) addMissing(in.key(), unmet);
            } else {
                depth++;
                try {
                    unmet = obtain(in.key(), amt, !search);
                } finally {
                    depth--;
                }
            }
            inputUnmet = Sat.add(inputUnmet, unmet);
            if (search && (searchBudget.exhausted() || diagnostics.resolutionExhausted())) {
                return inputUnmet;
            }
            if (in.returned() && in.uses() == CraftInput.INFINITE_USES) {
                // true catalyst/container: the seed is handed back, net consumption zero —
                // return what we actually got into the pool for reuse downstream. A finite-use
                // tool is degraded (consumed) by these firings, so nothing goes back.
                long returned = amt - unmet;
                if (returned > 0) {
                    if (in.reusableStockSource() != null) {
                        var source = in.reusableStockSource();
                        var route = new ReusableStockRouteKey<K>(source, in.key());
                        if (reusableAcquisition.sharedReturnable() > 0) {
                            bump(reusablePool,
                                    new ReusableStockKey<>(source.poolScope(), in.key()),
                                    reusableAcquisition.sharedReturnable());
                        }
                        if (reusableAcquisition.privateReturnable() > 0) {
                            bump(reusablePrivatePool, route,
                                    reusableAcquisition.privateReturnable());
                        }
                    } else {
                        bump(bpPool, in.key(), returned);
                    }
                }
            }
            if (search && (inputUnmet > 0 || missingTotal > entryMissing)) {
                return inputUnmet; // a shortfall appeared; bail early, the caller will roll back
            }
        }

        long produced = Sat.mul(times, r.outputAmount());
        long surplus = produced - d;
        if (surplus > 0) {
            bump(bpPool, x, surplus);
        }
        for (CraftOutput<K> out : r.byproducts()) {
            if (mayReuseByproduct(r, out.key())) {
                bump(bpPool, out.key(), Sat.mul(out.amount(), times));
            }
        }
        return inputUnmet;
    }

    /**
     * Draws a reusable seed only through its logical loop pool. A pool first reuses its own returned
     * state, then borrows from the shared physical host inventory, and finally falls back to normal
     * network stock/crafting. Ordinary recipes can never see either private layer.
     */
    private ReusableSeedAcquisition obtainReusableSeed(
            CraftPattern<K> pattern, CraftInput<K> input, long amount, boolean search) {
        var source = input.reusableStockSource();
        if (source == null || amount <= 0) {
            return new ReusableSeedAcquisition(Math.max(0L, amount), 0L, 0L);
        }

        var route = new ReusableStockRouteKey<K>(source, input.key());
        long fromPrivate = Math.min(amount, get(reusablePrivatePool, route));
        if (fromPrivate > 0) {
            put(reusablePrivatePool, route, get(reusablePrivatePool, route) - fromPrivate);
        }

        long remaining = amount - fromPrivate;
        var poolKey = new ReusableStockKey<K>(source.poolScope(), input.key());
        long fromPool = Math.min(remaining, get(reusablePool, poolKey));
        if (fromPool > 0) {
            put(reusablePool, poolKey, get(reusablePool, poolKey) - fromPool);
        }

        remaining -= fromPool;
        long borrowedExact = 0L;
        long borrowedPrivate = 0L;
        if (remaining > 0) {
            var borrowed = borrowReusableStock(source, input.key(), remaining);
            if (borrowed.amount() > 0) {
                borrowedExact = borrowed.pinnedExactAmount();
                borrowedPrivate = borrowed.amount() - borrowedExact;
                remaining -= borrowed.amount();
            }
        }

        long externalExact = 0L;
        if (remaining > 0 && isSelfReturnedSeed(pattern, input)) {
            // A normal-network seed is reserved before ordinary demand can consume the same key.
            // Host stock still has priority; this path is only the fallback when the private host
            // could not provide the complete bootstrap state.
            long reserved = drawReservedSelfSeed(input.key(), remaining);
            remaining -= reserved;
            externalExact = reserved;
        }
        if (remaining > 0 && isFeedbackSeed(pattern, input)) {
            long bootstrapped = consumeFeedbackSeedBootstrap(pattern, input, remaining);
            remaining -= bootstrapped;
            externalExact = Sat.add(externalExact, bootstrapped);
        }
        if (remaining <= 0) {
            return new ReusableSeedAcquisition(
                    0L,
                    Sat.add(Sat.add(fromPool, borrowedExact), externalExact),
                    Sat.add(fromPrivate, borrowedPrivate));
        }
        long unmet;
        long externalRequest = remaining;
        if (isSelfReturnedSeed(pattern, input)) {
            unmet = craftSelfSeedFromAlternative(input.key(), remaining, pattern);
            if (unmet > 0) addMissing(input.key(), unmet);
        } else if (isFeedbackSeed(pattern, input)) {
            // Following this key recursively would reopen the ancestor request that this exact
            // two-node bootstrap closes. If neither host stock nor the held output token sufficed,
            // report the seed itself as missing instead of recursing until the depth guard.
            unmet = remaining;
            if (!search) addMissing(input.key(), unmet);
        } else {
            depth++;
            try {
                unmet = obtain(input.key(), remaining, !search);
            } finally {
                depth--;
            }
        }
        externalExact = Sat.add(externalExact, externalRequest - unmet);
        return new ReusableSeedAcquisition(
                unmet,
                Sat.add(Sat.add(fromPool, borrowedExact), externalExact),
                Sat.add(fromPrivate, borrowedPrivate));
    }

    /**
     * Adds as much demand as the global physical-variant matching problem can satisfy. Every probe
     * re-solves all still-private routes together, so an earlier fuzzy request may be reassigned when
     * a later, more constrained request arrives. An exact allocation is removed from that rematchable
     * set as soon as its returned catalyst is exposed as shared credit; future probes subtract the
     * pinned physical units first. This keeps route matching order-independent without invalidating a
     * shared credit that a later pattern may already have consumed.
     */
    private BorrowedReusableSeed borrowReusableStock(
            ReusableStockSource source, K plannedKey, long requested) {
        if (requested <= 0) return new BorrowedReusableSeed(0L, 0L);
        var route = new ReusableStockRouteKey<K>(source, plannedKey);
        long existing = get(reusableBorrowedDemand, route);

        long low = 0L;
        long high = requested;
        if (!isReusableDemandFeasible(route, addNonNegative(existing, high))) {
            while (low < high) {
                long distance = high - low;
                long middle = low + (distance >>> 1) + (distance & 1L);
                if (isReusableDemandFeasible(route, addNonNegative(existing, middle))) {
                    low = middle;
                } else {
                    high = middle - 1L;
                }
            }
        } else {
            low = high;
        }
        if (low <= 0) return new BorrowedReusableSeed(0L, 0L);

        var demands = new HashMap<>(reusableBorrowedDemand);
        demands.put(route, addNonNegative(existing, low));
        var allocation = ReusableStockMatcher.allocate(
                availableReusableStock(), demands,
                candidate -> graph.reusableStockCandidates(candidate.source(), candidate.plannedKey()));
        if (!allocation.feasible()) {
            throw new IllegalStateException("feasible reusable-stock probe produced no allocation");
        }

        var matchedUsage = reusableUsage(allocation);
        var exactUsage = new ReusableStockUsageKey<K>(
                source.storageScope(), source.poolScope(), source.routingScope(),
                plannedKey, plannedKey);
        long pinnedExact = Math.min(low, get(matchedUsage, exactUsage));
        if (pinnedExact > 0) {
            put(pinnedExactReusableStock, exactUsage,
                    Sat.add(get(pinnedExactReusableStock, exactUsage), pinnedExact));
        }
        put(reusableBorrowedDemand, route, demands.get(route) - pinnedExact);

        // Removing the just-pinned exact edge and the same amount of route demand preserves the
        // feasible residual allocation. Re-solving keeps every still-private fuzzy assignment free
        // to move while the exposed exact shared credit is permanently excluded from host supply.
        allocation = ReusableStockMatcher.allocate(
                availableReusableStock(), reusableBorrowedDemand,
                candidate -> graph.reusableStockCandidates(candidate.source(), candidate.plannedKey()));
        if (!allocation.feasible()) {
            throw new IllegalStateException("pinning an exact reusable allocation broke residual matching");
        }
        matchedUsage = reusableUsage(allocation);
        var desiredUsage = new HashMap<ReusableStockUsageKey<K>, Long>(pinnedExactReusableStock);
        for (var entry : matchedUsage.entrySet()) {
            desiredUsage.merge(entry.getKey(), entry.getValue(), CraftPlannerV2::addNonNegative);
        }
        replaceTracked(usedReusableStock, desiredUsage);
        return new BorrowedReusableSeed(low, pinnedExact);
    }

    private Map<ReusableStockUsageKey<K>, Long> reusableUsage(
            ReusableStockMatcher.Result<K> allocation) {
        var desiredUsage = new HashMap<ReusableStockUsageKey<K>, Long>();
        for (var entry : allocation.allocation().entrySet()) {
            var allocationKey = entry.getKey();
            var allocationRoute = allocationKey.route();
            var allocationSource = allocationRoute.source();
            var usage = new ReusableStockUsageKey<K>(
                    allocationSource.storageScope(),
                    allocationSource.poolScope(),
                    allocationSource.routingScope(),
                    allocationRoute.plannedKey(),
                    allocationKey.actualKey());
            desiredUsage.merge(usage, entry.getValue(), CraftPlannerV2::addNonNegative);
        }
        return desiredUsage;
    }

    /** Physical host snapshot with exact shared credits removed from future max-flow probes. */
    private Map<ReusableStockKey<K>, Long> availableReusableStock() {
        var available = new HashMap<ReusableStockKey<K>, Long>(graph.reusableStock());
        for (var pinned : pinnedExactReusableStock.entrySet()) {
            var physical = new ReusableStockKey<K>(
                    pinned.getKey().storageScope(), pinned.getKey().actualKey());
            long left = get(available, physical) - pinned.getValue();
            if (left > 0) available.put(physical, left);
            else available.remove(physical);
        }
        return available;
    }

    private boolean isReusableDemandFeasible(ReusableStockRouteKey<K> route, long routeDemand) {
        var demands = new HashMap<>(reusableBorrowedDemand);
        if (routeDemand > 0) demands.put(route, routeDemand);
        return ReusableStockMatcher.allocate(
                availableReusableStock(), demands,
                candidate -> graph.reusableStockCandidates(candidate.source(), candidate.plannedKey()))
                .feasible();
    }

    private <T> void replaceTracked(Map<T, Long> target, Map<T, Long> replacement) {
        var keys = new HashSet<T>();
        keys.addAll(target.keySet());
        keys.addAll(replacement.keySet());
        for (var key : keys) {
            long next = get(replacement, key);
            if (get(target, key) != next) {
                put(target, key, next);
            }
        }
    }

    private static long addNonNegative(long left, long right) {
        return left >= Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private record ReusableSeedAcquisition(
            long unmet, long sharedReturnable, long privateReturnable) {
    }

    private record BorrowedReusableSeed(long amount, long pinnedExactAmount) {
    }

    private static <K> boolean isSelfReturnedSeed(CraftPattern<K> pattern, CraftInput<K> input) {
        return input.returned()
                && input.uses() == CraftInput.INFINITE_USES
                && pattern.output().equals(input.key());
    }

    private boolean isFeedbackSeed(CraftPattern<K> pattern, CraftInput<K> input) {
        return feedbackSeedBootstrap(pattern, input) != null;
    }

    private FeedbackSeedBootstrap<K> feedbackSeedBootstrap(
            CraftPattern<K> pattern, CraftInput<K> input) {
        return feedbackSeedBootstrap(pattern, input, capacity);
    }

    private FeedbackSeedBootstrap<K> feedbackSeedBootstrap(
            CraftPattern<K> pattern,
            CraftInput<K> input,
            Map<K, Long> materialCapacity) {
        FeedbackSeedBootstrap<K> best = null;
        long bestCapacity = -1L;
        long bestStateCost = Long.MAX_VALUE;
        for (FeedbackSeedBootstrap<K> bootstrap
                : feedbackSeedBootstraps.getOrDefault(pattern, List.of())) {
            if (bootstrap.seedInput() != input) continue;
            long candidateCapacity =
                    feedbackBootstrapSeedCapacity(bootstrap, materialCapacity);
            long candidateStateCost = bootstrap.outputUnitsFor(input.amount());
            if (best == null
                    || candidateCapacity > bestCapacity
                    || (candidateCapacity == bestCapacity
                    && candidateStateCost < bestStateCost)) {
                best = bootstrap;
                bestCapacity = candidateCapacity;
                bestStateCost = candidateStateCost;
            }
        }
        return best;
    }

    /**
     * Seed output supported by a converter's non-loop inputs. The loop-state input is reserved
     * separately, so including it here would make the feedback edge prove its own capacity.
     */
    private long feedbackBootstrapSeedCapacity(
            FeedbackSeedBootstrap<K> bootstrap, Map<K, Long> materialCapacity) {
        CraftInput<K> seed = bootstrap.seedInput();
        long availableLoopState = graph.stock(bootstrap.loopPattern().output());
        if (seed.reusableStockSource() != null) {
            availableLoopState = Sat.add(
                    availableLoopState,
                    graph.reusableStock(
                            seed.reusableStockSource().storageScope(),
                            bootstrap.loopPattern().output()));
        }
        long firings = bootstrap.converterInput().firingsFrom(availableLoopState);
        for (CraftInput<K> auxiliary : bootstrap.converter().inputs()) {
            if (auxiliary == bootstrap.converterInput()) continue;
            long available = materialCapacity == null
                    ? graph.stock(auxiliary.key())
                    : materialCapacity.getOrDefault(
                            auxiliary.key(), graph.stock(auxiliary.key()));
            firings = Math.min(firings, auxiliary.firingsFrom(available));
            if (firings == 0) return 0L;
        }
        return Sat.mul(firings, bootstrap.converter().outputAmount());
    }

    private boolean isFeedbackConverterInput(CraftPattern<K> pattern, CraftInput<K> input) {
        for (FeedbackSeedBootstrap<K> bootstrap
                : feedbackSeedConverters.getOrDefault(pattern, List.of())) {
            if (bootstrap.converterInput() == input) return true;
        }
        return false;
    }

    private static <K> boolean hasSelfReturnedSeed(CraftPattern<K> pattern) {
        for (CraftInput<K> input : pattern.inputs()) {
            if (isSelfReturnedSeed(pattern, input)) return true;
        }
        return false;
    }

    /** Crafts only the catalyst seed via a non-self alternative; the gain macro itself is excluded. */
    private long craftSelfSeedFromAlternative(K key, long amount, CraftPattern<K> excluded) {
        List<CraftPattern<K>> alternatives = new ArrayList<>();
        for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(key, List.of())) {
            if (pattern != excluded && !hasSelfReturnedSeed(pattern)) alternatives.add(pattern);
        }
        alternatives.sort((a, b) -> Long.compare(capacityScore(b), capacityScore(a)));
        boolean competing = alternatives.size() > 1;
        for (CraftPattern<K> alternative : alternatives) {
            boolean admitted = competing
                    ? searchBudget.tryConsume()
                    : diagnostics.tryConsumeResolutionWork();
            if (!admitted) {
                // The outer committed route will retain this deterministic dependency tree; a
                // speculative parent will roll the same writes back at its own mark.
                if (!fallbackBudget.tryConsume()) {
                    return amount;
                }
                if (!consumeFixedBootstrapOutput(alternative, amount)) {
                    continue;
                }
                fire(key, alternative, amount, false);
                return 0L;
            }
            int mark = trail.size();
            long beforeMissing = missingTotal;
            if (!consumeFixedBootstrapOutput(alternative, amount)) {
                rollback(mark);
                continue;
            }
            long unmet = fire(key, alternative, amount, true);
            if (searchBudget.exhausted() || diagnostics.resolutionExhausted()) {
                rollback(mark);
                return amount;
            }
            if (unmet == 0 && missingTotal == beforeMissing) return 0L;
            rollback(mark);
        }
        return amount;
    }

    private boolean consumeFixedBootstrapOutput(CraftPattern<K> pattern, long amount) {
        return consumeFixedBootstrapFirings(
                pattern, Sat.ceilDiv(amount, pattern.outputAmount()));
    }

    /** Charges a startup-only firing against the exact component before ordinary demand sees it. */
    private boolean consumeFixedBootstrapFirings(CraftPattern<K> pattern, long times) {
        if (!fixedFallbackItems.contains(pattern.output())) {
            return true;
        }
        long quota = get(fixedFiringQuota, pattern);
        if (times <= 0L || quota < times) {
            return false;
        }
        put(fixedFiringQuota, pattern, quota - times);
        return true;
    }

    /** Holds a self-output catalyst aside before ordinary demand can consume it as finished output. */
    private void reserveSelfSeed(K key) {
        long required = 0L;
        for (CraftPattern<K> pattern : patternsByOutput.getOrDefault(key, List.of())) {
            for (CraftInput<K> input : pattern.inputs()) {
                if (isSelfReturnedSeed(pattern, input)) {
                    required = Math.max(required, input.amount());
                }
            }
        }
        long alreadyReserved = get(reservedSelfSeeds, key);
        long additional = Math.max(0L, required - alreadyReserved);
        if (additional <= 0) return;
        long available = get(stockLeft, key);
        long held = Math.min(additional, available);
        if (held > 0) {
            put(stockLeft, key, available - held);
            put(reservedSelfSeeds, key, Sat.add(alreadyReserved, held));
        }
    }

    private long drawReservedSelfSeed(K key, long amount) {
        long available = get(reservedSelfSeeds, key);
        long drawn = Math.min(amount, available);
        if (drawn > 0) {
            put(reservedSelfSeeds, key, available - drawn);
            put(usedStock, key, Sat.add(get(usedStock, key), drawn));
        }
        return drawn;
    }

    /**
     * Holds the physical output-state token only when ordinary demand would exhaust that state and the
     * feedback loop is the currently preferred way to cover the shortfall. The held item is not charged
     * to {@code usedStock} until the explicit converter actually consumes it.
     */
    private void reserveFeedbackSeedOutput(K key, long demand) {
        long immediatelyAvailable = Sat.add(get(bpPool, key), get(stockLeft, key));
        if (demand <= immediatelyAvailable) return;

        List<CraftPattern<K>> patterns = patternsByOutput.getOrDefault(key, List.of());
        if (patterns.isEmpty()) return;
        CraftPattern<K> preferred = capacityOrder(key).get(0);
        List<FeedbackSeedBootstrap<K>> bootstraps = feedbackSeedBootstraps.get(preferred);
        if (bootstraps == null || bootstraps.isEmpty()) return;

        Map<FeedbackSeedBootstrap<K>, Long> additionalBySeed = new HashMap<>();
        long totalAdditional = 0L;
        for (CraftInput<K> seed : preferred.inputs()) {
            FeedbackSeedBootstrap<K> bootstrap =
                    feedbackSeedBootstrap(preferred, seed);
            if (bootstrap == null) continue;
            long hostAvailable = graph.reusableStock(seed.reusableStockSource(), seed.key());
            long seedShortfall = Math.max(0L, seed.amount() - hostAvailable);
            long required = bootstrap.outputUnitsFor(seedShortfall);
            long alreadyReserved = get(reservedFeedbackSeedOutputs, bootstrap);
            long additional = Math.max(0L, required - alreadyReserved);
            if (additional > 0) {
                additionalBySeed.put(bootstrap, additional);
                totalAdditional = Sat.add(totalAdditional, additional);
            }
        }
        if (totalAdditional <= 0) return;
        int mark = trail.size();
        long availableStock = get(stockLeft, key);
        for (Map.Entry<FeedbackSeedBootstrap<K>, Long> entry : additionalBySeed.entrySet()) {
            FeedbackSeedBootstrap<K> bootstrap = entry.getKey();
            long additional = entry.getValue();
            long ordinary = Math.min(additional, availableStock);
            availableStock -= ordinary;
            long hostNeeded = additional - ordinary;
            long hostBorrowed = 0L;
            if (hostNeeded > 0) {
                var borrowed = borrowReusableStock(
                        bootstrap.bootstrapSource(), key, hostNeeded);
                hostBorrowed = borrowed.amount();
                if (hostBorrowed < hostNeeded) {
                    rollback(mark);
                    return;
                }
            }
            put(reservedFeedbackSeedOutputs, bootstrap,
                    Sat.add(get(reservedFeedbackSeedOutputs, bootstrap), additional));
            if (hostBorrowed > 0) {
                put(reservedFeedbackSeedHostOutputs, bootstrap,
                        Sat.add(get(reservedFeedbackSeedHostOutputs, bootstrap), hostBorrowed));
            }
        }
        put(stockLeft, key, availableStock);
    }

    /**
     * Executes the proven ordinary converter against the held output-state stock and exposes its
     * product as the loop's reusable seed. The converter firing is recorded normally, so AE2 schedules
     * the same real pattern once for bootstrap plus however many times final output requires.
     */
    private long consumeFeedbackSeedBootstrap(
            CraftPattern<K> pattern, CraftInput<K> input, long requested) {
        FeedbackSeedBootstrap<K> bootstrap = feedbackSeedBootstrap(pattern, input);
        if (bootstrap == null || requested <= 0) return 0L;

        long firings = Sat.ceilDiv(requested, bootstrap.converter().outputAmount());
        long requiredOutput = bootstrap.converterInput().unitsFor(firings);
        long reserved = get(reservedFeedbackSeedOutputs, bootstrap);
        if (requiredOutput > reserved) return 0L;

        // Auxiliary converter inputs (e.g. the reaction chamber's water) are ordinary materials
        // from outside the cycle; obtain them for the bootstrap firings before committing, and
        // abandon the bootstrap cleanly when any of them cannot be covered.
        int mark = trail.size();
        long beforeMissing = missingTotal;
        if (!consumeFixedBootstrapFirings(bootstrap.converter(), firings)) {
            rollback(mark);
            return 0L;
        }
        for (CraftInput<K> auxiliary : bootstrap.converter().inputs()) {
            if (auxiliary == bootstrap.converterInput()) continue;
            long unmet;
            depth++;
            try {
                unmet = obtain(auxiliary.key(), auxiliary.unitsFor(firings), false);
            } finally {
                depth--;
            }
            if (unmet > 0 || missingTotal > beforeMissing) {
                rollback(mark);
                return 0L;
            }
        }

        put(reservedFeedbackSeedOutputs, bootstrap, reserved - requiredOutput);
        long reservedFromHost = get(reservedFeedbackSeedHostOutputs, bootstrap);
        long fromHost = Math.min(requiredOutput, reservedFromHost);
        if (fromHost > 0) {
            put(reservedFeedbackSeedHostOutputs, bootstrap, reservedFromHost - fromHost);
        }
        long fromOrdinaryStock = requiredOutput - fromHost;
        if (fromOrdinaryStock > 0) {
            bump(usedStock, pattern.output(), fromOrdinaryStock);
        }
        bump(grossDemand, pattern.output(), requiredOutput);
        bumpFiring(bootstrap.converter(), firings);

        long produced = Sat.mul(firings, bootstrap.converter().outputAmount());
        long supplied = Math.min(requested, produced);
        long surplus = produced - supplied;
        if (surplus > 0) {
            bump(bpPool, input.key(), surplus);
        }
        return supplied;
    }

    /** Draw up to {@code d} of {@code x}: byproduct pool first, then inventory (counted as used stock). */
    private long drawPools(K x, long d) {
        long got = 0;
        long bp = Math.min(d, get(bpPool, x));
        if (bp > 0) {
            put(bpPool, x, get(bpPool, x) - bp);
            got += bp;
            d -= bp;
        }
        long st = Math.min(d, get(stockLeft, x));
        if (st > 0) {
            put(stockLeft, x, get(stockLeft, x) - st);
            put(usedStock, x, Sat.add(get(usedStock, x), st));
            got += st;
        }
        return got;
    }

    // ---- trail-logged mutation helpers -----------------------------------------------------------

    private static <T> long get(Map<T, Long> m, T k) {
        Long v = m.get(k);
        return v == null ? 0L : v;
    }

    private <T> void put(Map<T, Long> m, T k, long newVal) {
        Long old = m.get(k);
        long oldAvailabilityState = availabilityState;
        boolean tracksAvailability = tracksAvailability(m);
        trail.push(() -> {
            if (old == null) {
                m.remove(k);
            } else {
                m.put(k, old);
            }
            if (tracksAvailability) {
                availabilityState = oldAvailabilityState;
            }
        });
        if (tracksAvailability) {
            availabilityState = ++nextAvailabilityState;
        }
        if (newVal == 0) {
            m.remove(k);
        } else {
            m.put(k, newVal);
        }
    }

    private boolean tracksAvailability(Map<?, Long> map) {
        return map == bpPool
                || map == stockLeft
                || map == reservedSelfSeeds
                || map == reservedFeedbackSeedOutputs
                || map == reservedFeedbackSeedHostOutputs
                || map == reusableBorrowedDemand
                || map == reusablePrivatePool
                || map == reusablePool
                || map == pinnedExactReusableStock
                || map == fixedFiringQuota;
    }

    private <T> void bump(Map<T, Long> m, T k, long delta) {
        if (delta == 0) {
            return;
        }
        put(m, k, Sat.add(get(m, k), delta));
    }

    private void addMissing(K k, long amt) {
        if (amt <= 0) {
            return;
        }
        put(missing, k, Sat.add(get(missing, k), amt));
        long old = missingTotal;
        trail.push(() -> missingTotal = old);
        missingTotal = Sat.add(missingTotal, amt);
    }

    private void bumpFiring(CraftPattern<K> r, long delta) {
        Long old = firings.get(r);
        trail.push(() -> {
            if (old == null) {
                firings.remove(r);
            } else {
                firings.put(r, old);
            }
        });
        firings.put(r, Sat.add(old == null ? 0L : old, delta));
    }

    private void rollback(int mark) {
        while (trail.size() > mark) {
            trail.pop().run();
        }
    }

    private record MaterialLeaf(Object key) {
    }

    private record MaterialTerm(int footprint, long amount) {
    }

    private record MaterialRecipe(long outputAmount, List<MaterialTerm> inputs) {
    }

    private record SearchFailure<K>(K key, long amount, long availabilityState, int depth) {
    }

    private record ConsumableProofState<K>(K key, long availabilityState) {
    }

    private record LowWidthAnalysis<K>(
            List<LowWidthComponent<K>> components,
            long optimisticTargetCapacity,
            List<K> replayOrder) {
    }

    private record LowWidthComponent<K>(
            List<K> items,
            List<CraftPattern<K>> patterns,
            Map<K, Long> externalDemand,
            Map<K, Long> externalSupply,
            Map<CraftPattern<K>, Set<K>> reusableByproducts,
            int separatorWidth,
            boolean hasSpeculativeByproducts,
            boolean exactSolverEligible,
            boolean requiresOrderedValidation,
            boolean infeasibilityProof) {

        private boolean reusesByproduct(CraftPattern<K> pattern, K key) {
            return reusableByproducts.getOrDefault(pattern, Set.of()).contains(key);
        }

        private boolean reusesByproducts() {
            // Supply emitted by a deterministic pattern just outside this component is still
            // byproduct-sensitive: the fixed vector is valid only if full ordered validation can
            // actually expose that boundary credit before this component consumes it.
            return !reusableByproducts.isEmpty() || !externalSupply.isEmpty();
        }
    }

    /** One reusable startup reserve in either ordinary network stock or a host-private loop pool. */
    private record SeedReserveKey<K>(K key, Object storageScope, Object poolScope) {
    }

    private record SeedActivation<K>(
            CraftPattern<K> pattern, CraftInput<K> input, int activationVariable) {
    }

    /** Mutable only while one low-width matrix is being compiled. */
    private static final class SeedGroupModel<K> {
        private final SeedReserveKey<K> key;
        private final List<SeedActivation<K>> requirements = new ArrayList<>();
        private final Set<K> acceptedHostKeys = new LinkedHashSet<>();
        private int ordinaryReserveVariable = -1;
        private int hostReserveVariable = -1;

        private SeedGroupModel(SeedReserveKey<K> key) {
            this.key = key;
        }
    }

    private record ByproductSchedule<K>(
            List<K> order,
            Map<CraftPattern<K>, Set<K>> reusableByproducts,
            Map<CraftPattern<K>, Set<K>> speculativeByproducts,
            Set<K> unsafeItems) {

        private boolean reuses(CraftPattern<K> pattern, K key) {
            return reusableByproducts.getOrDefault(pattern, Set.of()).contains(key);
        }

        private boolean models(CraftPattern<K> pattern, K key) {
            return reuses(pattern, key)
                    || isSpeculative(pattern, key);
        }

        private boolean isSpeculative(CraftPattern<K> pattern, K key) {
            return speculativeByproducts.getOrDefault(pattern, Set.of()).contains(key);
        }

        private boolean hasSpeculativeByproduct(CraftPattern<K> pattern) {
            return !speculativeByproducts.getOrDefault(pattern, Set.of()).isEmpty();
        }

        private boolean hasRelevantByproducts() {
            return !reusableByproducts.isEmpty()
                    || !speculativeByproducts.isEmpty()
                    || !unsafeItems.isEmpty();
        }
    }

    private interface ScheduleNode<K> {
    }

    private record ScheduleItem<K>(K key) implements ScheduleNode<K> {
    }

    private record ByproductBarrier<K>(K key) implements ScheduleNode<K> {
    }

    private record ByproductLink<K>(CraftPattern<K> pattern, K output, K key) {
    }

    private record LowWidthSolve<K>(
            BoundedIntegerLinearSolver.Status status,
            Map<CraftPattern<K>, Long> firings,
            Set<CraftPattern<K>> decisionSupport,
            int integerNodes) {

        private static <K> LowWidthSolve<K> unsupported() {
            return unsupported(0);
        }

        private static <K> LowWidthSolve<K> unsupported(int nodes) {
            return new LowWidthSolve<>(
                    BoundedIntegerLinearSolver.Status.INVALID_INPUT, null, Set.of(), nodes);
        }
    }

    private record SolvedLowWidthComponent<K>(
            LowWidthComponent<K> component, LowWidthSolve<K> solve) {
    }

    /** Immutable output of graph discovery/cycle cutting for one priority-root orientation. */
    private static final class PreparedGraph<K> {
        private final CraftGraph<K> graph;
        private final List<K> order;
        private final Set<K> items;
        private final Set<K> cutOutputs;
        private final Map<K, List<CraftPattern<K>>> patternsByOutput;
        private final Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs;
        private final Map<CraftPattern<K>, Map<K, Long>> linearContainerBootstrapReserves;
        private final Map<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> feedbackSeedBootstraps;
        private final Map<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> feedbackSeedConverters;
        private final List<ConservativeFeedbackAnalysis.Component<K>> conservativeFeedbackComponents;
        private final List<ConservativeFeedbackAnalysis.FallbackComponent<K>>
                canonicalFeedbackFallbackComponents;
        private final boolean seedOrdered;
        private final Set<K> seedOrderedDependencyCone;
        private final Map<K, Long> capacity;
        private final Map<CraftPattern<K>, Long> capacityScoreByPattern;
        private final Map<K, List<CraftPattern<K>>> capacityOrderByOutput;
        private final Map<CraftPattern<K>, Map<K, Long>> directRawConsumablesByPattern;
        private final Map<CraftPattern<K>, Integer> materialFootprintByPattern;
        private final ByproductSchedule<K> byproductSchedule;
        private final int patternCount;
        private final int inputCount;
        private final int contendedOutputCount;

        private PreparedGraph(
                CraftGraph<K> graph,
                List<K> order,
                Set<K> items,
                Set<K> cutOutputs,
                Map<K, List<CraftPattern<K>>> patternsByOutput,
                Map<CraftPattern<K>, Set<K>> suppressedPositiveFeedbackOutputs,
                Map<CraftPattern<K>, Map<K, Long>> linearContainerBootstrapReserves,
                Map<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> feedbackSeedBootstraps,
                Map<CraftPattern<K>, List<FeedbackSeedBootstrap<K>>> feedbackSeedConverters,
                List<ConservativeFeedbackAnalysis.Component<K>> conservativeFeedbackComponents,
                List<ConservativeFeedbackAnalysis.FallbackComponent<K>>
                        canonicalFeedbackFallbackComponents,
                boolean seedOrdered,
                Set<K> seedOrderedDependencyCone,
                Map<K, Long> capacity,
                Map<CraftPattern<K>, Long> capacityScoreByPattern,
                Map<K, List<CraftPattern<K>>> capacityOrderByOutput,
                Map<CraftPattern<K>, Map<K, Long>> directRawConsumablesByPattern,
                Map<CraftPattern<K>, Integer> materialFootprintByPattern,
                ByproductSchedule<K> byproductSchedule,
                int patternCount,
                int inputCount,
                int contendedOutputCount) {
            this.graph = graph;
            this.order = order;
            this.items = items;
            this.cutOutputs = cutOutputs;
            this.patternsByOutput = patternsByOutput;
            this.suppressedPositiveFeedbackOutputs = suppressedPositiveFeedbackOutputs;
            this.linearContainerBootstrapReserves = linearContainerBootstrapReserves;
            this.feedbackSeedBootstraps = feedbackSeedBootstraps;
            this.feedbackSeedConverters = feedbackSeedConverters;
            this.conservativeFeedbackComponents = List.copyOf(conservativeFeedbackComponents);
            this.canonicalFeedbackFallbackComponents =
                    List.copyOf(canonicalFeedbackFallbackComponents);
            this.seedOrdered = seedOrdered;
            this.seedOrderedDependencyCone = seedOrderedDependencyCone;
            this.capacity = capacity;
            this.capacityScoreByPattern = capacityScoreByPattern;
            this.capacityOrderByOutput = capacityOrderByOutput;
            this.directRawConsumablesByPattern = directRawConsumablesByPattern;
            this.materialFootprintByPattern = materialFootprintByPattern;
            this.byproductSchedule = byproductSchedule;
            this.patternCount = patternCount;
            this.inputCount = inputCount;
            this.contendedOutputCount = contendedOutputCount;
        }
    }

    /** Mutable counters shared by all cut-orientation runs; frozen without extra graph work. */
    private static final class DiagnosticsCollector {
        private final int reachableWorkEstimate;
        private final int configuredSearchBudget;
        private final int configuredResolutionBudget;
        private final int configuredFallbackBudget;
        private int reachableItems;
        private int reachablePatterns;
        private int inputEdges;
        private int contendedOutputs;
        private int cycleCuts;
        private boolean seedOrdered;
        private int planRuns;
        private int compiledOrientations;
        private int reusedCompilations;
        private int hotNodeVisits;
        private int dynamicCapacityEvaluations;
        private int equivalentRoutesPruned;
        private int failureMemoHits;
        private int frontierPeak;
        private int separatorWidthPeak;
        private int lowWidthAttempts;
        private int lowWidthSolved;
        private int lowWidthInfeasible;
        private int lowWidthCutoffs;
        private int lowWidthIntegerNodes;
        private boolean searchCutoff;
        private int consumedResolutionBudget;
        private boolean resolutionCutoff;
        private int consumedFallbackBudget;
        private boolean fallbackCutoff;
        private long graphCompileNanos;
        private long linearPassNanos;
        private long searchNanos;
        private final SharedCounterBudget sharedResolutionWorkBudget;
        private final SharedCounterBudget sharedFallbackWorkBudget;

        private DiagnosticsCollector(
                int reachableWorkEstimate,
                int configuredSearchBudget,
                PlanningSession<?> session) {
            this.reachableWorkEstimate = reachableWorkEstimate;
            this.configuredSearchBudget = Math.max(1, configuredSearchBudget);
            this.configuredResolutionBudget = fallbackWorkBudget(reachableWorkEstimate);
            this.configuredFallbackBudget = fallbackWorkBudget(reachableWorkEstimate);
            this.sharedResolutionWorkBudget = session.resolutionWorkBudget;
            this.sharedFallbackWorkBudget = session.fallbackWorkBudget;
        }

        private int fallbackBudgetLimit() {
            return configuredFallbackBudget;
        }

        private void recordCompilation(PreparedGraph<?> prepared, long nanos) {
            compiledOrientations = increment(compiledOrientations);
            reachableItems = Math.max(reachableItems, prepared.items.size());
            reachablePatterns = Math.max(reachablePatterns, prepared.patternCount);
            inputEdges = Math.max(inputEdges, prepared.inputCount);
            contendedOutputs = Math.max(contendedOutputs, prepared.contendedOutputCount);
            cycleCuts = Math.max(cycleCuts, prepared.cutOutputs.size());
            seedOrdered |= prepared.seedOrdered;
            graphCompileNanos = addNanos(graphCompileNanos, nanos);
        }

        private void recordCompilationReuse() {
            reusedCompilations = increment(reusedCompilations);
        }

        private void recordPlanRun() {
            planRuns = increment(planRuns);
        }

        private void recordHotNodeVisit() {
            hotNodeVisits = increment(hotNodeVisits);
        }

        private void recordDynamicCapacityEvaluation() {
            dynamicCapacityEvaluations = increment(dynamicCapacityEvaluations);
        }

        private void recordEquivalentRoutesPruned(int count) {
            if (count > 0) equivalentRoutesPruned = add(equivalentRoutesPruned, count);
        }

        private void recordFailureMemoHit() {
            failureMemoHits = increment(failureMemoHits);
        }

        private void recordFrontierSize(int size) {
            frontierPeak = Math.max(frontierPeak, size);
        }

        private void recordSeparatorWidth(int width) {
            separatorWidthPeak = Math.max(separatorWidthPeak, Math.max(0, width));
        }

        private void recordLowWidthAttempt() {
            lowWidthAttempts = increment(lowWidthAttempts);
        }

        private void recordLowWidthResult(
                BoundedIntegerLinearSolver.Status status, int integerNodes) {
            lowWidthIntegerNodes = add(lowWidthIntegerNodes, Math.max(0, integerNodes));
            switch (status) {
                case SOLVED -> lowWidthSolved = increment(lowWidthSolved);
                case INFEASIBLE -> lowWidthInfeasible = increment(lowWidthInfeasible);
                case BUDGET_EXHAUSTED -> lowWidthCutoffs = increment(lowWidthCutoffs);
                default -> {
                    // Unsupported/overflow/internal results simply retain the ordinary fallback.
                }
            }
        }

        private void recordLowWidthCapacityProof() {
            lowWidthInfeasible = increment(lowWidthInfeasible);
        }

        private void recordSearchCutoff() {
            searchCutoff = true;
        }

        private boolean tryConsumeResolutionWork() {
            if (consumedResolutionBudget >= configuredResolutionBudget
                    || !sharedResolutionWorkBudget.tryConsume(1)) {
                resolutionCutoff = true;
                return false;
            }
            consumedResolutionBudget = increment(consumedResolutionBudget);
            return true;
        }

        private boolean resolutionExhausted() {
            return resolutionCutoff;
        }

        private void recordFallbackWork() {
            consumedFallbackBudget = increment(consumedFallbackBudget);
        }

        private void recordFallbackCutoff() {
            fallbackCutoff = true;
        }

        private void addLinearPassNanos(long nanos) {
            linearPassNanos = addNanos(linearPassNanos, nanos);
        }

        private void addSearchNanos(long nanos) {
            searchNanos = addNanos(searchNanos, nanos);
        }

        private PlanningDiagnostics finish(long started, SearchBudget budget) {
            int consumed = budget == null ? 0 : budget.consumed();
            return new PlanningDiagnostics(
                    reachableWorkEstimate,
                    reachableItems,
                    reachablePatterns,
                    inputEdges,
                    contendedOutputs,
                    cycleCuts,
                    seedOrdered,
                    configuredSearchBudget,
                    consumed,
                    configuredResolutionBudget,
                    consumedResolutionBudget,
                    configuredFallbackBudget,
                    consumedFallbackBudget,
                    planRuns,
                    compiledOrientations,
                    reusedCompilations,
                    hotNodeVisits,
                    dynamicCapacityEvaluations,
                    equivalentRoutesPruned,
                    failureMemoHits,
                    frontierPeak,
                    searchCutoff,
                    resolutionCutoff,
                    fallbackCutoff,
                    graphCompileNanos,
                    linearPassNanos,
                    searchNanos,
                    Math.max(0L, System.nanoTime() - started),
                    separatorWidthPeak,
                    lowWidthAttempts,
                    lowWidthSolved,
                    lowWidthInfeasible,
                    lowWidthCutoffs,
                    lowWidthIntegerNodes);
        }

        private static int increment(int value) {
            return value == Integer.MAX_VALUE ? value : value + 1;
        }

        private static int add(int left, int right) {
            return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
        }

        private static long addNanos(long left, long right) {
            long nonNegativeRight = Math.max(0L, right);
            return left > Long.MAX_VALUE - nonNegativeRight
                    ? Long.MAX_VALUE
                    : left + nonNegativeRight;
        }
    }

    /**
     * Monotonic plan-wide alternative-search guard. Once a request is denied, callers stop exploring
     * alternatives and finish the current plan through a deterministic capacity-first route.
     */
    private static final class SearchBudget {
        private final int initial;
        private int remaining;
        private final SharedCounterBudget shared;
        private final DiagnosticsCollector diagnostics;
        private boolean exhausted;

        private SearchBudget(
                int work,
                SharedCounterBudget shared,
                DiagnosticsCollector diagnostics) {
            this.initial = Math.max(1, work);
            this.remaining = initial;
            this.shared = shared;
            this.diagnostics = diagnostics;
        }

        private boolean tryConsume() {
            return tryConsume(1);
        }

        private boolean tryConsume(int work) {
            int requested = Math.max(1, work);
            if (remaining < requested || !shared.tryConsume(requested)) {
                if (!exhausted) {
                    exhausted = true;
                    diagnostics.recordSearchCutoff();
                }
                return false;
            }
            remaining -= requested;
            return true;
        }

        private boolean exhausted() {
            return exhausted;
        }

        private int consumed() {
            return initial - remaining;
        }
    }

    /** Monotonic counter shared by every quantity probe in one planning session. */
    private static final class SharedCounterBudget {
        private int remaining;

        private SharedCounterBudget(int work) {
            remaining = Math.max(1, work);
        }

        private boolean tryConsume(int work) {
            PlanningCancellation.check();
            int requested = Math.max(1, work);
            if (remaining < requested) {
                return false;
            }
            remaining -= requested;
            return true;
        }
    }

    /** Bounds the deterministic tail after alternative search has stopped. */
    private static final class FallbackBudget {
        private int remaining;
        private final SharedCounterBudget shared;
        private final DiagnosticsCollector diagnostics;

        private FallbackBudget(
                int work,
                SharedCounterBudget shared,
                DiagnosticsCollector diagnostics) {
            this.remaining = Math.max(1, work);
            this.shared = shared;
            this.diagnostics = diagnostics;
        }

        private boolean tryConsume() {
            PlanningCancellation.check();
            if (remaining <= 0 || !shared.tryConsume(1)) {
                diagnostics.recordFallbackCutoff();
                return false;
            }
            remaining--;
            diagnostics.recordFallbackWork();
            return true;
        }
    }

    private record FeedbackSeedBootstrap<K>(
            CraftPattern<K> loopPattern,
            CraftInput<K> seedInput,
            CraftPattern<K> converter,
            CraftInput<K> converterInput) {

        long outputUnitsFor(long seedAmount) {
            long firings = Sat.ceilDiv(seedAmount, converter.outputAmount());
            return converterInput.unitsFor(firings);
        }

        ReusableStockSource bootstrapSource() {
            ReusableStockSource owner = seedInput.reusableStockSource();
            return new ReusableStockSource(
                    owner.storageScope(),
                    owner.poolScope(),
                    new ReusableBootstrapRoute<>(owner.routingScope(), seedInput.key()));
        }
    }

    /** Tiny union-find used only during the O(V+E) conflict-component discovery pass. */
    private static final class IntDisjointSet {
        private final int[] parent;
        private final byte[] rank;

        private IntDisjointSet(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int i = 0; i < size; i++) {
                parent[i] = i;
            }
        }

        private int find(int value) {
            int root = value;
            while (parent[root] != root) {
                root = parent[root];
            }
            while (parent[value] != value) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        private void union(int left, int right) {
            int leftRoot = find(left);
            int rightRoot = find(right);
            if (leftRoot == rightRoot) {
                return;
            }
            if (rank[leftRoot] < rank[rightRoot]) {
                parent[leftRoot] = rightRoot;
            } else if (rank[leftRoot] > rank[rightRoot]) {
                parent[rightRoot] = leftRoot;
            } else {
                parent[rightRoot] = leftRoot;
                rank[leftRoot]++;
            }
        }
    }

    private static final class FootprintInterner {
        private final Map<Object, Integer> ids = new HashMap<>();

        private int intern(Object shape) {
            Integer existing = ids.get(shape);
            if (existing != null) {
                return existing;
            }
            int id = ids.size() + 1;
            ids.put(shape, id);
            return id;
        }
    }
}

package com.moakiee.thunderbolt.core.crafting.planner;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Linear-time reachable-SCC analysis used to distinguish simple material conversions from arbitrary
 * recipe cycles. It deliberately classifies narrowly: anything that cannot be proven conservative is
 * {@link Kind#COMPLEX} and stays on the planner's existing safe target-first cut.
 */
final class CycleAnalysis<K> {

    enum Kind {
        ACYCLIC,
        PURE_CONVERSION,
        CATALYZED_CONVERSION,
        /**
         * Structurally a conversion ring (each internal pattern consumes exactly one ordinary
         * internal input, no internal byproducts), but the round-trip exchange rate is not 1 —
         * lossy or gainful. Any single orientation is still an ordinary mass-balanced DAG, so
         * reorienting it is safe: only one direction ever exists within a plan, which makes
         * round-trip amplification impossible to schedule.
         */
        LOSSY_CONVERSION,
        CATALYST_STATE,
        COMPLEX;

        boolean mayReorient() {
            return this == PURE_CONVERSION
                    || this == CATALYZED_CONVERSION
                    || this == LOSSY_CONVERSION;
        }
    }

    private static final int MAX_WEIGHT_BITS = 128;

    private final Map<K, Kind> kindByMember;
    private final Set<K> directlyReorientable;
    private final Map<K, Set<K>> membersByMember;

    private CycleAnalysis(
            Map<K, Kind> kindByMember,
            Set<K> directlyReorientable,
            Map<K, Set<K>> membersByMember) {
        this.kindByMember = Map.copyOf(kindByMember);
        this.directlyReorientable = Set.copyOf(directlyReorientable);
        this.membersByMember = Map.copyOf(membersByMember);
    }

    static <K> CycleAnalysis<K> analyze(CraftGraph<K> graph, K target) {
        Map<K, List<K>> adjacency = reachableAdjacency(graph, target);
        Map<K, List<K>> reverse = reverse(adjacency);
        List<K> finishOrder = finishOrder(adjacency);

        Map<K, Kind> kinds = new HashMap<>();
        Map<K, Set<K>> membersBySccMember = new HashMap<>();
        Set<K> assigned = new HashSet<>();
        for (int i = finishOrder.size() - 1; i >= 0; i--) {
            K root = finishOrder.get(i);
            if (!assigned.add(root)) continue;

            Set<K> members = new LinkedHashSet<>();
            Deque<K> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                K node = stack.pop();
                members.add(node);
                for (K previous : reverse.getOrDefault(node, List.of())) {
                    if (assigned.add(previous)) stack.push(previous);
                }
            }

            boolean selfLoop = members.size() == 1
                    && adjacency.getOrDefault(root, List.of()).contains(root);
            Kind kind = members.size() > 1 || selfLoop
                    ? classify(graph, members)
                    : Kind.ACYCLIC;
            if (members.size() > 1 || selfLoop) {
                Set<K> frozen = Set.copyOf(members);
                for (K member : members) membersBySccMember.put(member, frozen);
            }
            for (K member : members) kinds.put(member, kind);
        }
        return new CycleAnalysis<>(
                kinds, directlyReorientable(graph, adjacency.keySet()), membersBySccMember);
    }

    Kind kindOf(K key) {
        return kindByMember.getOrDefault(key, Kind.ACYCLIC);
    }

    /** The key's cycle members (itself included), or an empty set for acyclic nodes. */
    Set<K> membersOf(K key) {
        return membersByMember.getOrDefault(key, Set.of());
    }

    boolean mayReorient(K key) {
        return kindOf(key).mayReorient() || directlyReorientable.contains(key);
    }

    /**
     * Whether every reachable cyclic SCC is a ratio-conservative conversion SCC whose external
     * inputs can be represented by ordinary balances or catalyst-presence constraints.
     *
     * <p>This deliberately excludes lossy/gainful and stateful cycles. Callers still perform a
     * narrower pattern-level check before admitting unchanged catalysts; acyclic nodes surrounding
     * the admitted SCCs are harmless.</p>
     */
    boolean hasOnlyRankableConversionCycles() {
        boolean foundCycle = false;
        for (Kind kind : kindByMember.values()) {
            if (kind == Kind.ACYCLIC) continue;
            foundCycle = true;
            if (kind != Kind.PURE_CONVERSION && kind != Kind.CATALYZED_CONVERSION) return false;
        }
        return foundCycle;
    }

    private record Ratio(long numerator, long denominator) {
        Ratio reciprocal() {
            return new Ratio(denominator, numerator);
        }
    }

    /**
     * Finds conservative two-way material edges even when a tool's own producer pulls both endpoints
     * into a larger {@link Kind#COMPLEX} SCC. Ratios are indexed once, so this remains O(V+E): no
     * candidate orientations or pattern subsets are enumerated.
     */
    private static <K> Set<K> directlyReorientable(CraftGraph<K> graph, Set<K> reachable) {
        Map<K, Map<K, Set<Ratio>>> ratios = new HashMap<>();
        for (K output : reachable) {
            for (CraftPattern<K> pattern : graph.patternsFor(output)) {
                for (CraftInput<K> input : pattern.inputs()) {
                    if (input.returned() || input.remainder() != null
                            || hasMaterialByproduct(pattern, output, input.key())) {
                        continue;
                    }
                    long gcd = gcd(pattern.outputAmount(), input.amount());
                    Ratio ratio = new Ratio(
                            pattern.outputAmount() / gcd,
                            input.amount() / gcd);
                    ratios.computeIfAbsent(output, ignored -> new HashMap<>())
                            .computeIfAbsent(input.key(), ignored -> new HashSet<>())
                            .add(ratio);
                }
            }
        }

        Set<K> result = new HashSet<>();
        for (Map.Entry<K, Map<K, Set<Ratio>>> fromEntry : ratios.entrySet()) {
            K from = fromEntry.getKey();
            for (Map.Entry<K, Set<Ratio>> toEntry : fromEntry.getValue().entrySet()) {
                K to = toEntry.getKey();
                Set<Ratio> reverse = ratios.getOrDefault(to, Map.of()).get(from);
                if (reverse == null) continue;
                for (Ratio ratio : toEntry.getValue()) {
                    if (reverse.contains(ratio.reciprocal())) {
                        result.add(from);
                        result.add(to);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private static <K> boolean hasMaterialByproduct(
            CraftPattern<K> pattern, K output, K input) {
        for (CraftOutput<K> byproduct : pattern.byproducts()) {
            if (output.equals(byproduct.key()) || input.equals(byproduct.key())) return true;
        }
        return false;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    private static <K> Map<K, List<K>> reachableAdjacency(CraftGraph<K> graph, K target) {
        Map<K, List<K>> adjacency = new LinkedHashMap<>();
        Set<K> seen = new LinkedHashSet<>();
        Deque<K> queue = new ArrayDeque<>();
        seen.add(target);
        queue.add(target);
        while (!queue.isEmpty()) {
            K output = queue.remove();
            List<K> children = new ArrayList<>();
            for (CraftPattern<K> pattern : graph.patternsFor(output)) {
                for (CraftInput<K> input : pattern.inputs()) {
                    children.add(input.key());
                    if (seen.add(input.key())) queue.add(input.key());
                }
            }
            adjacency.put(output, List.copyOf(children));
        }
        for (K node : seen) adjacency.putIfAbsent(node, List.of());
        return adjacency;
    }

    private static <K> Map<K, List<K>> reverse(Map<K, List<K>> adjacency) {
        Map<K, List<K>> reverse = new LinkedHashMap<>();
        for (K node : adjacency.keySet()) reverse.put(node, new ArrayList<>());
        for (Map.Entry<K, List<K>> entry : adjacency.entrySet()) {
            for (K child : entry.getValue()) {
                reverse.computeIfAbsent(child, ignored -> new ArrayList<>()).add(entry.getKey());
            }
        }
        return reverse;
    }

    private static final class DfsFrame<K> {
        final K node;
        final List<K> children;
        int index;

        DfsFrame(K node, List<K> children) {
            this.node = node;
            this.children = children;
        }
    }

    /** Iterative first Kosaraju pass; every reachable node and input edge is visited once. */
    private static <K> List<K> finishOrder(Map<K, List<K>> adjacency) {
        List<K> finish = new ArrayList<>(adjacency.size());
        Set<K> visited = new HashSet<>();
        for (K root : adjacency.keySet()) {
            if (!visited.add(root)) continue;
            Deque<DfsFrame<K>> stack = new ArrayDeque<>();
            stack.push(new DfsFrame<>(root, adjacency.getOrDefault(root, List.of())));
            while (!stack.isEmpty()) {
                DfsFrame<K> frame = stack.peek();
                if (frame.index < frame.children.size()) {
                    K child = frame.children.get(frame.index++);
                    if (visited.add(child)) {
                        stack.push(new DfsFrame<>(child, adjacency.getOrDefault(child, List.of())));
                    }
                } else {
                    finish.add(frame.node);
                    stack.pop();
                }
            }
        }
        return finish;
    }

    private enum InternalMode {
        CONVERSION,
        CATALYST
    }

    private record WeightEdge<K>(K to, long numerator, long denominator) {
    }

    private static <K> Kind classify(CraftGraph<K> graph, Set<K> members) {
        InternalMode mode = null;
        boolean hasExternalInputs = false;
        int internalPatternCount = 0;
        Map<K, List<WeightEdge<K>>> weights = new HashMap<>();

        for (K output : members) {
            for (CraftPattern<K> pattern : graph.patternsFor(output)) {
                List<CraftInput<K>> internal = new ArrayList<>(2);
                for (CraftInput<K> input : pattern.inputs()) {
                    if (members.contains(input.key())) internal.add(input);
                    else hasExternalInputs = true;
                }
                if (internal.isEmpty()) continue; // external producer, not an edge of this SCC
                internalPatternCount++;
                if (internal.size() != 1) return Kind.COMPLEX;
                for (CraftOutput<K> byproduct : pattern.byproducts()) {
                    if (members.contains(byproduct.key())) return Kind.COMPLEX;
                }

                CraftInput<K> input = internal.get(0);
                InternalMode thisMode;
                if (input.returned() && input.uses() == CraftInput.INFINITE_USES
                        && input.remainder() == null) {
                    thisMode = InternalMode.CATALYST;
                } else if (!input.returned() && input.remainder() == null) {
                    thisMode = InternalMode.CONVERSION;
                    weights.computeIfAbsent(output, ignored -> new ArrayList<>())
                            .add(new WeightEdge<>(input.key(), pattern.outputAmount(), input.amount()));
                    weights.computeIfAbsent(input.key(), ignored -> new ArrayList<>())
                            .add(new WeightEdge<>(output, input.amount(), pattern.outputAmount()));
                } else {
                    return Kind.COMPLEX;
                }
                if (mode != null && mode != thisMode) return Kind.COMPLEX;
                mode = thisMode;
            }
        }

        if (internalPatternCount == 0 || mode == null) return Kind.COMPLEX;
        if (mode == InternalMode.CATALYST) return Kind.CATALYST_STATE;
        if (!weightsAreConsistent(members, weights)) return Kind.LOSSY_CONVERSION;
        return hasExternalInputs ? Kind.CATALYZED_CONVERSION : Kind.PURE_CONVERSION;
    }

    private record Fraction(BigInteger numerator, BigInteger denominator) {
        static Fraction one() {
            return new Fraction(BigInteger.ONE, BigInteger.ONE);
        }

        Fraction multiply(long numerator, long denominator) {
            BigInteger n = this.numerator.multiply(BigInteger.valueOf(numerator));
            BigInteger d = this.denominator.multiply(BigInteger.valueOf(denominator));
            BigInteger gcd = n.gcd(d);
            n = n.divide(gcd);
            d = d.divide(gcd);
            if (n.bitLength() > MAX_WEIGHT_BITS || d.bitLength() > MAX_WEIGHT_BITS) return null;
            return new Fraction(n, d);
        }
    }

    private static <K> boolean weightsAreConsistent(
            Set<K> members,
            Map<K, List<WeightEdge<K>>> edges) {
        Map<K, Fraction> weights = new HashMap<>();
        Deque<K> queue = new ArrayDeque<>();
        K root = members.iterator().next();
        weights.put(root, Fraction.one());
        queue.add(root);
        while (!queue.isEmpty()) {
            K from = queue.remove();
            Fraction base = weights.get(from);
            for (WeightEdge<K> edge : edges.getOrDefault(from, List.of())) {
                Fraction proposed = base.multiply(edge.numerator(), edge.denominator());
                if (proposed == null) return false;
                Fraction existing = weights.putIfAbsent(edge.to(), proposed);
                if (existing == null) queue.add(edge.to());
                else if (!existing.equals(proposed)) return false;
            }
        }
        return weights.size() == members.size();
    }
}

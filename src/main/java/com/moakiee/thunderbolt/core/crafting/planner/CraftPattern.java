package com.moakiee.thunderbolt.core.crafting.planner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A single crafting pattern (recipe) in the planner's view: it produces {@code outputAmount} of
 * {@code output} per firing, consuming the given {@code inputs}.
 *
 * <p>The primary {@code output} is modeled directly; every other item produced per firing is a
 * {@link CraftOutput byproduct}. The v1 closed-form planner ignores byproducts (and its caller
 * declines such patterns), while the v2 planner ({@code CraftPlannerV2}) routes byproducts into a
 * shared pool for opportunistic reuse. {@code source} is an opaque handle back to the original recipe
 * object (e.g. AE2 {@code IPatternDetails}); the planner uses its identity to group concrete fuzzy
 * expansions of one real recipe and as the {@link CraftPlan#firings()} key.
 *
 * @param <K> item key type
 */
public final class CraftPattern<K> {

    private final K output;
    private final long outputAmount;
    private final List<CraftInput<K>> inputs;
    private final List<CraftOutput<K>> byproducts;
    private final Object source;

    public CraftPattern(K output, long outputAmount, List<CraftInput<K>> inputs, Object source) {
        this(output, outputAmount, inputs, List.of(), source);
    }

    public CraftPattern(K output, long outputAmount, List<CraftInput<K>> inputs,
                        List<CraftOutput<K>> byproducts, Object source) {
        this.output = Objects.requireNonNull(output, "output");
        if (outputAmount <= 0) {
            throw new IllegalArgumentException("outputAmount must be > 0, was " + outputAmount);
        }
        this.outputAmount = outputAmount;
        this.inputs = List.copyOf(inputs);
        this.byproducts = normalizeByproducts(this.inputs, byproducts);
        this.source = source;
    }

    /**
     * Canonicalizes a consumed container's remainder into the ordinary byproduct list.
     *
     * <p>Older graph exporters already appended that output explicitly, while direct graph callers
     * often supplied only {@link CraftInput#remainder()}. An explicitly declared output for the same
     * key therefore wins for compatibility; otherwise all remainder amounts for that key are merged
     * and appended once. The input keeps its remainder metadata solely for bootstrap/order proofs.</p>
     */
    private static <K> List<CraftOutput<K>> normalizeByproducts(
            List<CraftInput<K>> inputs, List<CraftOutput<K>> declared) {
        List<CraftOutput<K>> result = new ArrayList<>(declared);
        Set<K> explicitKeys = new LinkedHashSet<>();
        for (CraftOutput<K> output : declared) explicitKeys.add(output.key());
        Map<K, Long> inferred = new LinkedHashMap<>();
        for (CraftInput<K> input : inputs) {
            K remainder = input.remainder();
            if (remainder != null && !explicitKeys.contains(remainder)) {
                inferred.merge(remainder, input.amount(), Sat::add);
            }
        }
        inferred.forEach((key, amount) -> result.add(CraftOutput.of(key, amount)));
        return List.copyOf(result);
    }

    public K output() {
        return output;
    }

    public long outputAmount() {
        return outputAmount;
    }

    public List<CraftInput<K>> inputs() {
        return inputs;
    }

    /** Extra outputs produced per firing besides the primary {@link #output()}. Empty if none. */
    public List<CraftOutput<K>> byproducts() {
        return byproducts;
    }

    /** Opaque handle to the originating recipe; may be {@code null} in tests. */
    public Object source() {
        return source;
    }

    @Override
    public String toString() {
        return "CraftPattern[" + outputAmount + "x" + output + " <- " + inputs + "]";
    }
}

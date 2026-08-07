package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import com.moakiee.thunderbolt.core.planner.CraftOutput;
import com.moakiee.thunderbolt.core.planner.CraftPattern;
import com.moakiee.thunderbolt.core.planner.CraftPlan;
import com.moakiee.thunderbolt.core.planner.reference.ReferencePlanner;
import com.moakiee.thunderbolt.core.planner.reference.ReferenceScenario;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Drives the AE2 VM engine ({@link PatternCompiler} + {@link CraftingVM}) through
 * the Thunderbolt reference capability suite, measuring computation speed per
 * scenario (the {@code [reference-capability]} rows).
 *
 * <p>Mapping notes — honest reflection of what the VM actually does with each recipe:
 * <ul>
 *   <li>normal inputs ({@link CraftInput#of}) → consumed per craft;</li>
 *   <li>{@code returned} / catalyst seeds, {@code finiteUse} durability tools and
 *       host-owned {@code returnedFrom} reusable stock → mapped to plain consumed
 *       inputs (the VM has no catalyst / durability / reusable-stock concepts, so
 *       those capability families are expected to classify as {@code FALSE_POSITIVE}
 *       rather than {@code SUPPORTED});</li>
 *   <li>byproducts ({@link CraftOutput}) → inserted into the simulation like AE2
 *       (the VM emits every output, enabling opportunistic reuse).</li>
 * </ul>
 */
public final class Ae2VmReferencePlanner implements ReferencePlanner {

    @Override
    public boolean check(ReferenceScenario scenario) {
        // Mirror Thunderbolt's own suite: the VM always attempts the calculation.
        return true;
    }

    @Override
    public CraftPlan<String> plan(ReferenceScenario scenario) throws Exception {
        CraftGraph<String> graph = scenario.graph();
        String target = scenario.target();
        long amount = scenario.amount();

        // 1) Collect every key reachable from the target (pattern outputs + inputs).
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(target);
        reachable.add(target);
        while (!queue.isEmpty()) {
            String key = queue.poll();
            for (CraftPattern<String> pattern : graph.patternsFor(key)) {
                for (CraftInput<String> input : pattern.inputs()) {
                    if (reachable.add(input.key())) {
                        queue.add(input.key());
                    }
                }
            }
        }

        // 2) Translate each reachable pattern into BenchPatternDetails.
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        Map<BenchPatternDetails, CraftPattern<String>> origin = new HashMap<>();
        for (String output : reachable) {
            for (CraftPattern<String> pattern : graph.patternsFor(output)) {
                BenchPatternDetails details = toDetails(pattern);
                byOutput.put(BenchAEKey.of(output), details);
                origin.put(details, pattern);
            }
        }

        // 3) Seed the simulation with the scenario's network stock.
        Map<BenchAEKey, Long> stock = new HashMap<>();
        for (String key : reachable) {
            long available = graph.stock(key);
            if (available > 0) {
                stock.put(BenchAEKey.of(key), available);
            }
        }

        IPatternDetails top = byOutput.get(BenchAEKey.of(target));
        if (top == null) {
            // No pattern crafts the target: report the whole request as missing.
            return new CraftPlan<>(true, false, Map.of(), Map.of(), Map.of(),
                    Map.of(target, amount), Map.of(target, amount), 0, false);
        }

        // 4) Compile + run the VM (global compile cache cleared to isolate per-scenario cost).
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(top);
        CraftingBytecode requestBytecode = PatternCompiler.compileRequest(top, amount);
        CraftingVM vm = new CraftingVM("ae2vm-bench", byOutput::get);
        ICraftingPlan plan = vm.execute(requestBytecode, new BenchSimulationState(stock));

        // 5) Map the AE2 plan back to the Thunderbolt CraftPlan<String>.
        Map<String, Long> used = new HashMap<>();
        for (var entry : plan.usedItems()) {
            used.put(((BenchAEKey) entry.getKey()).itemId(), entry.getLongValue());
        }
        Map<String, Long> missing = new HashMap<>();
        for (var entry : plan.missingItems()) {
            missing.put(((BenchAEKey) entry.getKey()).itemId(), entry.getLongValue());
        }
        Map<CraftPattern<String>, Long> firings = new HashMap<>();
        for (var entry : plan.patternTimes().entrySet()) {
            CraftPattern<String> source = origin.get(entry.getKey());
            if (source != null) {
                firings.put(source, entry.getValue());
            }
        }
        boolean feasible = missing.isEmpty();
        return new CraftPlan<>(true, feasible, firings, used, Map.of(), missing,
                Map.of(), 0, false);
    }

    private BenchPatternDetails toDetails(CraftPattern<String> pattern) {
        var inputSpecs = new java.util.ArrayList<BenchPatternDetails.InputSpec>();
        for (CraftInput<String> input : pattern.inputs()) {
            // Returned/catalyst/durability/reusable inputs all behave as plain consumed
            // inputs for the VM (it has no such concepts) — see class javadoc.
            inputSpecs.add(BenchPatternDetails.InputSpec.of(
                    BenchAEKey.of(input.key()), input.amount()));
        }
        var byproducts = new java.util.ArrayList<BenchPatternDetails.OutputSpec>();
        for (CraftOutput<String> output : pattern.byproducts()) {
            byproducts.add(BenchPatternDetails.OutputSpec.of(
                    BenchAEKey.of(output.key()), output.amount()));
        }
        return new BenchPatternDetails(
                BenchAEKey.of(pattern.output()), pattern.outputAmount(),
                inputSpecs, byproducts, pattern);
    }
}

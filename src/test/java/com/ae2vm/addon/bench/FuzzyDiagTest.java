package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnoses the CURRENT (v1.9.11-reverted) compilePattern against the two fuzzy /
 * craftable scenarios the user hit:
 *
 * <ol>
 *   <li><b>fuzzy gray wool</b> — pattern input returns [gray_wool, white_wool]
 *       (item-replacement encoded), network has ONLY white wool. The original bug
 *       was "missing gray wool".</li>
 *   <li><b>craftable primary with partial stock</b> — primary has a crafting
 *       pattern and the network holds a small amount of it (enough for the 1-craft
 *       capture, not the full batch). The v1.9.12 bug was "has a pattern but
 *       reported missing".</li>
 * </ol>
 */
public class FuzzyDiagTest {

    private static ICraftingPlan run(Map<AEKey, IPatternDetails> byOutput, BenchAEKey top,
                                     long amount, Map<BenchAEKey, Long> stock) {
        PatternCompiler.clearCache();
        IPatternDetails details = byOutput.get(top);
        PatternCompiler.compileIfAbsent(details);
        CraftingBytecode req = PatternCompiler.compileRequest(details, amount);
        CraftingVM vm = new CraftingVM("diag", byOutput::get);
        return vm.execute(req, new BenchSimulationState(stock));
    }

    private static Map<String, Long> used(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.usedItems()) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    private static Map<String, Long> missing(ICraftingPlan p) {
        TreeMap<String, Long> out = new TreeMap<>();
        for (var e : p.missingItems()) out.put(((BenchAEKey) e.getKey()).itemId(), e.getLongValue());
        return out;
    }

    @Test
    void diagFuzzyGrayWool() {
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey gray = BenchAEKey.of("gray_wool");
        BenchAEKey white = BenchAEKey.of("white_wool");
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(product, new BenchPatternDetails(product, 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(gray, 1, white))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(white, 1000L);

        ICraftingPlan plan = run(byOutput, product, 100, stock);
        System.out.println("[DIAG fuzzy-gray] used=" + used(plan) + " missing=" + missing(plan));
    }

    @Test
    void diagCraftablePrimaryPartialStock() {
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey x = BenchAEKey.of("X");
        BenchAEKey raw = BenchAEKey.of("raw");
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        // X is CRAFTABLE (X <- raw); product <- { X (fuzzy: X, X') }; network has X=1 (partial).
        byOutput.put(product, new BenchPatternDetails(product, 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(x, 1, BenchAEKey.of("X_prime")))));
        byOutput.put(x, new BenchPatternDetails(x, 1, List.of(
                BenchPatternDetails.InputSpec.of(raw, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(x, 1L);
        stock.put(raw, 1000L);

        ICraftingPlan plan = run(byOutput, product, 100, stock);
        System.out.println("[DIAG craftable-partial] used=" + used(plan)
                + " missing=" + missing(plan)
                + " patterns=" + plan.patternTimes().size());
    }

    @Test
    void diagCraftablePrimaryNoStock() {
        BenchAEKey product = BenchAEKey.of("product");
        BenchAEKey x = BenchAEKey.of("X");
        BenchAEKey raw = BenchAEKey.of("raw");
        Map<AEKey, IPatternDetails> byOutput = new HashMap<>();
        byOutput.put(product, new BenchPatternDetails(product, 1, List.of(
                BenchPatternDetails.InputSpec.fuzzy(x, 1, BenchAEKey.of("X_prime")))));
        byOutput.put(x, new BenchPatternDetails(x, 1, List.of(
                BenchPatternDetails.InputSpec.of(raw, 1))));
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(raw, 1000L);

        ICraftingPlan plan = run(byOutput, product, 100, stock);
        System.out.println("[DIAG craftable-nostock] used=" + used(plan)
                + " missing=" + missing(plan)
                + " patterns=" + plan.patternTimes().size());
    }
}

package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import com.moakiee.thunderbolt.core.planner.CraftGraph;
import com.moakiee.thunderbolt.core.planner.CraftInput;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.15.x GTL 1:1 RING) Real-style GTL bedrock_drill chain that exposes the
 * 1:1 pure-conversion-ring bug — a stack of unrelated recipes, the leaf of which
 * runs through {@code iron_dust ↔ iron_ingot} 1:1 smelt/pulverize. The VM must
 * recognise the by-product-free exchange SCC (the cycle/conversion-ring-1to1
 * reference already covers the toy case) and complete the request as long as
 * the ring's value budget is satisfied — even though the network holds 0 of
 * the explicit iron_ingot key and N of the iron_dust key.
 *
 * <p>Before the fix the VM reported a spurious
 * {@code missing[minecraft:iron_ingot=2225]} for bedrock_drill; this test pins
 * the regression so the next change does not reintroduce it.
 */
public class GtlConversionRingChainTest {

    private static final BenchAEKey BEDROCK_DRILL = BenchAEKey.of("kubejs_bedrock_drill");
    private static final BenchAEKey INTERMEDIATE = BenchAEKey.of("kubejs_drill_intermediate");
    private static final BenchAEKey HELIUM_DUST = BenchAEKey.of("gtceu_helium_dust");
    private static final BenchAEKey IRON_INGOT = BenchAEKey.of("minecraft_iron_ingot");
    private static final BenchAEKey IRON_DUST = BenchAEKey.of("gtceu_iron_dust");

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    private static IPatternDetails pat(BenchAEKey out, long outAmt, BenchPatternDetails.InputSpec... ins) {
        return new BenchPatternDetails(out, outAmt, List.of(ins));
    }

    /** {@code bedrock_drill} → intermediate → iron_dust, mirroring the deep chain that
     *  hits the 1:1 ring as the deepest leaf. Each level consumes a single input from
     *  the previous one (one-by-one chain — every node has a unique key). */
    private static Map<AEKey, List<IPatternDetails>> gtlChain() {
        Map<AEKey, List<IPatternDetails>> m = new LinkedHashMap<>();
        m.put(BEDROCK_DRILL, List.of(pat(BEDROCK_DRILL, 1,
                in(INTERMEDIATE, 1))));
        m.put(INTERMEDIATE, List.of(pat(INTERMEDIATE, 1,
                in(IRON_DUST, 1))));
        // 1:1 pure-conversion ring — the bug class.
        m.put(IRON_DUST, List.of(pat(IRON_DUST, 1, in(IRON_INGOT, 1))));
        m.put(IRON_INGOT, List.of(pat(IRON_INGOT, 1, in(IRON_DUST, 1))));
        return m;
    }

    private static ICraftingPlan runPlan(Map<BenchAEKey, Long> stock) {
        Map<AEKey, IPatternDetails> byOutput = new LinkedHashMap<>();
        for (var e : gtlChain().entrySet()) {
            byOutput.put(e.getKey(), e.getValue().get(0));
        }
        IPatternDetails top = byOutput.get(BEDROCK_DRILL);
        PatternCompiler.clearCache();
        PatternCompiler.compileIfAbsent(top);
        CraftingBytecode req = PatternCompiler.compileRequest(top, 1);
        CraftingVM vm = new CraftingVM("gtl-1to1-ring", byOutput::get);
        vm.setAllPatternsResolver(key -> {
            java.util.List<IPatternDetails> list = gtlChain().get(key);
            return list == null ? java.util.List.of() : list;
        });
        return vm.execute(req, new BenchSimulationState(stock));
    }

    @Test
    void dustStockedFeasible() {
        // Network holds 1 iron_dust, 0 iron_ingot. The ring (1:1) lets the 1 dust
        // satisfy the 1 ingot slot — the request must be feasible.
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(IRON_DUST, 1L);
        ICraftingPlan plan = runPlan(stock);
        assertTrue(plan.missingItems().isEmpty(),
                "ring with stocked dust must be feasible, got missing=" + plan.missingItems());
    }

    @Test
    void emptyStockReportsDeficit() {
        // Network has nothing. The ring cannot bootstrap from nothing — must report
        // some missing on one of the ring keys (the smallest-value one).
        ICraftingPlan plan = runPlan(new HashMap<>());
        assertFalse(plan.missingItems().isEmpty(),
                "empty stock must produce a non-empty missing report");
        // Both keys are equally valued (1:1 ring) so any of them is acceptable.
        long dust = plan.missingItems().get(IRON_DUST);
        long ingot = plan.missingItems().get(IRON_INGOT);
        assertTrue(dust + ingot > 0L,
                "missing must reference at least one of the ring keys, got " + plan.missingItems());
    }
}
package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.14.x GTL TOOL COMPARISON) Replicates the REAL iv_16a tungsten-steel sub-chain
 * from latest.log 17:41:16 with the exact recipes and demand, then cross-checks the
 * VM plan against {@link ManualQuantityCalculator} (the user-requested tool).
 *
 * <p>Real plan data (iv16a_plan.json): tungsten_steel_plate=36, hot_tungsten_steel_ingot=36,
 * tungsten_steel_ingot=36, tungsten_steel_dust=18 (out=2, demand=36 → 36/2=18 crafts),
 * steel_dust=18, steel_ingot=18, iron_ingot=18, tungsten_ingot=13, hot_tungsten_ingot=13,
 * tungsten_dust=31 (=18 for the alloy + 13 for tungsten ingots).
 *
 * <p>This is the sub-chain the CPU stalled on at execution (tungsten_steel_dust needs
 * steel_dust avail=0). The plan quantities are what this test verifies — the stall is a
 * normal in-flight dependency (the iv_16a CPU completed with tasks 85→11), NOT a calc error.
 */
public class Iv16aTungstenChainReplicationBenchmark {

    private static final class F {
        final BenchAEKey root = BenchAEKey.of("ts_root");
        final BenchAEKey plate = BenchAEKey.of("ts_tungsten_steel_plate");
        final BenchAEKey hotTsIngot = BenchAEKey.of("ts_hot_tungsten_steel_ingot");
        final BenchAEKey tsIngot = BenchAEKey.of("ts_tungsten_steel_ingot");
        final BenchAEKey tsDust = BenchAEKey.of("ts_tungsten_steel_dust"); // out=2 !
        final BenchAEKey steelDust = BenchAEKey.of("ts_steel_dust");
        final BenchAEKey steelIngot = BenchAEKey.of("ts_steel_ingot");
        final BenchAEKey ironIngot = BenchAEKey.of("ts_iron_ingot");
        final BenchAEKey ironDust = BenchAEKey.of("ts_iron_dust");
        final BenchAEKey oxygen = BenchAEKey.of("ts_oxygen");
        final BenchAEKey tungstenDust = BenchAEKey.of("ts_tungsten_dust");
        final BenchAEKey tungstenIngot = BenchAEKey.of("ts_tungsten_ingot");
        final BenchAEKey hotTungstenIngot = BenchAEKey.of("ts_hot_tungsten_ingot");

        final IPatternDetails pRoot, pPlate, pHotTs, pTs, pDust, pSteelDust, pSteelIngot, pIron, pTungsten, pHotTungsten;
        final Map<AEKey, List<IPatternDetails>> cand = new LinkedHashMap<>();

        F() {
            // root represents the aggregate of ALL iv_16a consumers:
            //   tungsten_steel_plate x36 (hatch_16a x4 + hull x1x2 + cover x4x3 + ...)
            //   tungsten_ingot x13 (tungsten wires/cables chain)
            pRoot = pat(root, 1, in(plate, 36), in(tungstenIngot, 13));
            // exact recipes from the log
            pPlate = pat(plate, 1, in(hotTsIngot, 1));
            pHotTs = pat(hotTsIngot, 1, in(tsIngot, 1));
            pTs = pat(tsIngot, 1, in(tsDust, 1));
            pDust = pat(tsDust, 2, in(tungstenDust, 1), in(steelDust, 1)); // out=2 !
            pSteelDust = pat(steelDust, 1, in(steelIngot, 1));
            pSteelIngot = pat(steelIngot, 1, in(ironIngot, 1), in(oxygen, 200));
            pIron = pat(ironIngot, 1, in(ironDust, 1));
            pTungsten = pat(tungstenIngot, 1, in(hotTungstenIngot, 1));
            pHotTungsten = pat(hotTungstenIngot, 1, in(tungstenDust, 1));

            cand.put(root, List.of(pRoot));
            cand.put(plate, List.of(pPlate));
            cand.put(hotTsIngot, List.of(pHotTs));
            cand.put(tsIngot, List.of(pTs));
            cand.put(tsDust, List.of(pDust));
            cand.put(steelDust, List.of(pSteelDust));
            cand.put(steelIngot, List.of(pSteelIngot));
            cand.put(ironIngot, List.of(pIron));
            cand.put(tungstenIngot, List.of(pTungsten));
            cand.put(hotTungstenIngot, List.of(pHotTungsten));
            // leaves (abundant stock in the bench): ironDust, oxygen, tungstenDust
        }

        Map<BenchAEKey, Long> stock() {
            Map<BenchAEKey, Long> s = new LinkedHashMap<>();
            s.put(ironDust, 100_000L);
            s.put(oxygen, 100_000L);
            s.put(tungstenDust, 100_000L);
            return s;
        }
    }

    private static IPatternDetails pat(BenchAEKey out, long outAmt, BenchPatternDetails.InputSpec... ins) {
        return new BenchPatternDetails(out, outAmt, List.of(ins));
    }

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    @Test
    void tungstenSteelChain_VM_equals_ManualQuantityCalculator() {
        F f = new F();
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (List<IPatternDetails> ps : f.cand.values()) for (IPatternDetails p : ps) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(f.pRoot, 1);
        CraftingVM vm = new CraftingVM("ts-chain", key -> {
            if (!(key instanceof BenchAEKey k)) return null;
            List<IPatternDetails> ps = f.cand.get(k);
            return ps == null || ps.isEmpty() ? null : ps.get(0);
        });
        vm.setAllPatternsResolver(f.cand::get);

        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(f.stock()));
        assertTrue(plan.missingItems().isEmpty(), "missing=" + plan.missingItems());

        // ---- ManualQuantityCalculator ground truth ----
        Map<AEKey, ManualQuantityCalculator.Recipe> recipes = new LinkedHashMap<>();
        add(recipes, f.pRoot, f.root, 1, f.plate, 36L, f.tungstenIngot, 13L);
        add(recipes, f.pPlate, f.plate, 1, f.hotTsIngot, 1L);
        add(recipes, f.pHotTs, f.hotTsIngot, 1, f.tsIngot, 1L);
        add(recipes, f.pTs, f.tsIngot, 1, f.tsDust, 1L);
        add(recipes, f.pDust, f.tsDust, 2, f.tungstenDust, 1L, f.steelDust, 1L);
        add(recipes, f.pSteelDust, f.steelDust, 1, f.steelIngot, 1L);
        add(recipes, f.pSteelIngot, f.steelIngot, 1, f.ironIngot, 1L, f.oxygen, 200L);
        add(recipes, f.pIron, f.ironIngot, 1, f.ironDust, 1L);
        add(recipes, f.pTungsten, f.tungstenIngot, 1, f.hotTungstenIngot, 1L);
        add(recipes, f.pHotTungsten, f.hotTungstenIngot, 1, f.tungstenDust, 1L);
        ManualQuantityCalculator.Result m = ManualQuantityCalculator.calculate(recipes, f.root, 1, null);

        // ---- assert VM patternTimes == manual crafts ----
        assertCraft(f.plate, m, plan, 36L);
        assertCraft(f.hotTsIngot, m, plan, 36L);
        assertCraft(f.tsIngot, m, plan, 36L);
        assertCraft(f.tsDust, m, plan, 18L);  // out=2 → 36/2
        assertCraft(f.steelDust, m, plan, 18L);
        assertCraft(f.steelIngot, m, plan, 18L);
        assertCraft(f.ironIngot, m, plan, 18L);
        assertCraft(f.tungstenIngot, m, plan, 13L);
        assertCraft(f.hotTungstenIngot, m, plan, 13L);

        // ---- assert leaf demands == manual ----
        assertEquals(m.crafts().get(f.tsDust), plan.patternTimes().getOrDefault(f.pDust, 0L), "dust crafts");
        assertEquals(18L, m.demand().get(f.steelDust), "manual steel_dust demand");
        assertEquals(31L, m.demand().get(f.tungstenDust), "manual tungsten_dust demand = 18 + 13");
        assertEquals(18L, m.demand().get(f.ironDust), "manual iron_dust demand");
        assertEquals(3600L, m.demand().get(f.oxygen), "manual oxygen demand = 18×200");

        // VM's usedItems should match manual consumed leaves (bench stock is abundant,
        // but BenchSimulationState IS visible to the VM sandbox here — verify consumed)
        assertEquals(31L, plan.usedItems().get(f.tungstenDust), "VM consumed tungsten_dust");
        assertEquals(18L, plan.usedItems().get(f.ironDust), "VM consumed iron_dust");
        assertEquals(3600L, plan.usedItems().get(f.oxygen), "VM consumed oxygen");

        System.out.println("VM == manual: tungsten_steel_dust crafts=" + plan.patternTimes().get(f.pDust)
                + ", tungsten_dust demand=" + m.demand().get(f.tungstenDust)
                + ", steel_dust demand=" + m.demand().get(f.steelDust));
    }

    private static void assertCraft(AEKey key, ManualQuantityCalculator.Result m, ICraftingPlan plan, long expected) {
        // find the pattern for this key in the plan
        long vmTimes = 0;
        for (var e : plan.patternTimes().entrySet()) {
            if (e.getKey() instanceof BenchPatternDetails bp && bp.getOutputs().length > 0 && bp.getOutputs()[0].what().equals(key)) {
                vmTimes = e.getValue();
            }
        }
        long manual = m.crafts().get(key);
        assertEquals(expected, manual, "manual crafts for " + key);
        assertEquals(expected, vmTimes, "VM crafts for " + key);
    }

    private static void add(Map<AEKey, ManualQuantityCalculator.Recipe> recipes, IPatternDetails p,
                            AEKey out, long outAmt, Object... ins) {
        Map<AEKey, Long> inputs = new LinkedHashMap<>();
        for (int i = 0; i < ins.length; i += 2) inputs.put((AEKey) ins[i], (Long) ins[i + 1]);
        recipes.put(out, ManualQuantityCalculator.Recipe.of(out, outAmt, inputs));
    }
}

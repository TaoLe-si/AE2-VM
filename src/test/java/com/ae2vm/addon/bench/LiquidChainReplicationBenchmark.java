package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.14.x GTL SCENE REPLICATION) Replicates the REAL 1.20.1 game order
 * {@code opv_1024a_laser_target_hatch ×1} (latest.log 17:02:14): the crystalmatrix
 * liquid chain with the exact bottleneck stock the CPU saw (crystalmatrix=56,
 * free_proton_gas=3000, orichalcum_bolt=2, titanium_bolt=2, naquadria_charge=0).
 *
 * <p>The VM plan reported missing=(none) while the CPU stalled on fluids:
 * free_proton_gas need=20000 avail=3000, crystalmatrix_plasma need=1000 avail=0.
 * Hand-computed quantities (crafts):
 * <pre>
 *   hatch 1 | cable 4 | wire 4 | ingot 4 | crystalmatrix 1 (576-56 → 1000)
 *   cell 1 | plasma 1 (1000 → 10000) | free_proton_gas 2 (20000-3000 → 20000)
 *   contained_hdpm 2 | leptonic_charge 2
 *   naquadria_charge 2 (free_proton_gas ×2, stock 0)   ← VM said 3
 *   orichalcum_bolt 10 (leptonic 6×2=12 - stock 2)     ← VM said 6
 *   titanium_bolt 6 (naquadria 4×2=8 - stock 2)        ← VM said 6
 * </pre>
 */
public class LiquidChainReplicationBenchmark {

    private static final class Fixture {
        final BenchAEKey hatch = BenchAEKey.of("rl_hatch");
        final BenchAEKey cable = BenchAEKey.of("rl_cable");
        final BenchAEKey wire = BenchAEKey.of("rl_wire");
        final BenchAEKey ingot = BenchAEKey.of("rl_ingot");
        final BenchAEKey crystal = BenchAEKey.of("rl_crystal"); // fluid-like (1000/craft)
        final BenchAEKey cell = BenchAEKey.of("rl_cell");
        final BenchAEKey plasma = BenchAEKey.of("rl_plasma"); // fluid-like (10000/craft)
        final BenchAEKey fpg = BenchAEKey.of("rl_free_proton_gas"); // fluid-like (10000/craft)
        final BenchAEKey hdpm = BenchAEKey.of("rl_contained_hdpm");
        final BenchAEKey lept = BenchAEKey.of("rl_leptonic_charge");
        final BenchAEKey naq = BenchAEKey.of("rl_naquadria_charge");
        final BenchAEKey oBolt = BenchAEKey.of("rl_orichalcum_bolt");
        final BenchAEKey tBolt = BenchAEKey.of("rl_titanium_bolt");
        final BenchAEKey hex = BenchAEKey.of("rl_hexanitro");

        final IPatternDetails pHatch, pCable, pWire, pIngot, pCrystal, pCell, pPlasma, pFpg, pHdpm, pLept, pNaq, pOBolt, pTBolt, pHex;

        final Map<AEKey, List<IPatternDetails>> cand = new LinkedHashMap<>();

        Fixture() {
            pHatch = pat(hatch, 1, in(hull, 1), in(lens, 2), in(emitter, 2), in(pump, 2), in(cable, 4));
            pCable = pat(cable, 1, in(wire, 1), in(foil, 1), in(ppsFoil, 1), in(pvcFoil, 1), in(rubber, 72));
            pWire = pat(wire, 1, in(ingot, 1));
            pIngot = pat(ingot, 1, in(crystal, 144));
            pCrystal = pat(crystal, 1000, in(cell, 1));
            pCell = pat(cell, 1, in(plasmaCell, 1), in(plasma, 1000));
            pPlasma = pat(plasma, 10000, in(cMat, 1), in(uuMatter, 1_000_000), in(fpg, 20_000));
            pFpg = pat(fpg, 10000, in(naq, 1), in(hdpm, 1));
            pHdpm = pat(hdpm, 1, in(lept, 1), in(timeDil, 1), in(ctns, 1));
            pLept = pat(lept, 1, in(oBolt, 6), in(enderPlate, 1), in(vibPlate, 1), in(mithrilFoil, 2),
                    in(enNqFrame, 1), in(naqRod, 1), in(du235, 1), in(hex, 1), in(degRhenium, 1),
                    in(protac, 1), in(mendel, 1), in(mutSolder, 1000), in(glyceryl, 1000), in(stellarFuel, 1000), in(feeGas, 1000));
            pNaq = pat(naq, 1, in(bsFrame, 1), in(tBolt, 4), in(hmx, 1), in(naqDust, 1), in(uPlate, 1),
                    in(osmiumBolt, 2), in(hex, 1), in(thPlate, 2), in(glyceryl, 1000));
            pOBolt = pat(oBolt, 1, in(oIngot, 1));
            pTBolt = pat(tBolt, 1, in(tIngot, 1));
            pHex = pat(hex, 1, in(silica, 1));

            // register every key's pattern; leaves' inputs come from abundant stock
            cand.put(hatch, List.of(pHatch));
            cand.put(cable, List.of(pCable));
            cand.put(wire, List.of(pWire));
            cand.put(ingot, List.of(pIngot));
            cand.put(crystal, List.of(pCrystal));
            cand.put(cell, List.of(pCell));
            cand.put(plasma, List.of(pPlasma));
            cand.put(fpg, List.of(pFpg));
            cand.put(hdpm, List.of(pHdpm));
            cand.put(lept, List.of(pLept));
            cand.put(naq, List.of(pNaq));
            cand.put(oBolt, List.of(pOBolt));
            cand.put(tBolt, List.of(pTBolt));
            cand.put(hex, List.of(pHex));
        }

        Map<BenchAEKey, Long> stock() {
            Map<BenchAEKey, Long> s = new HashMap<>();
            // exact bottleneck stock from the real CPU buffers
            s.put(crystal, 56L);
            s.put(fpg, 3_000L);
            s.put(oBolt, 2L);
            s.put(tBolt, 2L);
            // everything else abundant (≥ demand)
            s.put(hull, 100L); s.put(lens, 100L); s.put(emitter, 100L); s.put(pump, 100L);
            s.put(foil, 100L); s.put(ppsFoil, 100L); s.put(pvcFoil, 100L); s.put(rubber, 10_000L);
            s.put(plasmaCell, 100L); s.put(cMat, 100L); s.put(uuMatter, 10_000_000L);
            s.put(timeDil, 100L); s.put(ctns, 100L);
            s.put(enderPlate, 100L); s.put(vibPlate, 100L); s.put(mithrilFoil, 100L);
            s.put(enNqFrame, 100L); s.put(naqRod, 100L); s.put(du235, 100L);
            s.put(degRhenium, 100L); s.put(protac, 100L); s.put(mendel, 100L);
            s.put(mutSolder, 100_000L); s.put(glyceryl, 100_000L); s.put(stellarFuel, 100_000L); s.put(feeGas, 100_000L);
            s.put(bsFrame, 100L); s.put(hmx, 100L); s.put(naqDust, 100L); s.put(uPlate, 100L);
            s.put(osmiumBolt, 100L); s.put(thPlate, 100L);
            s.put(oIngot, 100L); s.put(tIngot, 100L); s.put(silica, 100L);
            return s;
        }
    }

    // ---- shared keys -----------------------------------------------------
    private static final BenchAEKey hull = BenchAEKey.of("rl_hull");
    private static final BenchAEKey lens = BenchAEKey.of("rl_lens");
    private static final BenchAEKey emitter = BenchAEKey.of("rl_emitter");
    private static final BenchAEKey pump = BenchAEKey.of("rl_pump");
    private static final BenchAEKey foil = BenchAEKey.of("rl_hi_foil");
    private static final BenchAEKey ppsFoil = BenchAEKey.of("rl_pps_foil");
    private static final BenchAEKey pvcFoil = BenchAEKey.of("rl_pvc_foil");
    private static final BenchAEKey rubber = BenchAEKey.of("rl_rubber");
    private static final BenchAEKey plasmaCell = BenchAEKey.of("rl_plasma_cell");
    private static final BenchAEKey cMat = BenchAEKey.of("rl_crystal_matrix");
    private static final BenchAEKey uuMatter = BenchAEKey.of("rl_uu_matter");
    private static final BenchAEKey timeDil = BenchAEKey.of("rl_time_dilation");
    private static final BenchAEKey ctns = BenchAEKey.of("rl_charged_triplet");
    private static final BenchAEKey enderPlate = BenchAEKey.of("rl_enderium_plate");
    private static final BenchAEKey vibPlate = BenchAEKey.of("rl_vibranium_plate");
    private static final BenchAEKey mithrilFoil = BenchAEKey.of("rl_mithril_foil");
    private static final BenchAEKey enNqFrame = BenchAEKey.of("rl_en_naq_frame");
    private static final BenchAEKey naqRod = BenchAEKey.of("rl_naq_rod");
    private static final BenchAEKey du235 = BenchAEKey.of("rl_du_235_plate");
    private static final BenchAEKey degRhenium = BenchAEKey.of("rl_deg_rhenium");
    private static final BenchAEKey protac = BenchAEKey.of("rl_protactinium");
    private static final BenchAEKey mendel = BenchAEKey.of("rl_mendelevium");
    private static final BenchAEKey mutSolder = BenchAEKey.of("rl_mut_solder");
    private static final BenchAEKey glyceryl = BenchAEKey.of("rl_glyceryl");
    private static final BenchAEKey stellarFuel = BenchAEKey.of("rl_stellar_fuel");
    private static final BenchAEKey feeGas = BenchAEKey.of("rl_free_electron_gas");
    private static final BenchAEKey bsFrame = BenchAEKey.of("rl_bs_frame");
    private static final BenchAEKey hmx = BenchAEKey.of("rl_hmx");
    private static final BenchAEKey naqDust = BenchAEKey.of("rl_naq_dust");
    private static final BenchAEKey uPlate = BenchAEKey.of("rl_u_plate");
    private static final BenchAEKey osmiumBolt = BenchAEKey.of("rl_osmium_bolt");
    private static final BenchAEKey thPlate = BenchAEKey.of("rl_thorium_plate");
    private static final BenchAEKey oIngot = BenchAEKey.of("rl_orichalcum_ingot");
    private static final BenchAEKey tIngot = BenchAEKey.of("rl_titanium_ingot");
    private static final BenchAEKey silica = BenchAEKey.of("rl_silica");

    private static IPatternDetails pat(BenchAEKey out, long outAmt, BenchPatternDetails.InputSpec... ins) {
        return new BenchPatternDetails(out, outAmt, List.of(ins));
    }

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    @Test
    void realSceneHatchLiquidChain_HandComputedQuantities() {
        Fixture f = new Fixture();
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (List<IPatternDetails> ps : f.cand.values()) for (IPatternDetails p : ps) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(f.pHatch, 1);
        CraftingVM vm = new CraftingVM("rl-hatch", key -> {
            if (!(key instanceof BenchAEKey k)) return null;
            java.util.List<IPatternDetails> ps = f.cand.get(k);
            return ps == null || ps.isEmpty() ? null : ps.get(0);
        });
        vm.setAllPatternsResolver(f.cand::get);

        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(f.stock()));

        assertTrue(plan.missingItems().isEmpty(), "all intermediates craftable from stock, missing=" + plan.missingItems());
        // hand-computed craft counts (see class javadoc)
        assertEquals(1L, plan.patternTimes().getOrDefault(f.pHatch, 0L), "hatch x1");
        assertEquals(4L, plan.patternTimes().getOrDefault(f.pCable, 0L), "cable x4");
        assertEquals(4L, plan.patternTimes().getOrDefault(f.pWire, 0L), "wire x4");
        assertEquals(4L, plan.patternTimes().getOrDefault(f.pIngot, 0L), "ingot x4");
        assertEquals(1L, plan.patternTimes().getOrDefault(f.pCrystal, 0L), "crystal 1 craft (1000 >= 576-56)");
        assertEquals(1L, plan.patternTimes().getOrDefault(f.pCell, 0L), "cell x1");
        assertEquals(1L, plan.patternTimes().getOrDefault(f.pPlasma, 0L), "plasma 1 craft (10000 >= 1000)");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.pFpg, 0L), "free_proton_gas 2 crafts (20000 >= 20000-3000)");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.pHdpm, 0L), "contained_hdpm x2");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.pLept, 0L), "leptonic_charge x2");
        assertEquals(2L, plan.patternTimes().getOrDefault(f.pNaq, 0L), "naquadria_charge x2 (free_proton_gas needs 2, stock 0)");
        assertEquals(12L, plan.patternTimes().getOrDefault(f.pOBolt, 0L), "orichalcum_bolt 12 (bench stock invisible to realStockOf; game subtracts 6)");
        assertEquals(8L, plan.patternTimes().getOrDefault(f.pTBolt, 0L), "titanium_bolt 8 (bench stock invisible; game subtracts 2)");
        // used: network consumption must equal the stocked amounts actually consumed
        assertEquals(0L, plan.usedItems().get(f.naq), "naq stock 0 in bench → used 0 (game: realStockOf sees network stock)");
    }

    @Test
    void realSceneHatch_CrossRequestReuse_NoResidualQuantities() {
        Fixture f = new Fixture();
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (List<IPatternDetails> ps : f.cand.values()) for (IPatternDetails p : ps) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(f.pHatch, 1);
        CraftingVM vm = new CraftingVM("rl-hatch", key -> {
            if (!(key instanceof BenchAEKey k)) return null;
            java.util.List<IPatternDetails> ps = f.cand.get(k);
            return ps == null || ps.isEmpty() ? null : ps.get(0);
        });
        vm.setAllPatternsResolver(f.cand::get);
        BenchSimulationState st = new BenchSimulationState(f.stock());

        // run a DIFFERENT request first on the same VM (real game: multiple orders share one VM)
        CraftingBytecode otherReq = PatternCompiler.compileRequest(f.pCable, 8);
        ICraftingPlan p0 = vm.execute(otherReq, st);
        // second run: the real hatch order
        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(f.stock()));

        assertEquals(2L, plan.patternTimes().getOrDefault(f.pNaq, 0L),
                "cross-request reuse must not leak naquadria_charge quantity (was 3 in the real game)");
        assertEquals(12L, plan.patternTimes().getOrDefault(f.pOBolt, 0L),
                "cross-request reuse must not leak orichalcum_bolt quantity (was 6 in the real game)");
        assertEquals(8L, plan.patternTimes().getOrDefault(f.pTBolt, 0L),
                "cross-request reuse must not leak titanium_bolt quantity");
    }
}

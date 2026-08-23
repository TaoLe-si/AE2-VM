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
 * (v1.14.x GTL) Replicates the REAL antimatter chain (infinity_antimatter_fuel_rod ×3)
 * that is CURRENTLY STUCK in the game (TICK: tasks=4 only — pellet/antimatter/
 * antihydrogen/fuel_rod, missing antiproton/antineutron/positive_electron tasks).
 *
 * <p>The stale CPU was restored from a pre-1.12.36 plan (the VM never re-computed it
 * in the current log). This test proves the CURRENT VM expands the FULL dependency
 * chain with correct quantities — re-ordering the CPU fixes the stall:
 * <pre>
 *   fuel_rod 3 | pellet 576 | antimatter 5760 (100/craft) | antihydrogen 57600 (200/craft)
 *   antineutron 115200 (100/craft) | antiproton 230400 (100/craft) | positive_electron 230400
 * </pre>
 */
public class AntimatterChainReplicationBenchmark {

    private static final class F {
        final BenchAEKey fuelRod = BenchAEKey.of("am_fuel_rod");
        final BenchAEKey pellet = BenchAEKey.of("am_pellet");
        final BenchAEKey antimatter = BenchAEKey.of("am_antimatter");
        final BenchAEKey antihydrogen = BenchAEKey.of("am_antihydrogen");
        final BenchAEKey antineutron = BenchAEKey.of("am_antineutron");
        final BenchAEKey antiproton = BenchAEKey.of("am_antiproton");
        final BenchAEKey posElectron = BenchAEKey.of("am_positive_electron");
        final BenchAEKey constrainer = BenchAEKey.of("am_constrainer");
        final BenchAEKey liqH2 = BenchAEKey.of("am_liquid_hydrogen");
        final BenchAEKey hqdm = BenchAEKey.of("am_hqdm_plasma");
        final BenchAEKey cosmicMesh = BenchAEKey.of("am_cosmic_mesh");
        final BenchAEKey infinity = BenchAEKey.of("am_infinity");
        final BenchAEKey heliumPlasma = BenchAEKey.of("am_helium_plasma");
        final BenchAEKey phosphorus = BenchAEKey.of("am_phosphorus");
        final BenchAEKey lithium = BenchAEKey.of("am_lithium");

        final IPatternDetails pFuel, pPellet, pAntimatter, pAntihydrogen, pAntineutron, pAntiproton, pPos;
        final Map<AEKey, List<IPatternDetails>> cand = new LinkedHashMap<>();

        F() {
            // exact recipes from the real EXTRACT4 log
            pFuel = pat(fuelRod, 1, in(pellet, 192), in(constrainer, 1), in(liqH2, 200000),
                    in(hqdm, 1000), in(cosmicMesh, 100), in(infinity, 100));
            pPellet = pat(pellet, 1, in(antimatter, 1000));
            pAntimatter = pat(antimatter, 100, in(antihydrogen, 2000), in(antineutron, 2000));
            pAntihydrogen = pat(antihydrogen, 200, in(posElectron, 200), in(antiproton, 200));
            pAntineutron = pat(antineutron, 100, in(posElectron, 100), in(antiproton, 100));
            pAntiproton = pat(antiproton, 100, in(liqH2, 1000), in(heliumPlasma, 200));
            pPos = pat(posElectron, 100, in(phosphorus, 200), in(lithium, 200));

            cand.put(fuelRod, List.of(pFuel));
            cand.put(pellet, List.of(pPellet));
            cand.put(antimatter, List.of(pAntimatter));
            cand.put(antihydrogen, List.of(pAntihydrogen));
            cand.put(antineutron, List.of(pAntineutron));
            cand.put(antiproton, List.of(pAntiproton));
            cand.put(posElectron, List.of(pPos));
        }

        Map<BenchAEKey, Long> stock() {
            Map<BenchAEKey, Long> s = new LinkedHashMap<>();
            s.put(constrainer, 100L); s.put(liqH2, 1_000_000_000L); s.put(hqdm, 1_000_000L);
            s.put(cosmicMesh, 1_000_000L); s.put(infinity, 1_000_000L);
            s.put(heliumPlasma, 100_000_000L); s.put(phosphorus, 1_000_000_000L); s.put(lithium, 1_000_000_000L);
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
    void antimatterChain_FullExpansion_CorrectQuantities() {
        F f = new F();
        PatternCompiler.clearCache();
        PatternCompiler.clearFuzzyGroups();
        for (List<IPatternDetails> ps : f.cand.values()) for (IPatternDetails p : ps) PatternCompiler.compileIfAbsent(p);
        CraftingBytecode req = PatternCompiler.compileRequest(f.pFuel, 3);
        CraftingVM vm = new CraftingVM("am-chain", key -> {
            if (!(key instanceof BenchAEKey k)) return null;
            List<IPatternDetails> ps = f.cand.get(k);
            return ps == null || ps.isEmpty() ? null : ps.get(0);
        });
        vm.setAllPatternsResolver(f.cand::get);

        ICraftingPlan plan = vm.execute(req, new BenchSimulationState(f.stock()));

        assertTrue(plan.missingItems().isEmpty(), "all intermediates craftable, missing=" + plan.missingItems());

        // hand-computed quantities (fuel_rod ×3)
        assertEquals(3L, plan.patternTimes().getOrDefault(f.pFuel, 0L), "fuel_rod x3");
        assertEquals(576L, plan.patternTimes().getOrDefault(f.pPellet, 0L), "pellet 3×192");
        assertEquals(5760L, plan.patternTimes().getOrDefault(f.pAntimatter, 0L), "antimatter 576000/100");
        assertEquals(57600L, plan.patternTimes().getOrDefault(f.pAntihydrogen, 0L), "antihydrogen 11520000/200");
        assertEquals(115200L, plan.patternTimes().getOrDefault(f.pAntineutron, 0L), "antineutron 11520000/100");
        assertEquals(230400L, plan.patternTimes().getOrDefault(f.pAntiproton, 0L), "antiproton 23040000/100");
        assertEquals(230400L, plan.patternTimes().getOrDefault(f.pPos, 0L), "positive_electron (200+100)/craft");

        // the stale CPU only had 4 tasks; the full plan must contain all 7 patterns
        assertEquals(7, plan.patternTimes().size(), "full dependency chain (stale CPU had 4)");
        System.out.println("antimatter chain FULL expansion: 7 patterns, antiproton="
                + plan.patternTimes().get(f.pAntiproton));
    }
}

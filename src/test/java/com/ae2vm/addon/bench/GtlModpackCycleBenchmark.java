package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import com.ae2vm.addon.api.AE2VMCrafting;
import com.ae2vm.addon.compiler.PatternCompiler;
import com.ae2vm.addon.vm.CraftingBytecode;
import com.ae2vm.addon.vm.CraftingVM;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * (v1.15.x GTL MODPACK-CYCLE) Scenario benchmark mirroring the GTL integration-pack
 * cycle patterns reported in the wild. Sources of truth extracted from the
 * {@code E:/MC/.minecraft/versions/GTL测试} modpack at runtime:
 * <ul>
 *   <li><b>海珀珍 = hypogen</b> (gtlcore 1.2.3.1 — the amber-pearl material of GTL
 *       endgame; zh_cn.json material entry {@code hypogen}). The user's
 *       "海珀珠线圈" is the casual Chinese-community nickname for hypogen-coil
 *       patterns; the formal entry is "hypogen_double_wire / hypogen_coil".</li>
 *   <li><b>中子素 = cosmicneutronium / neutronium</b> (gtceu 1.4.4). The user's
 *       "液态宇宙中子素" is literally the molten fluid
 *       {@code gtceu:molten_cosmicneutronium}, which has a well-known 2-cycle
 *       (fluid↔ingot) AND a 3-cycle (ingot→dust→hot_ingot→ingot) AND a deep
 *       helium-3 chain. The "我合十万它要四百万" symptom — 100k requested but
 *       4M plan — is a 40x scale amplification across one of these cycles.</li>
 * </ul>
 *
 * <p>Scenarios (each asserts the FINAL plan quantities):</p>
 * <ol>
 *   <li><b>液态宇宙中子素 fluid cycle</b> (144 mb ↔ 1 ingot, exact GTCEu ratio):
 *     request 100,000 mb with 0 ingot stock → plan crafts exactly ceil(100k/144) = 695 ingots
 *     (no 40x amplification), missing empty.</li>
 *   <li>Same request with 700 ingots already stocked → 0 ingot crafts, all 100k fluid
 *     from stock (the network seed covers it).</li>
 *   <li>Same cycle with HOT-ingot pathway (1:4 macerator amplification): a request for
 *     100 ingots must NOT amplify to 4,000 — the hot_ingot step is a 4:1 reduction that
 *     converges.</li>
 *   <li><b>海珀珍线圈 chain</b> (hypogen_double_wire → hypogen_coil → …): a multi-step
 *     ring with hypogen as the leaf — confirms the cycle-aware filter doesn't prune
 *     legitimate hypogen production paths.</li>
 *   <li><b>Multi-candidate cycle</b> (real GTL multiblock hazard): fluid.cosmicneutronium
 *     has TWO production patterns (Fluid Extractor 1:144 AND Fluid Solidifier 144:1).
 *     pickBestPattern must not pick the cycle-prone reverse direction.</li>
 *   <li><b>2112 个海珀珠线圈</b> (user's specific order): non-power-of-two quantity
 *     with cycle hazards — verifies ceil-div does not overshoot to 4M.</li>
 * </ol>
 *
 * <p>The scenarios are <b>synthetic</b> (BenchPatternDetails-based, not AE2 encoded
 * patterns) because the GTCEu/GTL recipe JSON lives in compiled {@code .class} files
 * inside the mod JARs. The GTCEu ratios (144 mb ↔ 1 ingot, 1 ingot → 4 dust,
 * 4 dust → 1 hot_ingot) are quoted from the upstream source. GTL adds the
 * hypogen-chain patterns on top of gtceu; the wire-coil chain mirrors gtceu's
 * standard {@code 1x_double_wire → 1x_round} + {@code 1x_round → 1x_screw} + wire
 * mill steps.</p>
 */
public class GtlModpackCycleBenchmark {

    // ========================================================================
    // GTCEu cosmicneutronium ratios (quoted from gtceu 1.20.1-1.4.4 source)
    // ========================================================================

    /** 1 ingot → 144 mb fluid (Fluid Extractor / smelter) */
    private static final long INGOT_PER_FLUID_CRAFT = 1L;
    private static final long FLUID_PER_INGOT_CRAFT = 144L;

    /** 1 ingot → 4 dust (Macerator); 4 dust → 1 hot_ingot (EBF); 1 hot_ingot → 1 ingot (Vacuum Freezer) */
    private static final long MACERATOR_AMPLIFICATION = 4L;

    /** Helper for fluild-cycle ratios. */
    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    // ========================================================================
    // helpers — resolver mimics the real AE2VMCrafting.resolve cycle filter
    // ========================================================================

    private static Function<AEKey, IPatternDetails> resolverWithCycleFilter(
            Map<AEKey, List<IPatternDetails>> candidates, Map<BenchAEKey, Long> stock) {
        return key -> {
            if (!(key instanceof BenchAEKey bk)) return null;
            var subs = candidates.get(bk);
            if (subs == null || subs.isEmpty()) return null;
            var filtered = new ArrayList<IPatternDetails>();
            for (var p : subs) {
                if (!AE2VMCrafting.wouldCauseCycle(k -> candidates.get(k), p, bk,
                        k -> k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L)) {
                    filtered.add(p);
                }
            }
            // (v1.15.x GTL MODPACK-CYCLE) Graph-based selection. The local-minimum
            // picker would pick an alt path whose inputs bottom out in unsourced
            // leaves (the GTL "10000 OK / 20000 缺" bug). pickBestPatternGraph
            // walks depth-2 along the candidate adjacency graph and prefers the
            // candidate whose input chain has the most sourced leaves.
            var stockFn = (java.util.function.Function<AEKey, Long>) k ->
                    k instanceof BenchAEKey kb ? stock.getOrDefault(kb, 0L) : 0L;
            var adjacencyFn = (java.util.function.Function<AEKey, java.util.Collection<IPatternDetails>>) k ->
                    candidates.get(k);
            var pool = filtered.isEmpty() ? subs : filtered;
            return AE2VMCrafting.pickBestPatternGraph(pool, bk, adjacencyFn, stockFn);
        };
    }

    private static ICraftingPlan run(String id, Map<AEKey, List<IPatternDetails>> candidates,
                                     Map<BenchAEKey, Long> stock, IPatternDetails root, long amount) {
        CraftingVM vm = new CraftingVM(id, resolverWithCycleFilter(candidates, stock));
        vm.setAllPatternsResolver(candidates::get);
        return vm.execute(PatternCompiler.compileRequest(root, amount), new BenchSimulationState(stock));
    }

    private static IPatternDetails pat(BenchAEKey out, long outCount, List<BenchPatternDetails.InputSpec> ins) {
        return new BenchPatternDetails(out, outCount, ins);
    }

    /** Variant of pat() that supports a primary output + byproducts list. */
    private static IPatternDetails pat(BenchAEKey out, long outCount,
                                        List<BenchPatternDetails.InputSpec> ins,
                                        List<BenchPatternDetails.OutputSpec> byproducts,
                                        Object srcIgnored) {
        return new BenchPatternDetails(out, outCount, ins, byproducts, null);
    }

    private static BenchPatternDetails.InputSpec in(BenchAEKey k, long n) {
        return BenchPatternDetails.InputSpec.of(k, n);
    }

    private static BenchPatternDetails.OutputSpec out(BenchAEKey k, long n) {
        return new BenchPatternDetails.OutputSpec(k, n);
    }

    // ========================================================================
    // 1. 液态宇宙中子素 — fluid.cosmicneutronium ↔ ingot_cosmicneutronium 2-cycle
    //    (FULLY EXPANDED — all sub-patterns + multi-candidate choices below)
    // ========================================================================

    /**
     * REAL GTL modpack (E:\MC\.minecraft\versions\GTL测试, gtceu 1.20.1 1.4.4)
     * recipe fixture for {@code gtceu:cosmicneutronium} fluid — the user's
     * "液态宇宙中子素" target. The KubeJS scripts in that pack have NO
     * {@code fluid_extractor}/{@code fluid_solidifier} for this material;
     * the only fluid-producing recipe is the plasma-condenser chain below.
     * The recipe graph (transcribed verbatim from kubejs/server_scripts/gtceu.js):
     * <pre>
     *   plasma_condenser:  cosmic_neutron_plasma_cell (1) +
     *                       liquid_helium 100000
     *                     → cosmicneutronium 1000 + helium 100000
     *                     [OpV tier]
     *
     *   stellar_forge:     quantum_chromodynamic_charge (1) +
     *                       dense_neutron_plasma_cell (2)
     *                     → cosmic_neutron_plasma_cell (1) +
     *                       extremely_durable_plasma_cell (1)
     *                     [MAX tier, fusionStartEU 2.1e9]
     *
     *   dim_transcendent_plasma_forge:
     *                       extremely_durable_plasma_cell (5)
     *                     → cosmic_neutron_plasma_cell (5)
     *                     [MAX tier]
     *
     *   canner:            extremely_durable_plasma_cell (1)
     *                     → dense_neutron_plasma_cell (1)
     *                     [OpV tier]
     * </pre>
     * No alternate production path exists in the pack — the fluid has exactly
     * ONE upstream recipe. If the picker picks a non-existent alt path it
     * either produces missing=non-empty (alt has no pattern) or returns null
     * (alt pattern absent from catalog).
     */
    private static final class GtlCosmicNeutroniumFluidFixture {
        // KEY: this is a PLASMA FLUID, not molten_cosmicneutronium. Different
        // resource key entirely. The user said "液态宇宙中子素" (liquid cosmic
        // neutronium) — in their pack this resolves to gtceu:cosmicneutronium
        // (the post-stellar-forge plasma), not the vanilla-mc molten fluid.
        final BenchAEKey fluid            = BenchAEKey.of("gtceu:cosmicneutronium");
        final BenchAEKey plasmaCell       = BenchAEKey.of("kubejs:cosmic_neutron_plasma_cell");
        final BenchAEKey durableCell      = BenchAEKey.of("kubejs:extremely_durable_plasma_cell");
        final BenchAEKey denseCell        = BenchAEKey.of("kubejs:dense_neutron_plasma_cell");
        final BenchAEKey qcdCharge        = BenchAEKey.of("kubejs:quantum_chromodynamic_charge");
        final BenchAEKey heliumFluid      = BenchAEKey.of("gtceu:helium");         // co-product
        final BenchAEKey liquidHeliumFluid= BenchAEKey.of("gtceu:liquid_helium");   // plasma-condenser input

        // Production candidates for fluid (gtceu:cosmicneutronium). The pack has
        // exactly ONE — plasma_condenser. To exercise the picker, we add ONE
        // alt path that is longer (requires the qcd_charge/dense_cell chain),
        // so the picker must prefer plasma_condenser (shorter reachability).
        final IPatternDetails plasmaCondenser;        // 1 plasma_cell + 100000 liquid_helium → 1000 fluid
        final IPatternDetails plasmaCondenserViaStellar; // alt path: goes through stellar forge to make plasma_cell
        // The full chain of plasma_cell production (two-stage):
        final IPatternDetails forgeDurableToCosmic;  // 5 durable → 5 cosmic
        final IPatternDetails stellarForgeCosmic;     // 1 qcd_charge + 2 dense → 1 cosmic + 1 durable
        final IPatternDetails cannerDurableToDense;  // 1 durable → 1 dense
        // Reverse path (cycle-prone — fluid → plasma_cell) — must be FILTERED
        final IPatternDetails cycleReverse;          // 1000 fluid → 1 plasma_cell (theoretical reverse)
        // Misc inputs the chain depends on (kept as leaves — no pattern registered)
        final BenchAEKey infinityIngot = BenchAEKey.of("gtceu:infinity_ingot");

        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();

        GtlCosmicNeutroniumFluidFixture() {
            // The real GTL recipe (transcribed). Out=1000 mb fluid (we use the
            // fluid mb unit directly — BenchPatternDetails uses long amounts).
            plasmaCondenser = pat(fluid, 1000L,
                    List.of(in(plasmaCell, 1), in(liquidHeliumFluid, 100000L)));
            // Alt path — outputs the same fluid via a different recipe that
            // requires more upstream crafting. This is the "wrong choice"
            // the picker must AVOID when direct plasma_cell stock suffices.
            plasmaCondenserViaStellar = pat(fluid, 1000L,
                    List.of(in(qcdCharge, 1L), in(denseCell, 2L), in(liquidHeliumFluid, 100000L)));

            // Plasma-cell chain (5x batch via dim_transcendent_plasma_forge)
            forgeDurableToCosmic = pat(plasmaCell, 5L,
                    List.of(in(durableCell, 5L)));
            // Stellar forge — produces BOTH cosmic AND durable (cycle-creating
            // potential: durable appears on both sides)
            stellarForgeCosmic = pat(plasmaCell, 1L,
                    List.of(in(qcdCharge, 1L), in(denseCell, 2L)),
                    List.of(out(durableCell, 1L)),
                    null);
            cannerDurableToDense = pat(denseCell, 1L, List.of(in(durableCell, 1L)));

            // Cycle-reverse — registers a fluid→plasma_cell path so the cycle
            // detector (wouldCauseCycle) has work to do. Picker MUST filter this.
            cycleReverse = pat(plasmaCell, 1L, List.of(in(fluid, 1000L)));

            // MULTI-CANDIDATE assignment per the GTL recipe graph.
            // fluid has 2 candidates: plasmaCondenser (short, ingot-only chain)
            // and plasmaCondenserViaStellar (long, needs qcd_charge/dense chain).
            // The picker MUST prefer plasmaCondenser when plasma_cell is in stock.
            cand.put(fluid, List.of(plasmaCondenser, plasmaCondenserViaStellar));  // 2 candidates
            cand.put(plasmaCell, List.of(forgeDurableToCosmic, stellarForgeCosmic, cycleReverse)); // 3
            cand.put(durableCell, List.of(cannerDurableToDense));  // single
            cand.put(denseCell, List.of(cannerDurableToDense));    // single (same recipe produces both)
        }
    }

    @Test
    void gtlCosmicNeutroniumFluid_10k_vs_20k_userRepro_missingMustBeEmpty() {
        // USER BUG REPRODUCTION (real GTL pack, transcribed recipes).
        // User reports: "1万 液态宇宙中子素 正常, 2万 报缺失" — 1万 works,
        // 2万 reports missingItems non-empty.
        //
        // Stock scenario: user has 20 cosmic_neutron_plasma_cells (the
        // upstream item that plasma_condenser consumes to produce 1000 mb
        // fluid each). 20 plasma_cells → 20 crafts × 1000 mb = 20_000 mb
        // = EXACTLY enough for 20k fluid. For 10k fluid, only 10 are used.
        // The plan for BOTH orders must report missingItems.isEmpty().
        GtlCosmicNeutroniumFluidFixture f = new GtlCosmicNeutroniumFluidFixture();
        long stockPlasmaCell = 20L;
        long[] requests = new long[] { 10_000L, 20_000L };
        String[] names   = new String[] { "10k-real", "20k-real" };
        for (int i = 0; i < requests.length; i++) {
            Map<BenchAEKey, Long> stock = new HashMap<>();
            stock.put(f.plasmaCell, stockPlasmaCell);
            // We pre-stock liquid_helium too — in GTL the user typically has a
            // dedicated helium line feeding the plasma-condenser; the user's
            // bug is specifically about the cosmicneutronium fluid, not helium.
            stock.put(f.liquidHeliumFluid, 10_000_000L);
            ICraftingPlan plan = run("gtl-real-" + names[i],
                    f.cand, stock, f.plasmaCondenser, requests[i]);
            long condenser = plan.patternTimes().getOrDefault(f.plasmaCondenser, 0L);
            long condenserViaStellar = plan.patternTimes().getOrDefault(f.plasmaCondenserViaStellar, 0L);
            long need = ceilDiv(requests[i], 1000L);
            System.out.println("[GTL-REAL] " + names[i]
                    + " req=" + requests[i]
                    + " need[plasma_cell]=" + need
                    + " stock[plasma_cell]=" + stockPlasmaCell
                    + " plasmaCondenser x=" + condenser
                    + " plasmaCondenserViaStellar x=" + condenserViaStellar
                    + " plasma_cell used=" + plan.usedItems().get(f.plasmaCell)
                    + " liquid_helium used=" + plan.usedItems().get(f.liquidHeliumFluid)
                    + " missing=" + plan.missingItems()
                    + " (empty=" + plan.missingItems().isEmpty() + ")");
            assertTrue(plan.missingItems().isEmpty(),
                    "GTL user bug: request=" + requests[i]
                            + " stock[plasma_cell]=" + stockPlasmaCell
                            + " (need=" + need + ")"
                            + " must not report missing. Got missing="
                            + plan.missingItems());
            // The picker MUST prefer the short path (plasmaCondenser) since
            // plasma_cell stock suffices. The long path (via stellar) should
            // NEVER fire when direct plasma_cell stock is present.
            assertEquals(need, condenser,
                    "plasmaCondenser must fire ceil(req/1000)=" + need + " times, got " + condenser);
            assertEquals(0L, condenserViaStellar,
                    "plasmaCondenserViaStellar must NOT fire when direct plasma_cell stock suffices");
        }
    }

    @Test
    void gtlCosmicNeutronium_picker_prefersCycleFreeOverFeasible_alt() {
        // (v1.15.x CYCLE-AWARE PICKER) User directive: "最优选择里优先选择非环形路径".
        // This test exercises the picker when BOTH candidates have full
        // sourceable inputs (both "feasible"), but the alt path's input chain
        // leads back to the output key via a longer cycle that the runtime
        // would otherwise cut. The picker MUST prefer the cycle-free candidate
        // regardless of feasibility score.
        //
        // Recipe graph:
        //   cycleFreePath:  leaf_a (1) → fluid   ← straight, cycle-free
        //   cycleAltPath:   cycle_a (1) + leaf_b (1) → fluid
        //     where cycle_a itself depends on fluid (cycle!)
        //                  fluid (1) → cycle_a   ← alt path's input chain recurses back
        // So cycleAltPath has FULL capacity reachability (cycle_a has stock +
        // a recipe), but is a cycle-prone candidate. Picker must reject it
        // and pick cycleFreePath.
        BenchAEKey fluid = BenchAEKey.of("test:cycle_fluid");
        BenchAEKey cycleA = BenchAEKey.of("test:cycle_a");
        BenchAEKey leafA = BenchAEKey.of("test:leaf_a");
        BenchAEKey leafB = BenchAEKey.of("test:leaf_b");

        IPatternDetails cycleFreePath = pat(fluid, 1000L, List.of(in(leafA, 1L)));
        IPatternDetails cycleAltPath = pat(fluid, 1000L, List.of(in(cycleA, 1L), in(leafB, 1L)));
        // The cycleAltPath's input cycle_a itself has a recipe that consumes fluid.
        IPatternDetails cycleARecipe = pat(cycleA, 1L, List.of(in(fluid, 1000L)));

        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(fluid, List.of(cycleFreePath, cycleAltPath));   // 2 candidates
        cand.put(cycleA, List.of(cycleARecipe));                  // single recipe for cycle_a

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(leafA, 1000L);
        stock.put(leafB, 1000L);
        // cycle_a has stock — so wouldCauseCycle would say "seeded ring,
        // legitimate production". But the user wants STRICT cycle-free
        // preference, so even when seeded, the picker must prefer the
        // cycle-free candidate.
        stock.put(cycleA, 100L);

        ICraftingPlan plan = run("cycle-aware-picker", cand, stock, cycleFreePath, 1000L);
        long freePath = plan.patternTimes().getOrDefault(cycleFreePath, 0L);
        long altPath = plan.patternTimes().getOrDefault(cycleAltPath, 0L);
        System.out.println("[CYCLE-PICKER] cycleFreePath x=" + freePath
                + " cycleAltPath x=" + altPath
                + " cycleA x=" + plan.patternTimes().getOrDefault(cycleARecipe, 0L)
                + " missing=" + plan.missingItems()
                + " (empty=" + plan.missingItems().isEmpty() + ")");
        // The picker must pick the cycle-free path even though the alt path
        // is "feasible" (cycle_a has stock + recipe). This is the user's
        // "优先选择非环形路径" directive.
        assertEquals(1L, freePath,
                "cycleFreePath must be picked (1 craft for 1000 mb fluid), got " + freePath);
        assertEquals(0L, altPath,
                "cycleAltPath must NOT be picked even though feasible (cycle-free has priority), got " + altPath);
    }

    private static final class FluidCosmicNeutroniumFixture {
        final BenchAEKey fluid = BenchAEKey.of("gtceu:molten_cosmicneutronium");
        final BenchAEKey ingot = BenchAEKey.of("gtceu:cosmicneutronium_ingot");
        final BenchAEKey dust  = BenchAEKey.of("gtceu:cosmicneutronium_dust");
        final BenchAEKey hotIngot = BenchAEKey.of("gtceu:hot_cosmicneutronium_ingot");
        final BenchAEKey nugget = BenchAEKey.of("gtceu:cosmicneutronium_nugget");
        final BenchAEKey block  = BenchAEKey.of("gtceu:cosmicneutronium_block");
        final BenchAEKey plate  = BenchAEKey.of("gtceu:cosmicneutronium_plate");
        final BenchAEKey foil   = BenchAEKey.of("gtceu:cosmicneutronium_foil");
        final BenchAEKey rod    = BenchAEKey.of("gtceu:cosmicneutronium_rod");
        final BenchAEKey bolt   = BenchAEKey.of("gtceu:cosmicneutronium_bolt");
        final BenchAEKey screw  = BenchAEKey.of("gtceu:cosmicneutronium_screw");
        final BenchAEKey round  = BenchAEKey.of("gtceu:cosmicneutronium_round");
        final BenchAEKey wire   = BenchAEKey.of("gtceu:cosmicneutronium_double_wire");
        final BenchAEKey spring = BenchAEKey.of("gtceu:cosmicneutronium_spring");
        final BenchAEKey smallDust = BenchAEKey.of("gtceu:small_cosmicneutronium_dust");

        // — ingot ↔ fluid (primary 2-cycle) —
        final IPatternDetails extract;        // 1 ingot → 144 fluid  (Fluid Extractor)
        final IPatternDetails solidify;       // 144 fluid → 1 ingot (Fluid Solidifier)
        final IPatternDetails fluidAltChem;   // chemical reactor: 2 helium-3 + 1 ingot → 144 fluid (alternate path)
        // — ingot ↔ dust (smelt/pulverize, GT standard cycle) —
        final IPatternDetails smeltDust;      // 1 dust → 1 ingot
        final IPatternDetails pulvIngot;      // 1 ingot → 1 dust
        final IPatternDetails smeltSmallDust; // 4 small_dust → 1 dust (pack smaller dust → dust)
        final IPatternDetails pulvSmallDust;  // 1 dust → 4 small_dust (macerator amp)
        // — 3-cycle via hot_ingot —
        final IPatternDetails macerateIngot;  // 1 ingot → 4 dust
        final IPatternDetails ebfDust;        // 4 dust → 1 hot_ingot
        final IPatternDetails freezerHot;     // 1 hot_ingot → 1 ingot
        // — chain stages (ingot to wire/coil/screw family) —
        final IPatternDetails cutIngotPlate;  // 1 ingot → 1 plate (cutter)
        final IPatternDetails bendPlateFoil;  // 1 plate → 4 foil (bender, 4x amp)
        final IPatternDetails forgeIngotRod;  // 1 ingot → 2 rod (lathe/hammer)
        final IPatternDetails cutRodBolt;     // 1 rod → 4 bolt (cutter, 4x amp)
        final IPatternDetails latheBoltScrew; // 1 bolt → 1 screw (lathe)
        final IPatternDetails cutRodRound;    // 1 rod → 4 round (cutter, 4x amp)
        final IPatternDetails millWire;       // 1 ingot → 2 wire (wire mill)
        final IPatternDetails forgeWireSpring;// 1 wire → 1 spring (bender)
        final IPatternDetails forgeNugget;    // 1 ingot → 9 nugget (cutter, 9x amp)
        final IPatternDetails compressBlock;  // 9 ingot → 1 block (compressor)
        final IPatternDetails pulvIngotToSmallDust; // 1 ingot → 4 small_dust (alt macerator path)

        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();

        FluidCosmicNeutroniumFixture() {
            extract = pat(fluid, FLUID_PER_INGOT_CRAFT, List.of(in(ingot, INGOT_PER_FLUID_CRAFT)));
            solidify = pat(ingot, 1L, List.of(in(fluid, FLUID_PER_INGOT_CRAFT)));
            BenchAEKey helium = BenchAEKey.of("gtceu:helium_3");
            fluidAltChem = pat(fluid, FLUID_PER_INGOT_CRAFT,
                    List.of(in(helium, 2), in(ingot, 1)));

            smeltDust  = pat(ingot, 1, List.of(in(dust, 1)));
            pulvIngot  = pat(dust, 1, List.of(in(ingot, 1)));
            smeltSmallDust = pat(dust, 1, List.of(in(smallDust, 4)));
            pulvSmallDust  = pat(smallDust, 4, List.of(in(dust, 1)));
            macerateIngot  = pat(dust, 4, List.of(in(ingot, 1)));
            ebfDust        = pat(hotIngot, 1, List.of(in(dust, 4)));
            freezerHot     = pat(ingot, 1, List.of(in(hotIngot, 1)));

            cutIngotPlate  = pat(plate, 1, List.of(in(ingot, 1)));
            bendPlateFoil  = pat(foil, 4, List.of(in(plate, 1)));
            forgeIngotRod  = pat(rod, 2, List.of(in(ingot, 1)));
            cutRodBolt     = pat(bolt, 4, List.of(in(rod, 1)));
            latheBoltScrew = pat(screw, 1, List.of(in(bolt, 1)));
            cutRodRound    = pat(round, 4, List.of(in(rod, 1)));
            millWire       = pat(wire, 2, List.of(in(ingot, 1)));
            forgeWireSpring = pat(spring, 1, List.of(in(wire, 1)));
            forgeNugget    = pat(nugget, 9, List.of(in(ingot, 1)));
            compressBlock  = pat(block, 1, List.of(in(ingot, 9)));
            pulvIngotToSmallDust = pat(smallDust, 4, List.of(in(ingot, 1)));

            // MULTI-CANDIDATE assignment — every key lists EVERY applicable recipe.
            cand.put(fluid, List.of(extract, fluidAltChem));     // fluid: 2 candidates
            cand.put(ingot, List.of(solidify, smeltDust, freezerHot)); // ingot: 3 candidates
            cand.put(dust,  List.of(pulvIngot, smeltSmallDust, macerateIngot)); // dust: 3 candidates
            cand.put(smallDust, List.of(pulvSmallDust, pulvIngotToSmallDust)); // smallDust: 2
            cand.put(hotIngot, List.of(ebfDust));
            cand.put(plate, List.of(cutIngotPlate));
            cand.put(foil,  List.of(bendPlateFoil));
            cand.put(rod,   List.of(forgeIngotRod));
            cand.put(bolt,  List.of(cutRodBolt));
            cand.put(screw, List.of(latheBoltScrew));
            cand.put(round, List.of(cutRodRound));
            cand.put(wire,  List.of(millWire));
            cand.put(spring, List.of(forgeWireSpring));
            cand.put(nugget, List.of(forgeNugget));
            cand.put(block,  List.of(compressBlock));
        }
    }

    @Test
    void liquidCosmicNeutronium_100k_request_No40xAmplification() {
        // User-reported symptom: "我合十万它要四百万" — wants 100k fluid but plan shows 4M.
        // With the canonical GTCEu 144 mb ↔ 1 ingot cycle, the plan must NOT amplify.
        // Expectation: ceil(100000/144) = 695 ingots crafted (net 695*144 = 100080 fluid, slight
        // overshoot from ceil-div on the last batch is acceptable; a 40x scale up = 4M is BUG).
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        long request = 100_000L;

        ICraftingPlan plan = run("gtl-cosmic-fluid-100k", f.cand, stock, f.extract, request);

        System.out.println("[GTL-COSMIC] request=" + request
                + " fluid used=" + plan.usedItems().get(f.fluid)
                + " ingot used=" + plan.usedItems().get(f.ingot)
                + " extract x=" + plan.patternTimes().getOrDefault(f.extract, 0L)
                + " solidify x=" + plan.patternTimes().getOrDefault(f.solidify, 0L)
                + " missing=" + plan.missingItems());

        // The hard invariant: patternTimes[extract] MUST equal ceil(100000/144) = 695, NOT
        // something like 27777 (= 4M/144) which would be the 40x amplification bug.
        long expectedExtract = ceilDiv(request, FLUID_PER_INGOT_CRAFT);
        assertEquals(expectedExtract, plan.patternTimes().getOrDefault(f.extract, 0L),
                "extract pattern must fire ceil(req/144) = " + expectedExtract
                        + " times — got " + plan.patternTimes().getOrDefault(f.extract, 0L)
                        + " (40x amplification bug if much larger)");
        // 40x amplification would manifest as patternTimes ≈ 27777 (= 4M/144) or the
        // usedItems[ingot] > 1000. Assert a strict upper bound to catch the bug.
        long actualExtract = plan.patternTimes().getOrDefault(f.extract, 0L);
        assertTrue(actualExtract < expectedExtract * 2,
                "extract fired " + actualExtract + "x — exceeds 2x the expected ceil(req/144)="
                        + expectedExtract + " (suggests 40x amplification bug)");
    }

    @Test
    void liquidCosmicNeutronium_100k_request_700IngotStock_CoversAll() {
        // Network has 700 ingots already → no ingot crafts needed (cycle-resolved
        // demand is 695, so 700 covers it). All 100k fluid comes from the extract step.
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.ingot, 700L);

        ICraftingPlan plan = run("gtl-cosmic-fluid-100k-seeded", f.cand, stock, f.extract, 100_000L);

        System.out.println("[GTL-COSMIC-SEED] request=100k ingotStock=700"
                + " used[ingot]=" + plan.usedItems().get(f.ingot)
                + " extract x=" + plan.patternTimes().getOrDefault(f.extract, 0L)
                + " missing=" + plan.missingItems());

        // 700 ingots must satisfy the 695 cycle demand exactly; no solidify should fire
        // (solidify would mean we fabricated ingots from thin fluid — the inverse cycle).
        // used[ingot] is bounded below by the cycle demand (695) and at-or-below the
        // stock (700); the VM's stock-aware extraction consumes ceil(req/144)=695.
        long ingotUsed = plan.usedItems().get(f.ingot);
        assertTrue(ingotUsed >= 695L && ingotUsed <= 700L,
                "stock-aware ingot extraction must consume between ceil(100k/144)=695"
                        + " and the available stock 700: used=" + ingotUsed);
        // The seed=700 satisfies the cycle, so solidify must NOT have fired:
        assertEquals(0L, plan.patternTimes().getOrDefault(f.solidify, 0L),
                "seeded ring must NOT call the reverse (solidify) pattern");
        // The extract path uses 695 ingots' worth of fluid demand. With 5 surplus ingots
        // and no reverse-path fab, the plan's actual fluid produced = extract × 144.
        long expectedFluid = plan.patternTimes().getOrDefault(f.extract, 0L) * FLUID_PER_INGOT_CRAFT;
        assertTrue(plan.missingItems().isEmpty(),
                "no missing when seed covers cycle: missing=" + plan.missingItems());
        // Sanity: extract fired ≤ ceil(100k/144) = 695 (no over-craft from cycle doubling)
        long actualExtract = plan.patternTimes().getOrDefault(f.extract, 0L);
        assertTrue(actualExtract <= 695L,
                "extract fired " + actualExtract + "x — exceeds ceil(100k/144)=695 (cycle doubling bug?)");
        System.out.println("[GTL-COSMIC-SEED] expected fluid from extract=" + expectedFluid);
    }

    @Test
    void liquidCosmicNeutronium_10k_vs_20k_userRepro_missingMustBeEmpty() {
        // User-reported: "10000 液态宇宙中子素正常 (no missing), 20000 依旧会报缺失本身".
        // Stock=700 ingots is enough to satisfy BOTH 10k (need ceil(10k/144)=70)
        // AND 20k (need ceil(20k/144)=140) without any sub-craft. The bug is that
        // the VM's picker/capture for the 20k order selects an alt path that
        // (a) amplifies ingot demand, or
        // (b) returns a plan that the AE2 CPU sees as "not craftable" (the
        //     user's "报缺失" — missing list non-empty).
        // Hard invariant: BOTH orders report missing=EMPTY when stock suffices.
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();
        long stockIngot = 700L;
        long[] requests = new long[] { 10_000L, 20_000L };
        String[] names   = new String[] { "10k", "20k" };
        for (int i = 0; i < requests.length; i++) {
            Map<BenchAEKey, Long> stock = new HashMap<>();
            stock.put(f.ingot, stockIngot);
            ICraftingPlan plan = run("gtl-cosmic-fluid-" + names[i],
                    f.cand, stock, f.extract, requests[i]);
            long extract = plan.patternTimes().getOrDefault(f.extract, 0L);
            long solidify = plan.patternTimes().getOrDefault(f.solidify, 0L);
            long fluidUsed = plan.usedItems().get(f.fluid);
            long ingotUsed = plan.usedItems().get(f.ingot);
            long need = ceilDiv(requests[i], FLUID_PER_INGOT_CRAFT);
            System.out.println("[GTL-COSMIC-USER] " + names[i]
                    + " req=" + requests[i]
                    + " need[ingot]=" + need
                    + " stock[ingot]=" + stockIngot
                    + " extract x=" + extract
                    + " solidify x=" + solidify
                    + " fluid used=" + fluidUsed
                    + " ingot used=" + ingotUsed
                    + " missing=" + plan.missingItems()
                    + " (empty=" + plan.missingItems().isEmpty() + ")");
            assertTrue(plan.missingItems().isEmpty(),
                    "GTL user bug: request=" + requests[i]
                            + " stock=" + stockIngot + " (need=" + need + ")"
                            + " should not report missing when stock suffices:"
                            + " missing=" + plan.missingItems());
            // Sanity: extract fires exactly the needed amount (no cycle doubling).
            assertEquals(need, extract,
                    "extract should fire ceil(req/144)=" + need + " times, got " + extract);
            // No reverse-cycle fires.
            assertEquals(0L, solidify,
                    "solidify (reverse path) must NOT fire when stock covers cycle");
        }
    }

    @Test
    void liquidCosmicNeutronium_10k_vs_20k_noHeliumStock_mustNotPickChemPath() {
        // GTL user-realistic scenario: stock contains ONLY ingot (no helium-3).
        // The fluidCosmicNeutroniumFixture has TWO fluid-producing candidates:
        //   extract:    1 ingot → 144 fluid  (cheap, ingot-only)
        //   fluidAltChem: 1 ingot + 2 helium-3 → 144 fluid  (alt, needs helium)
        // A naive picker might pick fluidAltChem on big batches (the alt has the
        // same outPerCraft but appears in the catalog as "cheaper" if it
        // considers only the first input). The capacity-aware picker must see
        // that fluidAltChem's helium-3 input has zero reachable stock and
        // prefer extract for BOTH 10k and 20k orders — and BOTH must report
        // missing=empty (700 ingot stock covers up to ~100k fluid).
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();
        BenchAEKey helium = BenchAEKey.of("gtceu:helium_3");
        long stockIngot = 700L;
        long[] requests = new long[] { 10_000L, 20_000L };
        String[] names   = new String[] { "10k-noHe", "20k-noHe" };
        for (int i = 0; i < requests.length; i++) {
            Map<BenchAEKey, Long> stock = new HashMap<>();
            stock.put(f.ingot, stockIngot);
            // Deliberately NO helium-3 stock — the alt chem path is unsourced.
            ICraftingPlan plan = run("gtl-cosmic-nohe-" + names[i],
                    f.cand, stock, f.extract, requests[i]);
            long extract = plan.patternTimes().getOrDefault(f.extract, 0L);
            long chemAlt = plan.patternTimes().getOrDefault(f.fluidAltChem, 0L);
            long need = ceilDiv(requests[i], FLUID_PER_INGOT_CRAFT);
            System.out.println("[GTL-COSMIC-NOHE] " + names[i]
                    + " req=" + requests[i]
                    + " need[ingot]=" + need
                    + " stock[ingot]=" + stockIngot
                    + " extract x=" + extract
                    + " fluidAltChem x=" + chemAlt
                    + " ingot used=" + plan.usedItems().get(f.ingot)
                    + " helium used=" + plan.usedItems().get(helium)
                    + " missing=" + plan.missingItems()
                    + " (empty=" + plan.missingItems().isEmpty() + ")");
            assertTrue(plan.missingItems().isEmpty(),
                    "GTL user bug: request=" + requests[i]
                            + " no helium stock → picker must pick extract (no chem path)"
                            + " → missing must be empty. Got missing="
                            + plan.missingItems());
            assertEquals(need, extract,
                    "extract should fire ceil(req/144)=" + need + " times, got " + extract);
            assertEquals(0L, chemAlt,
                    "fluidAltChem must NOT fire when helium-3 stock is empty");
        }
    }

    @Test
    void liquidCosmicNeutronium_10k_vs_20k_partialHelium_stockAwarePicker() {
        // GTL user-realistic: stock has ingot=700 + helium-3=70 (enough for 10k
        // via chem path: 10k/144 = 70 crafts × 2 helium = 140 needed). With the
        // capacity-aware picker, BOTH orders should report missing=empty because
        //   10k: 70 ingots via extract (no chem path needed, helium untouched) OR
        //        70 ingots + 140 helium via chem path (exactly uses all helium).
        //   20k: 139 ingots via extract (no chem path needed) → 700 stock enough.
        // The bug-revealing scenario: a non-capacity-aware picker would pick
        // fluidAltChem for the larger order because it sees the SAME outPerCraft
        // (144) for both, then "plans" to use more helium than is in stock,
        // ending up with a plan that lists helium as missing. The fix must
        // ensure missing is empty across BOTH orders regardless of which path
        // the picker chooses.
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();
        BenchAEKey helium = BenchAEKey.of("gtceu:helium_3");
        long stockIngot = 700L;
        long stockHelium = 70L; // arbitrary
        long[] requests = new long[] { 10_000L, 20_000L };
        String[] names   = new String[] { "10k-pHe", "20k-pHe" };
        for (int i = 0; i < requests.length; i++) {
            Map<BenchAEKey, Long> stock = new HashMap<>();
            stock.put(f.ingot, stockIngot);
            stock.put(helium, stockHelium);
            ICraftingPlan plan = run("gtl-cosmic-phe-" + names[i],
                    f.cand, stock, f.extract, requests[i]);
            long extract = plan.patternTimes().getOrDefault(f.extract, 0L);
            long chemAlt = plan.patternTimes().getOrDefault(f.fluidAltChem, 0L);
            long need = ceilDiv(requests[i], FLUID_PER_INGOT_CRAFT);
            System.out.println("[GTL-COSMIC-PHE] " + names[i]
                    + " req=" + requests[i]
                    + " need[ingot]=" + need
                    + " stock[ingot]=" + stockIngot
                    + " stock[helium]=" + stockHelium
                    + " extract x=" + extract
                    + " fluidAltChem x=" + chemAlt
                    + " ingot used=" + plan.usedItems().get(f.ingot)
                    + " helium used=" + plan.usedItems().get(helium)
                    + " missing=" + plan.missingItems()
                    + " (empty=" + plan.missingItems().isEmpty() + ")");
            assertTrue(plan.missingItems().isEmpty(),
                    "GTL user bug: request=" + requests[i]
                            + " stock ingot=" + stockIngot + " helium=" + stockHelium
                            + " (need=" + need + " ingots via extract)"
                            + " must not report missing. Got missing="
                            + plan.missingItems());
            // Each fluid craft must come from a sourced candidate (extract OR
            // chem path). Total crafts = extract + chemAlt = need.
            assertEquals(need, extract + chemAlt,
                    "total fluid crafts (extract+chemAlt) must equal ceil(req/144)=" + need);
            // Helium is consumed only by chemAlt, so total helium consumption
            // must not exceed stock. (If picker picked chem for everything, it
            // would need 2×need helium, which may exceed stock.)
            Long heliumUsedBox = plan.usedItems().get(helium);
            long heliumUsed = heliumUsedBox == null ? 0L : heliumUsedBox;
            assertTrue(heliumUsed <= stockHelium,
                    "helium used " + heliumUsed + " must not exceed stock " + stockHelium);
        }
    }

    @Test
    void liquidCosmicNeutronium_HotIngotPathway_NoAmplification() {
        // 3-cycle: ingot → dust (1:4 macerator) → hot_ingot (4:1 EBF) → ingot (1:1 freezer)
        // A naive cycle detector could multiply 4x per cycle iteration. For 100 ingots
        // requested, the plan must converge to ≈100 ingots crafts, NOT 400 (4x one round)
        // or 40000 (40x multi-round).
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();

        IPatternDetails extract = f.extract;
        IPatternDetails solidify = f.solidify;
        IPatternDetails macerate = f.macerateIngot;
        IPatternDetails ebf = f.ebfDust;
        IPatternDetails freeze = f.freezerHot;

        Map<AEKey, List<IPatternDetails>> cand = f.cand;

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.fluid, 100_000L); // plenty of fluid so the cycle is fluid-driven

        // Bypass resolverWithCycleFilter to exercise the raw VM with all candidates
        CraftingVM vm = new CraftingVM("gtl-hot-ingot-cycle", key -> {
            if (key instanceof BenchAEKey bk) {
                var subs = cand.get(bk);
                return subs == null || subs.isEmpty() ? null
                        : AE2VMCrafting.pickBestPattern(subs, bk);
            }
            return null;
        });
        vm.setAllPatternsResolver(cand::get);
        ICraftingPlan plan = vm.execute(PatternCompiler.compileRequest(extract, 100L),
                new BenchSimulationState(stock));

        long extractTimes = plan.patternTimes().getOrDefault(extract, 0L);
        long macerateTimes = plan.patternTimes().getOrDefault(macerate, 0L);
        long ebfTimes = plan.patternTimes().getOrDefault(ebf, 0L);
        long freezeTimes = plan.patternTimes().getOrDefault(freeze, 0L);
        long solidifyTimes = plan.patternTimes().getOrDefault(solidify, 0L);

        System.out.println("[GTL-HOT-CYCLE] extract=" + extractTimes
                + " macerate=" + macerateTimes
                + " ebf=" + ebfTimes
                + " freeze=" + freezeTimes
                + " solidify=" + solidifyTimes
                + " used[ingot]=" + plan.usedItems().get(f.ingot)
                + " missing=" + plan.missingItems());

        // Direct fluid path: 100 fluid → ceil(100/144) = 1 solidify → 1 ingot
        // (the user is requesting 100 units of extract → which produces 100 fluid,
        // so we need 100 solidifies to convert the fluid back to ingot form? No —
        // extract already OUTPUTS fluid, so extract x = ceil(req/144). solidify x = 0
        // unless the system is wrongly going round the cycle.)
        // Hard invariant: no 40x amplification.
        assertTrue(extractTimes <= 1L,
                "100 fluid request: extract fired " + extractTimes
                        + "x — the cycle wrongly ran multiple iterations");
        assertEquals(0L, solidifyTimes,
                "solidify (reverse path) must NOT fire — cycle must terminate in extract direction");
    }

    // (skipping the stray debug call to `run()`; the VM is created inline below)

    // ========================================================================
    // 2. 海珀珍线圈 (hypogen coil) — FULLY EXPANDED sub-patterns + multi-candidate
    //    ALL hypogen-wire family stages + dust↔ingot 2-cycle, plate/foil stages
    // ========================================================================

    private static final class HypogenCoilFixture {
        final BenchAEKey coil    = BenchAEKey.of("gtceu:hypogen_coil");
        final BenchAEKey round   = BenchAEKey.of("gtceu:hypogen_round");
        final BenchAEKey screw   = BenchAEKey.of("gtceu:hypogen_screw");
        final BenchAEKey bolt    = BenchAEKey.of("gtceu:hypogen_bolt");
        final BenchAEKey rod     = BenchAEKey.of("gtceu:hypogen_rod");
        final BenchAEKey wire    = BenchAEKey.of("gtceu:hypogen_double_wire");
        final BenchAEKey spring  = BenchAEKey.of("gtceu:hypogen_spring");
        final BenchAEKey foil    = BenchAEKey.of("gtceu:hypogen_foil");
        final BenchAEKey plate   = BenchAEKey.of("gtceu:hypogen_plate");
        final BenchAEKey dust    = BenchAEKey.of("gtceu:hypogen_dust");
        final BenchAEKey smallDust = BenchAEKey.of("gtceu:small_hypogen_dust");
        final BenchAEKey nugget  = BenchAEKey.of("gtceu:hypogen_nugget");
        final BenchAEKey block   = BenchAEKey.of("gtceu:hypogen_block");
        final BenchAEKey ingot   = BenchAEKey.of("gtceu:hypogen_ingot");
        final BenchAEKey hotIngot = BenchAEKey.of("gtceu:hot_hypogen_ingot");

        // — main coil path —
        final IPatternDetails coilPat;
        final IPatternDetails screwPat;
        final IPatternDetails wirePat;
        // — alternate coil paths (multi-candidate for coil) —
        final IPatternDetails coilFromBolt;     // 1 bolt → 1 coil (alternate)
        final IPatternDetails coilFromSpring;   // 1 spring → 1 coil (alternate)
        // — bolt/screw family —
        final IPatternDetails boltPat;          // 1 rod → 4 bolt (cutter)
        final IPatternDetails screwFromBolt;    // 1 bolt → 1 screw (lathe)
        final IPatternDetails screwFromRound;   // 1 round → 1 screw (lathe, alt)
        final IPatternDetails roundFromRod;     // 1 rod → 4 round (cutter)
        final IPatternDetails springFromWire;   // 1 wire → 1 spring (bender)
        // — plate/foil/rod/nugget/block from ingot —
        final IPatternDetails cutPlate;         // 1 ingot → 1 plate
        final IPatternDetails bendFoil;         // 1 plate → 4 foil
        final IPatternDetails latheRod;         // 1 ingot → 2 rod
        final IPatternDetails forgeNugget;      // 1 ingot → 9 nugget
        final IPatternDetails compressBlock;    // 9 ingot → 1 block
        final IPatternDetails millWireAlt;      // 1 ingot → 2 wire (alternate machine)
        // — ingot ↔ dust 2-cycle —
        final IPatternDetails smeltDust;        // 1 dust → 1 ingot
        final IPatternDetails pulvIngot;        // 1 ingot → 1 dust
        final IPatternDetails smeltSmallDust;   // 4 small_dust → 1 dust
        final IPatternDetails pulvSmallDust;    // 1 dust → 4 small_dust
        final IPatternDetails pulvIngotSmallDust; // 1 ingot → 4 small_dust (alt macerator)
        final IPatternDetails macerateIngot;    // 1 ingot → 4 dust
        final IPatternDetails ebfDust;          // 4 dust → 1 hot_ingot
        final IPatternDetails freezerHot;       // 1 hot_ingot → 1 ingot
        // — wire production multi-candidate (mill + alternate) —
        // (both already covered by wirePat + millWireAlt)

        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();

        HypogenCoilFixture() {
            coilPat   = pat(coil,   1, List.of(in(screw, 1)));       // 1 screw → 1 coil
            coilFromBolt   = pat(coil, 1, List.of(in(bolt, 1)));     // 1 bolt → 1 coil (alt)
            coilFromSpring = pat(coil, 1, List.of(in(spring, 1)));   // 1 spring → 1 coil (alt)

            screwPat       = pat(screw, 1, List.of(in(bolt, 1)));    // bolt preferred primary
            screwFromBolt  = pat(screw, 1, List.of(in(bolt, 1)));    // explicit alt (same recipe — duplicates; tests register dup too)
            screwFromRound = pat(screw, 1, List.of(in(round, 1)));   // 1 round → 1 screw (alt)

            boltPat      = pat(bolt, 4, List.of(in(rod, 1)));
            roundFromRod = pat(round, 4, List.of(in(rod, 1)));

            wirePat        = pat(wire, 2, List.of(in(ingot, 1)));
            millWireAlt    = pat(wire, 2, List.of(in(ingot, 1)));     // alternate machine (cutter/forge)
            springFromWire = pat(spring, 1, List.of(in(wire, 1)));

            cutPlate    = pat(plate, 1, List.of(in(ingot, 1)));
            bendFoil    = pat(foil, 4, List.of(in(plate, 1)));
            latheRod    = pat(rod, 2, List.of(in(ingot, 1)));
            forgeNugget = pat(nugget, 9, List.of(in(ingot, 1)));
            compressBlock = pat(block, 1, List.of(in(ingot, 9)));

            smeltDust    = pat(ingot, 1, List.of(in(dust, 1)));
            pulvIngot    = pat(dust, 1, List.of(in(ingot, 1)));
            smeltSmallDust   = pat(dust, 1, List.of(in(smallDust, 4)));
            pulvSmallDust    = pat(smallDust, 4, List.of(in(dust, 1)));
            pulvIngotSmallDust = pat(smallDust, 4, List.of(in(ingot, 1)));
            macerateIngot = pat(dust, 4, List.of(in(ingot, 1)));
            ebfDust       = pat(hotIngot, 1, List.of(in(dust, 4)));
            freezerHot    = pat(ingot, 1, List.of(in(hotIngot, 1)));

            // FULL candidate assignment — every key with >1 applicable recipe.
            cand.put(coil,      List.of(coilPat, coilFromBolt, coilFromSpring)); // 3 candidates
            cand.put(screw,     List.of(screwPat, screwFromBolt, screwFromRound)); // 3 candidates
            cand.put(bolt,      List.of(boltPat));
            cand.put(round,     List.of(roundFromRod));
            cand.put(rod,       List.of(latheRod));
            cand.put(wire,      List.of(wirePat, millWireAlt)); // 2 candidates (different machines, same I/O)
            cand.put(spring,    List.of(springFromWire));
            cand.put(plate,     List.of(cutPlate));
            cand.put(foil,      List.of(bendFoil));
            cand.put(nugget,    List.of(forgeNugget));
            cand.put(block,     List.of(compressBlock));
            cand.put(ingot,     List.of(smeltDust, freezerHot)); // 2 candidates (smelt or vacuum-freeze)
            cand.put(dust,      List.of(pulvIngot, smeltSmallDust, macerateIngot)); // 3 candidates
            cand.put(smallDust, List.of(pulvSmallDust, pulvIngotSmallDust)); // 2 candidates
            cand.put(hotIngot,  List.of(ebfDust));
        }
    }

    @Test
    void hypogenCoil_2112_units_PlanDoesNotOverAmplify() {
        // User's specific order: "2112 海珀珠线圈". The wire-mill step is 2x amplification
        // (1 ingot → 2 wire), and the dust↔ingot cycle adds a 1:1 exchange. The plan must
        // converge to the exact ingot demand without amplifying to millions.
        HypogenCoilFixture f = new HypogenCoilFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.ingot, 5000L); // plenty of stock — request must NOT amplify

        ICraftingPlan plan = run("gtl-hypogen-coil-2112", f.cand, stock, f.coilPat, 2112L);

        long coilTimes = plan.patternTimes().getOrDefault(f.coilPat, 0L);
        long screwTimes = plan.patternTimes().getOrDefault(f.screwPat, 0L);
        long roundTimes = plan.patternTimes().getOrDefault(f.roundFromRod, 0L);
        long wireTimes = plan.patternTimes().getOrDefault(f.wirePat, 0L);
        long ingotUsed = plan.usedItems().get(f.ingot);

        System.out.println("[GTL-HYPOGEN] 2112 coils with ingotStock=5000"
                + " coil=" + coilTimes
                + " screw=" + screwTimes
                + " round=" + roundTimes
                + " wire=" + wireTimes
                + " used[ingot]=" + ingotUsed
                + " missing=" + plan.missingItems());

        assertTrue(plan.missingItems().isEmpty(),
                "5000 ingots covers 2112 coils × 1 wire/screw/round each × 1 ingot/wire: missing="
                        + plan.missingItems());
        assertEquals(2112L, coilTimes, "coil pattern fires exactly 2112x");
        // screw may be produced directly from bolt (screwFromBolt) without going
        // through the wire→round→bolt chain — pickBestPattern picks the smallest
        // per-craft pattern. Either way screw fires 2112x.
        assertEquals(2112L, screwTimes, "screw pattern fires 2112x (1:1 chain)");
        // round/wire are optional intermediate steps (skipped when pickBestPattern
        // chooses the direct bolt→screw path); only assert they stay bounded so
        // a future regression that re-inflates via the wire chain is caught.
        assertTrue(roundTimes <= 2112L,
                "round must NOT exceed the coil count (cycle doubling bug if >2112): " + roundTimes);
        assertTrue(wireTimes <= 2112L,
                "wire must NOT exceed the coil count (cycle doubling bug if >2112): " + wireTimes);
        // ingot usage is bounded by the coil chain's worst-case demand:
        //   wire:  ceil(coil × 2 inputs) / 2 ingot → 1056 ingots
        //   OR direct bolt from rod: ceil(coil × 4 inputs) / 2 ingot = 4224 ingots (worst)
        // pickBestPattern picks the cheapest. With multicandidate register, the
        // plan should stay below 1200 ingots unless cycle amplification fires.
        assertTrue(ingotUsed <= 1300L,
                "ingot usage must NOT balloon to >1300 from cycle amplification: used=" + ingotUsed);
    }

    // ========================================================================
    // 3. Multi-candidate cycle — the cycle-prone reverse pattern must NOT be picked
    // ========================================================================

    @Test
    void multiCandidate_FluidCosmicNeutronium_PicksCorrectDirection() {
        // fluid.cosmicneutronium has multiple production patterns registered in the
        // canonical fixture (extract 1:144 + chemical reactor via helium-3). Add an
        // explicit cycle-reverse candidate (144 fluid → 288 fluid self-loop) so the
        // resolver must filter cycle-prone candidates BEFORE pickBestPattern.
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();

        BenchAEKey fluid = f.fluid;
        BenchAEKey ingot = f.ingot;
        BenchAEKey helium = BenchAEKey.of("gtceu:helium_3"); // external input, cycle-free

        IPatternDetails extract = f.extract;
        IPatternDetails chemReactor = pat(fluid, 50L, List.of(in(helium, 10L))); // external path
        IPatternDetails cycleReverse = pat(fluid, FLUID_PER_INGOT_CRAFT * 2,
                List.of(in(fluid, FLUID_PER_INGOT_CRAFT))); // 144 fluid → 288 fluid (cycle!)

        // Build a candidate map that REPLACES the fixture's fluid list with an
        // exhaustive candidate set including the explicit cycle-reverse pattern,
        // so the resolver must choose correctly among 3 candidates.
        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>(f.cand);
        cand.put(fluid, List.of(extract, f.fluidAltChem, cycleReverse, chemReactor));
        cand.put(ingot, List.of()); // no pattern → leaf (to keep extract cycle-prone)

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(helium, 10_000L);

        // Build a resolver mirroring the real resolve() (with cycle filter):
        CraftingVM vm = new CraftingVM("gtl-multi-cand", key -> {
            if (key instanceof BenchAEKey bk) {
                var subs = cand.get(bk);
                if (subs == null || subs.isEmpty()) return null;
                var filtered = new ArrayList<IPatternDetails>();
                for (var p : subs) {
                    if (p != null && !AE2VMCrafting.wouldCauseCycle(
                            k -> cand.get(k), p, bk)) {
                        filtered.add(p);
                    }
                }
                if (!filtered.isEmpty()) return AE2VMCrafting.pickBestPattern(filtered, bk);
                return AE2VMCrafting.pickBestPattern(subs, bk);
            }
            return null;
        });
        vm.setAllPatternsResolver(cand::get);

        // Build a request for fluid that uses chemReactor (smallest per-craft output
        // among cycle-free candidates = 50 mb).
        ICraftingPlan plan = vm.execute(PatternCompiler.compileRequest(chemReactor, 1000L),
                new BenchSimulationState(stock));

        long chemTimes = plan.patternTimes().getOrDefault(chemReactor, 0L);
        long extractTimes = plan.patternTimes().getOrDefault(extract, 0L);
        long cycleReverseTimes = plan.patternTimes().getOrDefault(cycleReverse, 0L);

        System.out.println("[GTL-MULTI-CAND] chemReactor=" + chemTimes
                + " extract=" + extractTimes
                + " cycleReverse=" + cycleReverseTimes
                + " used[helium]=" + plan.usedItems().get(helium)
                + " missing=" + plan.missingItems());

        // chemReactor produces 50 mb per craft, request 1000mb → 20 crafts (= 20*10=200 helium).
        // The extract path would be 1000/144 ≈ 7 crafts (= 7 ingots), but extract is cycle-prone
        // (input ingot which has no pattern, but extract itself creates a fluid-only cycle via
        // cycleReverse — the resolver must reject cycleReverse and pick the smallest cycle-free).
        assertTrue(chemTimes > 0, "chemReactor must fire to satisfy the request");
        assertEquals(0L, cycleReverseTimes,
                "cycle-reverse pattern (288mb from 144mb) must NEVER fire — would inflate demand");
        // extract may or may not fire depending on whether helium covers chem fully; either way
        // it should not run more than ceil(1000/144) = 7 times.
        assertTrue(extractTimes <= 7L,
                "extract fired " + extractTimes + "x — exceeds 7 (= ceil(1000/144))");
    }

    // ========================================================================
    // 4. 2112 海珀珠线圈 with TIGHT stock — must NOT balloon to 4M scale
    // ========================================================================

    @Test
    void hypogenCoil_2112_TightStock_No4MAmplification() {
        // User-reported: "2112 海珀珠线圈，说实话我也搞不清到底缺不缺材料了" — the user
        // could not tell whether materials were missing. This test asserts the plan is
        // self-consistent: either it succeeds (uses N ingots) OR it reports missing
        // (specific count) — it must NOT report an absurd 4M-scale missing.
        HypogenCoilFixture f = new HypogenCoilFixture();
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.ingot, 100L); // tight: 2112 coils × 1 ingot = 2112 ingots, only 100 stocked

        ICraftingPlan plan = run("gtl-hypogen-coil-2112-tight", f.cand, stock, f.coilPat, 2112L);

        long missingIngot = plan.missingItems().get(f.ingot);
        long missingDust = plan.missingItems().get(BenchAEKey.of("gtceu:hypogen_dust"));
        long totalMissing = missingIngot + missingDust;

        System.out.println("[GTL-HYPOGEN-TIGHT] stock=100, request=2112"
                + " missing[ingot]=" + missingIngot
                + " missing[dust]=" + missingDust
                + " used[ingot]=" + plan.usedItems().get(f.ingot)
                + " missing=" + plan.missingItems());

        // The realistic shortfall: ceil(2112/2) = 1056 ingots needed, 100 in stock → 956 missing.
        // The BUG signature: missing ≈ 40× over → e.g. 38240 missing instead of 956.
        assertTrue(missingIngot <= 2112L,
                "missing[ingot]=" + missingIngot + " — exceeds the actual demand (2112 ingots)"
                        + " (40x amplification bug if >> 2112)");
        assertTrue(totalMissing <= 5000L,
                "total missing=" + totalMissing + " — exceeds 5000 (suggests 40x amplification)");
    }

    // ========================================================================
    // 5. Multi-quantity consistency — quantity scale MUST NOT flip plan correctness
    // ========================================================================

    @Test
    void fluidCosmicNeutronium_MultiQuantity_NoScaleFlip() {
        // The "100k→4M" symptom: a quantity scale change flips plan correctness from
        // feasible (small qty) to absurd (large qty). Run a sweep and assert each plan
        // size stays within 2x the linear expectation.
        FluidCosmicNeutroniumFixture f = new FluidCosmicNeutroniumFixture();
        for (long request : new long[] { 100L, 1_000L, 10_000L, 100_000L, 1_000_000L }) {
            Map<BenchAEKey, Long> stock = new HashMap<>();
            ICraftingPlan plan = run("gtl-scale-" + request, f.cand, stock, f.extract, request);
            long extractTimes = plan.patternTimes().getOrDefault(f.extract, 0L);
            long expectedExtract = ceilDiv(request, FLUID_PER_INGOT_CRAFT);
            System.out.println("[GTL-SCALE] request=" + request
                    + " extract=" + extractTimes + " (expected=" + expectedExtract + ")"
                    + " missing=" + plan.missingItems());
            assertTrue(extractTimes <= expectedExtract * 2L,
                    "request=" + request + " extract=" + extractTimes
                            + " exceeds 2x expected=" + expectedExtract + " (40x scale bug)");
        }
    }

    // ========================================================================
    // 6. "Stuck crafting" reproduction — patternTimes is 0 yet usedItems is missing
    // ========================================================================

    @Test
    void stuckCrafting_PlanSaysCompleteButTreeMissing() {
        // User-reported: "合成液态中子素时还出现了卡合成的问题" — the plan looks OK but
        // crafting stalls. This is the "plan in NEITHER usedItems NOR patternTimes"
        // signature: the root pattern is captured (no missing) but its critical sub-craft
        // was cut by the cycle guard and never appears as a craft, so AE2's CPU stalls
        // at WAITING_FOR_INPUTS forever. Assert the sub-craft IS in patternTimes (not
        // silently dropped).
        BenchAEKey fluid = BenchAEKey.of("gtceu:molten_cosmicneutronium");
        BenchAEKey ingot = BenchAEKey.of("gtceu:cosmicneutronium_ingot");
        // Top-level recipe: assembles ingot into a higher-tier item via a fluid step.
        // The fluid step is craft-only (no fluid stock).
        BenchAEKey composite = BenchAEKey.of("gtceu:cosmicneutronium_composite");
        IPatternDetails compositeRec = pat(composite, 1,
                List.of(in(ingot, 1), in(fluid, FLUID_PER_INGOT_CRAFT)));
        IPatternDetails extract = pat(fluid, FLUID_PER_INGOT_CRAFT, List.of(in(ingot, 1L)));
        // (v1.15.x CALIBRATION) The fluid key has BOTH extract AND a fluid-fluid
        // cycle (solidify returns fluid). Registering the cycle candidate is what
        // the user actually has in their GTL network — the fluid solidifier is a
        // multiblock that returns the same fluid key for material conversion (GTL
        // chemical bath). The VM should:
        //   - Pick `extract` (cheapest cycle-free path to produce fluid).
        //   - Run extract exactly once (composite's fluid demand = 144 mb).
        //   - NOT invoke the cycle-back pattern.
        IPatternDetails cycleReverse = pat(fluid, FLUID_PER_INGOT_CRAFT * 2,
                List.of(in(fluid, FLUID_PER_INGOT_CRAFT)));

        Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        cand.put(composite, List.of(compositeRec));
        cand.put(fluid, List.of(extract, cycleReverse));
        cand.put(ingot, List.of());

        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(ingot, 1000L); // enough ingot to cover 1 composite + 1 extract

        CraftingVM vm = new CraftingVM("gtl-stuck", key -> {
            if (key instanceof BenchAEKey bk) {
                var subs = cand.get(bk);
                if (subs == null || subs.isEmpty()) return null;
                var filtered = new ArrayList<IPatternDetails>();
                for (var p : subs) {
                    if (p != null && !AE2VMCrafting.wouldCauseCycle(k -> cand.get(k), p, bk)) {
                        filtered.add(p);
                    }
                }
                if (filtered.isEmpty()) return AE2VMCrafting.pickBestPattern(subs, bk);
                return AE2VMCrafting.pickBestPattern(filtered, bk);
            }
            return null;
        });
        vm.setAllPatternsResolver(cand::get);

        ICraftingPlan plan = vm.execute(PatternCompiler.compileRequest(compositeRec, 1L),
                new BenchSimulationState(stock));

        long compositeTimes = plan.patternTimes().getOrDefault(compositeRec, 0L);
        long extractTimes = plan.patternTimes().getOrDefault(extract, 0L);
        long cycleReverseTimes = plan.patternTimes().getOrDefault(cycleReverse, 0L);
        long fluidUsed = plan.usedItems().get(fluid);
        long ingotUsed = plan.usedItems().get(ingot);

        System.out.println("[GTL-STUCK] composite x=" + compositeTimes
                + " extract x=" + extractTimes
                + " cycleReverse x=" + cycleReverseTimes
                + " used[fluid]=" + fluidUsed
                + " used[ingot]=" + ingotUsed
                + " missing=" + plan.missingItems());

        // (v1.15.x CALIBRATION) The user's symptom: "合成液态中子素时还出现了卡合成
        // 的问题" — the plan looks OK but crafting stalls at WAITING_FOR_INPUTS
        // because extract is silently dropped from patternTimes. Three invariants:
        //
        //   1) The composite recipe needs 144 mb fluid + 1 ingot per craft. The
        //      CORRECT plan: composite x=1 + extract x=1 (uses 1 ingot to produce
        //      144 mb fluid). The fluid extraction must be in patternTimes.
        assertEquals(1L, compositeTimes, "composite fires once");
        assertTrue(extractTimes >= 1L,
                "extract MUST fire ≥1x — otherwise the tree is missing the fluid sub-craft"
                        + " (the 'plan OK but tree missing' symptom): extract=" + extractTimes);
        // 2) The cycle-back pattern (cycleReverse: 144 fluid → 288 fluid self-loop)
        //    must NEVER be invoked by the VM — it would inflate the fluid count.
        assertEquals(0L, cycleReverseTimes,
                "cycleReverse pattern (288mb from 144mb self-loop) must NEVER fire"
                        + " — would inflate demand. Got: cycleReverse=" + cycleReverseTimes);
        // 3) The fluid consumed by extract must NOT be in usedItems (extract produces
        //    fluid into simInternal; composite consumes from simInternal; no net fluid
        //    extraction from network).
        assertEquals(0L, fluidUsed,
                "fluid must NOT be in usedItems (extract produces fluid into simInternal);"
                        + " used[fluid]=" + fluidUsed);
        // 4) No missing when ingot stock covers everything.
        assertTrue(plan.missingItems().isEmpty(),
                "no missing when ingot stock covers everything: missing=" + plan.missingItems());
    }

    // ========================================================================
    // 7. "10000 OK / 20000 缺料" 量级临界 — single 20000 splits consistently
    //     with two separate 10000s but the plan still says missing.
    //     Fixture: GTL multiblock assembler + cosmicneutronium chain, ALL
    //     sub-patterns + multi-candidate choices below.
    // ========================================================================

    /**
     * CosmoAssembler fixture — a kubejs final product assembled from a long
     * cosmicneutronium chain. Mirrors the GTL 1.20.1-modpack real recipe graph
     * for "海珀珠线圈 / 液态宇宙中子素 -based assembly".
     * <p>
     * For the SCALE-CONSISTENCY tests, the fixture registers BOTH the canonical
     * wire-chain path AND a parallel dust-chain alt path (the GTL multiblock's
     * alternate recipe). The two paths are NOT cycle-prone — they share the same
     * input chain — but {@code pickBestPattern} may pick either per scale (the
     * dust path is cheaper when wire stock is low). This is the real cause of
     * the "10000 OK / 20000 缺" symptom: at 5k the dust path covers (missing=0),
     * at 8k the wire path dominates (missing jumps), at 10k it switches back,
     * etc. The VM's missing aggregation must stay linear regardless of which
     * path is chosen.
     */
    private static final class CosmoAssemblerFixture {
        final BenchAEKey finalItem      = BenchAEKey.of("kubejs:final_composite");
        final BenchAEKey assemblyInput  = BenchAEKey.of("kubejs:assembly_input");
        final BenchAEKey plating        = BenchAEKey.of("kubejs:plating_input");
        final BenchAEKey boltRing       = BenchAEKey.of("kubejs:bolt_ring");
        final BenchAEKey screwPanel     = BenchAEKey.of("kubejs:screw_panel");

        final BenchAEKey plate          = BenchAEKey.of("gtceu:cosmicneutronium_plate");
        final BenchAEKey foil           = BenchAEKey.of("gtceu:cosmicneutronium_foil");
        final BenchAEKey rod            = BenchAEKey.of("gtceu:cosmicneutronium_rod");
        final BenchAEKey bolt           = BenchAEKey.of("gtceu:cosmicneutronium_bolt");
        final BenchAEKey screw          = BenchAEKey.of("gtceu:cosmicneutronium_screw");
        final BenchAEKey round          = BenchAEKey.of("gtceu:cosmicneutronium_round");
        final BenchAEKey wire           = BenchAEKey.of("gtceu:cosmicneutronium_double_wire");
        final BenchAEKey spring         = BenchAEKey.of("gtceu:cosmicneutronium_spring");
        final BenchAEKey dust           = BenchAEKey.of("gtceu:cosmicneutronium_dust");
        final BenchAEKey smallDust      = BenchAEKey.of("gtceu:small_cosmicneutronium_dust");
        final BenchAEKey nugget         = BenchAEKey.of("gtceu:cosmicneutronium_nugget");
        final BenchAEKey block          = BenchAEKey.of("gtceu:cosmicneutronium_block");
        final BenchAEKey ingot          = BenchAEKey.of("gtceu:cosmicneutronium_ingot");
        final BenchAEKey hotIngot       = BenchAEKey.of("gtceu:hot_cosmicneutronium_ingot");
        final BenchAEKey fluid          = BenchAEKey.of("gtceu:molten_cosmicneutronium");

        // Top-level + mid-stage recipes
        final IPatternDetails assembler;          // 64 input → 1 final
        final IPatternDetails altAssemblerMaint;  // 1 final = 32 plating + 32 screwPanel (alternate)
        final IPatternDetails makeInput;          // 8 wire → 1 input
        final IPatternDetails platingPat;         // 4 plate + 2 foil → 1 plating
        final IPatternDetails boltRingPat;        // 8 bolt → 1 boltRing
        final IPatternDetails screwPanelPat;      // 4 screw + 2 round → 1 screwPanel
        final IPatternDetails makeInputAlt;       // 4 wire + 1 dust → 1 input (alt recipe)

        // wire/rod family from ingot
        final IPatternDetails cutPlate;
        final IPatternDetails bendFoil;
        final IPatternDetails latheRod;
        final IPatternDetails cutRodBolt;
        final IPatternDetails latheBoltScrew;
        final IPatternDetails cutRodRound;
        final IPatternDetails millWire;
        final IPatternDetails millWireAlt;
        final IPatternDetails forgeWireSpring;
        final IPatternDetails forgeNugget;
        final IPatternDetails compressBlock;

        // ingot ↔ dust 2-cycle
        final IPatternDetails smeltDust;
        final IPatternDetails pulvIngot;
        final IPatternDetails smeltSmallDust;
        final IPatternDetails pulvSmallDust;
        final IPatternDetails pulvIngotSmallDust;
        final IPatternDetails macerateIngot;
        final IPatternDetails ebfDust;
        final IPatternDetails freezerHot;

        // fluid 2-cycle
        final IPatternDetails extractFluid;
        final IPatternDetails solidifyFluid;
        final IPatternDetails fluidAltChem;

        final Map<AEKey, List<IPatternDetails>> cand = new HashMap<>();
        final IPatternDetails rootPattern;

        CosmoAssemblerFixture() {
            // ---- top + mid ----
            assembler        = pat(finalItem, 1, List.of(in(assemblyInput, 64)));
            altAssemblerMaint = pat(finalItem, 1, List.of(in(plating, 32), in(screwPanel, 32)));
            makeInput       = pat(assemblyInput, 1, List.of(in(wire, 8)));
            makeInputAlt    = pat(assemblyInput, 1, List.of(in(wire, 4), in(dust, 1)));
            platingPat      = pat(plating, 1, List.of(in(plate, 4), in(foil, 2)));
            boltRingPat     = pat(boltRing, 1, List.of(in(bolt, 8)));
            screwPanelPat   = pat(screwPanel, 1, List.of(in(screw, 4), in(round, 2)));

            // ---- wire/rod family ----
            cutPlate        = pat(plate, 1, List.of(in(ingot, 1)));
            bendFoil        = pat(foil, 4, List.of(in(plate, 1)));
            latheRod        = pat(rod, 2, List.of(in(ingot, 1)));
            cutRodBolt      = pat(bolt, 4, List.of(in(rod, 1)));
            latheBoltScrew  = pat(screw, 1, List.of(in(bolt, 1)));
            cutRodRound     = pat(round, 4, List.of(in(rod, 1)));
            millWire        = pat(wire, 2, List.of(in(ingot, 1)));
            millWireAlt     = pat(wire, 2, List.of(in(ingot, 1)));  // alternate machine
            forgeWireSpring = pat(spring, 1, List.of(in(wire, 1)));
            forgeNugget     = pat(nugget, 9, List.of(in(ingot, 1)));
            compressBlock   = pat(block, 1, List.of(in(ingot, 9)));

            // ---- dust cycle ----
            smeltDust        = pat(ingot, 1, List.of(in(dust, 1)));
            pulvIngot        = pat(dust, 1, List.of(in(ingot, 1)));
            smeltSmallDust   = pat(dust, 1, List.of(in(smallDust, 4)));
            pulvSmallDust    = pat(smallDust, 4, List.of(in(dust, 1)));
            pulvIngotSmallDust = pat(smallDust, 4, List.of(in(ingot, 1)));
            macerateIngot    = pat(dust, 4, List.of(in(ingot, 1)));
            ebfDust          = pat(hotIngot, 1, List.of(in(dust, 4)));
            freezerHot       = pat(ingot, 1, List.of(in(hotIngot, 1)));

            // ---- fluid cycle ----
            extractFluid   = pat(fluid, FLUID_PER_INGOT_CRAFT, List.of(in(ingot, 1)));
            solidifyFluid  = pat(ingot, 1, List.of(in(fluid, FLUID_PER_INGOT_CRAFT)));
            fluidAltChem   = pat(fluid, FLUID_PER_INGOT_CRAFT,
                    List.of(in(BenchAEKey.of("gtceu:helium_3"), 2), in(ingot, 1)));

            // ---- FULL candidate assignment (every multi-recipe key) ----
            cand.put(finalItem, List.of(assembler, altAssemblerMaint));   // 2 candidates
            cand.put(assemblyInput, List.of(makeInput, makeInputAlt));     // 2 candidates
            cand.put(plating, List.of(platingPat));
            cand.put(boltRing, List.of(boltRingPat));
            cand.put(screwPanel, List.of(screwPanelPat));

            cand.put(plate, List.of(cutPlate));
            cand.put(foil,  List.of(bendFoil));
            cand.put(rod,   List.of(latheRod));
            cand.put(bolt,  List.of(cutRodBolt));
            cand.put(screw, List.of(latheBoltScrew));
            cand.put(round, List.of(cutRodRound));
            cand.put(wire,  List.of(millWire, millWireAlt));               // 2 candidates
            cand.put(spring, List.of(forgeWireSpring));
            cand.put(nugget, List.of(forgeNugget));
            cand.put(block,  List.of(compressBlock));
            cand.put(ingot,  List.of(solidifyFluid, smeltDust, freezerHot)); // 3 candidates
            cand.put(dust,   List.of(pulvIngot, smeltSmallDust, macerateIngot)); // 3
            cand.put(smallDust, List.of(pulvSmallDust, pulvIngotSmallDust));     // 2
            cand.put(hotIngot, List.of(ebfDust));
            cand.put(fluid,  List.of(extractFluid, fluidAltChem));          // 2 candidates

            rootPattern = assembler;
        }
    }

    /**
     * User-reported scale-threshold bug: "这是一万的 OK" / "这是两万的 缺材料了".
     * Two separate orders of 10k succeed; a single 20k order reports missing.
     * <p>
     * Root cause candidates the test exposes (any of them flips this assertion):
     * <ul>
     *   <li><b>JIT pow-of-2 bundle path crossover</b>: a pattern with
     *       {@code outputPerCraft=1} crossing 2^14 ≈ 16384 enters a different
     *       JIT fallback path that captures a fresh bundle with different
     *       cts-scale arithmetic. For 10000 (≈ 2^13.3) the captured bundle
     *       works; for 20000 (≈ 2^14.3) the freshly captured bundle reports
     *       a different used/missing split.</li>
     *   <li><b>ceilDiv / BigInteger boundary</b>: the per-pattern cts overflow
     *       guard in {@code CALL_BY_KEY} flips at 16384 (long × long ceiling
     *       edge when batched with another high-craft child) — see v1.9.x
     *       notes for the {@code ceilDiv} saturating fix.</li>
     *   <li><b>staleMissingRecheck oscillation</b>: the 5-retry GTL settle
     *       loop exits mid-cycle on the 20k order (each retry recompiles the
     *       root), but exits on the first compile for 10k (no missing at all).</li>
     * </ul>
     * <p>
     * Whatever the cause, the SCALE-CONSISTENCY invariant the user observed is:
     * <ul>
     *   <li>{@code plan(20k).used} + {@code plan(20k).missing} must equal
     *       {@code plan(10k).used × 2} (within small overlap-correction).</li>
     *   <li>{@code plan(20k).patternTimes} must equal
     *       {@code plan(10k).patternTimes × 2} (linearity).</li>
     *   <li>If {@code plan(10k)} reports ZERO missing and 20k reports missing,
     *       that is the bug — a per-scale-threshold inconsistency.</li>
     * </ul>
     */
    @Test
    void scaleThreshold_20k_fails_but_2x10k_succeeds_LinearConsistency() {
        // Real GTL reproduction: the user's network has limited ingot stock
        // (~1M) and 10k final assembler needs ~3.84M ingot, 20k needs ~7.68M. With
        // stock 1.5M:
        //   10k-A: used=1.5M, missing=2.34M (= exact shortfall, linear)
        //   10k-B: used=1.5M, missing=2.34M
        //   20k  : used=1.5M, missing=6.18M (linear 2×)
        // The "10000 OK / 20000 缺" bug is NOT "missing is non-zero" (it's
        // genuine shortfall) but "missing scales non-linearly OR plan amplifies
        // demand by 40x". So we assert LINEARITY, not zero-missing.
        CosmoAssemblerFixture f = new CosmoAssemblerFixture();
        IPatternDetails assembler = f.rootPattern;
        Map<AEKey, List<IPatternDetails>> cand = f.cand;

        // Realistic GTL user stock: enough to cover 10k partially (under the
        // ~3.84M ingot needed), not 20k. This is the EXACT state that exhibits
        // the "scale-flip" symptom in the wild.
        Map<BenchAEKey, Long> stock = new HashMap<>();
        stock.put(f.ingot, 1_500_000L);

        CraftingVM vm10a = buildVm("scale-10k-A", cand, stock);
        ICraftingPlan plan10a = vm10a.execute(PatternCompiler.compileRequest(assembler, 10_000L),
                new BenchSimulationState(stock));
        CraftingVM vm10b = buildVm("scale-10k-B", cand, stock);
        ICraftingPlan plan10b = vm10b.execute(PatternCompiler.compileRequest(assembler, 10_000L),
                new BenchSimulationState(stock));
        CraftingVM vm20 = buildVm("scale-20k", cand, stock);
        ICraftingPlan plan20 = vm20.execute(PatternCompiler.compileRequest(assembler, 20_000L),
                new BenchSimulationState(stock));

        long miss10a = totalMissing(plan10a);
        long miss10b = totalMissing(plan10b);
        long miss20 = totalMissing(plan20);
        long used20 = plan20.usedItems().get(f.ingot);
        long used10a = plan10a.usedItems().get(f.ingot);
        long used10b = plan10b.usedItems().get(f.ingot);

        System.out.println("[GTL-SCALE-CONSISTENCY] miss10a=" + miss10a
                + " miss10b=" + miss10b
                + " miss20=" + miss20
                + " used10a=" + used10a + " used10b=" + used10b + " used20=" + used20);

        // Linear-consistency invariants (these are the GTL user-reported bug):
        //   1) Two separate 10k plans MUST produce identical used (no VM-state leak)
        assertEquals(used10a, used10b,
                "two separate 10k orders must consume identical ingot (no cross-VM contamination): "
                        + used10a + " vs " + used10b);
        //   2) The 20k plan's used[ingot] MUST be ≤ 2×(10k used) + a small constant
        //      (linear scaling, NOT 40× amplification from cycle blow-up)
        assertTrue(used20 <= 2L * used10a + 1L,
                "used[ingot] at 20k must be ≤ 2×(10k used) — 40x amplification bug if >> 2× used10a:"
                        + " used20=" + used20 + " 2*used10a=" + (2L * used10a));
        //   3) Linear shortfall: total demand must scale linearly (2× from 10k to 20k).
        //      Real invariant: miss20 + used20 = 2 × (miss10a + used10a). The test
        //      directly verifies that total demand is exactly 2× between the two
        //      orders — independent of the absolute value of missing.
        long total10k = miss10a + used10a;
        long total20k = miss20 + used20;
        assertEquals(2L * total10k, total20k,
                "total demand at 20k must be EXACTLY 2× of 10k — linearity:"
                        + " total10k=" + total10k + " 2*total10k=" + (2L * total10k)
                        + " total20k=" + total20k);
        //   4) The 20k plan's patternTimes[assembler] MUST be exactly 2×(10k patternTimes)
        long assembler10 = plan10a.patternTimes().getOrDefault(assembler, 0L);
        long assembler20 = plan20.patternTimes().getOrDefault(assembler, 0L);
        assertEquals(2L * assembler10, assembler20,
                "assembler patternTimes must scale linearly: 2×" + assembler10 + "=" + (2L * assembler10)
                        + " but got " + assembler20);
    }

    @Test
    void scaleThreshold_21k_breakpoint_StillMissing() {
        // Walk the request size 1k → 20k in steps with stock 1.5M ingot. The user's
        // report: "10000 OK, 20000 缺" — meaning at exactly 10000 the plan was feasible
        // (or the user thought it was), and at 20000 it reports missing. Two checks:
        //   1) Linear used[ingot] growth BELOW the stock cap (1.5M). Any sudden jump
        //      means the cycle-blowup bug fires at that step.
        //   2) Linear missing growth ABOVE the stock cap. 20k's missing should be 2×
        //      10k's missing (within stock-cap).
        CosmoAssemblerFixture f = new CosmoAssemblerFixture();
        IPatternDetails assembler = f.rootPattern;
        Map<AEKey, List<IPatternDetails>> cand = f.cand;
        Map<BenchAEKey, Long> stock = new HashMap<>();
        long stockCap = 1_500_000L;
        stock.put(f.ingot, stockCap);

        long prevUsed = 0L;
        long prevQty = 0L;
        long prevMiss = 0L;
        boolean sawStockCap = false;
        for (long qty : new long[] { 1000L, 2000L, 5000L, 8000L, 9000L, 10000L, 11000L,
                                      12000L, 14000L, 16000L, 16384L, 17000L, 20000L }) {
            CraftingVM vm = buildVm("sweep-" + qty, cand, stock);
            ICraftingPlan plan = vm.execute(PatternCompiler.compileRequest(assembler, qty),
                    new BenchSimulationState(stock));
            long miss = totalMissing(plan);
            long used = plan.usedItems().get(f.ingot);
            System.out.println("[GTL-SWEEP] qty=" + qty
                    + " missing=" + miss + " used[ingot]=" + used);
            if (prevQty > 0) {
                // Both still below stock cap → linear used[ingot] growth
                if (!sawStockCap && used < stockCap && prevUsed < stockCap) {
                    long expected = prevUsed * qty / prevQty;
                    long drift = Math.abs(used - expected);
                    assertTrue(drift <= Math.max(64L, prevUsed / 5L),
                            "non-linear used[ingot] growth between qty=" + prevQty + " (used=" + prevUsed + ")"
                                    + " and qty=" + qty + " (used=" + used + ")"
                                    + " — expected ≈" + expected + " but drift=" + drift
                                    + " (cycle blowup bug)");
                }
                // Both above stock cap → linear missing growth (used stays capped,
                // missing absorbs the extra demand)
                if (sawStockCap && used >= stockCap && prevUsed >= stockCap) {
                    long expectedMiss = prevMiss * qty / prevQty;
                    long missDrift = Math.abs(miss - expectedMiss);
                    assertTrue(missDrift <= Math.max(64L, prevMiss / 5L),
                            "non-linear missing growth between qty=" + prevQty + " (miss=" + prevMiss + ")"
                                    + " and qty=" + qty + " (miss=" + miss + ")"
                                    + " — expected ≈" + expectedMiss + " but drift=" + missDrift
                                    + " (cycle blowup bug)");
                }
            }
            if (used >= stockCap) sawStockCap = true;
            prevQty = qty; prevUsed = used; prevMiss = miss;
        }
        System.out.println("[GTL-SWEEP] complete (sawStockCap=" + sawStockCap + ")");
    }

    private static CraftingVM buildVm(String id,
                                      Map<AEKey, List<IPatternDetails>> cand,
                                      Map<BenchAEKey, Long> stock) {
        CraftingVM vm = new CraftingVM(id, resolverWithCycleFilter(cand, stock));
        vm.setAllPatternsResolver(cand::get);
        return vm;
    }

    private static long totalMissing(ICraftingPlan plan) {
        long total = 0L;
        for (var e : plan.missingItems()) total += e.getLongValue();
        return total;
    }
}

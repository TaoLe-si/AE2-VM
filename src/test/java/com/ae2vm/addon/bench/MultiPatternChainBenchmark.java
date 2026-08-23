package com.ae2vm.addon.bench;

import appeng.api.crafting.IPatternDetails;
import com.ae2vm.addon.api.AE2VMCrafting;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmark-JUnit for the GTL "下单物品" (hatch) false-positive:
 * <p>
 * Two patterns produce the same primary output ({@code opv hatch}, 1 each).
 * Pattern A is the FOA mega-pattern: 8 heavy inputs (plutonium, carbon, helium,
 * fish oil, duranium, liquid ender air, cosmic neutronium, xenon) — the one the VM
 * wrongly picked in 1.12.23, producing a 1.25T-unit plan with only 3 "missing"
 * items and no report of the actually-unavailable mid-tier inputs.
 * Pattern B is the normal recipe: 16 mid-tier inputs (fine copper wire, SMD
 * capacitor, PCB, solder…) whose machines are NOT built, so they must be reported
 * missing.
 * <p>
 * The fix drives {@link AE2VMCrafting#pickBestPattern} to prefer the pattern with
 * the FEWER INPUTS when per-craft output amounts tie (both = 1), which selects
 * Pattern B, making the plan report the mid-tier missing set instead of the
 * mega-path.
 */
class MultiPatternChainBenchmark {

    // ---- the two recipe lines for the ordered item -----------------------------
    private static final BenchAEKey HATCH       = BenchAEKey.of("gtmadvancedhatch:opv_2147483647a_net_laser_source_hatch");
    private static final BenchAEKey PLUTONIUM   = BenchAEKey.of("gtceu:plutonium_ingot");
    private static final BenchAEKey CARBON      = BenchAEKey.of("gtceu:carbon_dust");
    private static final BenchAEKey HELIUM      = BenchAEKey.of("gtceu:liquid_helium");
    private static final BenchAEKey FISH_OIL    = BenchAEKey.of("gtceu:fish_oil");
    private static final BenchAEKey DURANIUM    = BenchAEKey.of("gtceu:duranium");
    private static final BenchAEKey ENDER_AIR   = BenchAEKey.of("gtceu:liquid_ender_air");
    private static final BenchAEKey COSMIC_NI   = BenchAEKey.of("gtceu:cosmicneutronium_ingot");
    private static final BenchAEKey XENON       = BenchAEKey.of("gtceu:xenon");

    private static final BenchAEKey COPPER_WIRE = BenchAEKey.of("gtceu:fine_copper_wire");
    private static final BenchAEKey SMD_CAP     = BenchAEKey.of("gtceu:smd_capacitor");
    private static final BenchAEKey PPS_FOIL    = BenchAEKey.of("gtceu:polyphenylene_sulfide_foil");
    private static final BenchAEKey MPIC        = BenchAEKey.of("gtceu:mpic_wafer");
    private static final BenchAEKey NOR_CHIP    = BenchAEKey.of("gtceu:nor_memory_chip");
    private static final BenchAEKey PERIOD_WAFER= BenchAEKey.of("gtceu:periodicium_wafer");
    private static final BenchAEKey RAM_CHIP    = BenchAEKey.of("gtceu:ram_chip");
    private static final BenchAEKey SMD_DIODE   = BenchAEKey.of("gtceu:smd_diode");
    private static final BenchAEKey TIN         = BenchAEKey.of("gtceu:tin");
    private static final BenchAEKey PVC_FOIL    = BenchAEKey.of("gtceu:polyvinyl_chloride_foil");
    private static final BenchAEKey ENDER_PEARL = BenchAEKey.of("gtceu:ender_pearl");
    private static final BenchAEKey MUT_SOLDER  = BenchAEKey.of("gtceu:super_mutated_living_solder");
    private static final BenchAEKey UXV_PUMP    = BenchAEKey.of("gtceu:uxv_electric_pump");
    private static final BenchAEKey FIBER_PCB   = BenchAEKey.of("gtceu:fiber_reinforced_printed_circuit_board");
    private static final BenchAEKey SBR         = BenchAEKey.of("gtceu:styrene_butadiene_rubber");
    private static final BenchAEKey SOLDER_ALLOY= BenchAEKey.of("gtceu:soldering_alloy");

    private static IPatternDetails foaPattern() {
        return new BenchPatternDetails(HATCH, 1, List.of(
                BenchPatternDetails.InputSpec.of(PLUTONIUM, 195891736L),
                BenchPatternDetails.InputSpec.of(CARBON, 181697490L),
                BenchPatternDetails.InputSpec.of(HELIUM, 121322986L),
                BenchPatternDetails.InputSpec.of(FISH_OIL, 55085313L),
                BenchPatternDetails.InputSpec.of(DURANIUM, 46868904L),
                BenchPatternDetails.InputSpec.of(ENDER_AIR, 810400000L),
                BenchPatternDetails.InputSpec.of(COSMIC_NI, 102),
                BenchPatternDetails.InputSpec.of(XENON, 2964064800L)));
    }

    private static IPatternDetails normalPattern() {
        return new BenchPatternDetails(HATCH, 1, List.of(
                BenchPatternDetails.InputSpec.of(COPPER_WIRE, 3448),
                BenchPatternDetails.InputSpec.of(SMD_CAP, 3648),
                BenchPatternDetails.InputSpec.of(PPS_FOIL, 27829),
                BenchPatternDetails.InputSpec.of(MPIC, 734),
                BenchPatternDetails.InputSpec.of(NOR_CHIP, 2090496L),
                BenchPatternDetails.InputSpec.of(PERIOD_WAFER, 466),
                BenchPatternDetails.InputSpec.of(RAM_CHIP, 5328),
                BenchPatternDetails.InputSpec.of(SMD_DIODE, 1680),
                BenchPatternDetails.InputSpec.of(TIN, 94336),
                BenchPatternDetails.InputSpec.of(PVC_FOIL, 27829),
                BenchPatternDetails.InputSpec.of(ENDER_PEARL, 3638),
                BenchPatternDetails.InputSpec.of(MUT_SOLDER, 2119920L),
                BenchPatternDetails.InputSpec.of(UXV_PUMP, 12750),
                BenchPatternDetails.InputSpec.of(FIBER_PCB, 65404),
                BenchPatternDetails.InputSpec.of(SBR, 1547352L),
                BenchPatternDetails.InputSpec.of(SOLDER_ALLOY, 14999160L)));
    }

    /** Regression: FOA-like tie must NOT win; smaller-total-input normal line must win. */
    @Test
    void pickBestPatternPrefersSmallerTotalInputOnTie() {
        IPatternDetails foa = foaPattern();
        IPatternDetails normal = normalPattern();
        // Both have primary output amount = 1 → the total-input-amount tiebreaker must pick normal
        // because FOA total ≈ 4.3B, normal total ≈ 19M.
        System.out.println("[multi-pattern] foa inputs=" + foa.getInputs().length
                + " totalInput=" + totalInputAmount(foa)
                + " outputs=" + foa.getOutputs().length + " primary=" + foa.getPrimaryOutput());
        System.out.println("[multi-pattern] normal inputs=" + normal.getInputs().length
                + " totalInput=" + totalInputAmount(normal)
                + " outputs=" + normal.getOutputs().length + " primary=" + normal.getPrimaryOutput());
        System.out.println("[multi-pattern] patternOutputs(foa,HATCH)=" + AE2VMCrafting.patternOutputs(foa, HATCH)
                + " patternOutputs(normal,HATCH)=" + AE2VMCrafting.patternOutputs(normal, HATCH));
        IPatternDetails chosen = AE2VMCrafting.pickBestPattern(List.of(foa, normal), HATCH);
        assertNotNull(chosen, "pickBestPattern returned null");
        assertTrue(totalInputAmount(chosen) == totalInputAmount(normal),
                "expected normal recipe (totalInput=" + totalInputAmount(normal) + "), got "
                        + "totalInput=" + totalInputAmount(chosen) + ": " + firstInput(chosen));

        // And the reverse order must not matter.
        IPatternDetails chosen2 = AE2VMCrafting.pickBestPattern(List.of(normal, foa), HATCH);
        assertTrue(totalInputAmount(chosen2) == totalInputAmount(normal),
                "order-independent: expected normal recipe, got totalInput=" + totalInputAmount(chosen2));
    }

    /** The mega-pattern alone would still be picked when it is the only candidate. */
    @Test
    void pickBestPatternFallsBackToSingleCandidate() {
        IPatternDetails foa = foaPattern();
        IPatternDetails chosen = AE2VMCrafting.pickBestPattern(List.of(foa), HATCH);
        assertNotNull(chosen);
        assertTrue(chosen.getInputs().length == 8,
                "single candidate → FOA path (8 inputs) expected, got " + chosen.getInputs().length + " inputs");
        System.out.println("[multi-pattern] single candidate → FOA path chosen (fallback) OK");
    }

    @Test
    void patternOutputsMatchesRequestedKey() {
        assertTrue(AE2VMCrafting.patternOutputs(foaPattern(), HATCH));
        assertTrue(AE2VMCrafting.patternOutputs(normalPattern(), HATCH));
        System.out.println("[multi-pattern] patternOutputs(HATCH) matches both candidates ✓");
    }

    private static long totalInputAmount(IPatternDetails p) {
        long total = 0;
        for (var input : p.getInputs()) {
            var stacks = input.getPossibleInputs();
            if (stacks != null && stacks.length > 0 && stacks[0] != null) {
                total += stacks[0].amount();
            }
        }
        return total;
    }

    private static String firstInput(IPatternDetails p) {
        if (p.getInputs().length == 0) return "(no inputs)";
        var first = p.getInputs()[0];
        var what = first.getPossibleInputs()[0].what();
        return what.toString() + " x" + first.getPossibleInputs()[0].amount();
    }
}
